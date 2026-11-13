package com.example.connectallspeakers

import com.example.connectallspeakers.audio.wasapi.LiveRenderSession
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.*

/** Fake capture/queues only; proves live processing/lifecycle without playing or enumerating hardware. */
class LiveRenderSessionTest {
    private val routes = listOf(PlaybackRoute("a", SpeakerChannel.Left, 40), PlaybackRoute("b", SpeakerChannel.Right, 60))
    private fun pump(rig: LiveRig, session: LiveRenderSession, count: Int, routes: List<PlaybackRoute> = this.routes) {
        repeat(count) { rig.advance(); session.pump(routes) {} }
    }
    private fun samples(bytes: ByteArray): List<Float> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return List(bytes.size / 4) { buffer.float }
    }

    @Test fun bothTargetsWaitForFreshPrebufferThenReceiveIndependentChannelsAndGain() {
        val rig = LiveRig()
        val session = LiveRenderSession("virtual", rig.capture, rig.devices) { rig.nanos }
        try {
            session.prepare(routes) {}
            pump(rig, session, 10)
            assertTrue(rig.endpoints.values.none { it.running })
            pump(rig, session, 15)
            assertTrue(rig.endpoints.values.all { it.running })
            assertEquals(setOf(.1f), samples(rig.endpoints.getValue("a").lastBytes).toSet())
            assertEquals(setOf(-.3f), samples(rig.endpoints.getValue("b").lastBytes).toSet())
            val edited = routes.map { if (it.endpointId == "b") it.copy(channel = SpeakerChannel.Mono, volumePercent = 20) else it }
            pump(rig, session, 1, edited)
            assertEquals(setOf(-.025f), samples(rig.endpoints.getValue("b").lastBytes).toSet())
            assertEquals(1, rig.endpoints.getValue("a").started)
        } finally { session.close(); session.close() }
        assertEquals(1, rig.closedCapture); assertEquals(1, rig.closedDevices)
        assertTrue(rig.endpoints.values.all { it.closed == 1 && !it.running && it.queued == 0 })
        assertEquals(0, session.status().bufferedFrames)
    }

    @Test fun twentyMinuteIndependentClockRunKeepsTwoSecondHistoryBoundedAndTracksSource() {
        val rig = LiveRig().apply { inputSkewPpm = 175.0 }
        val session = LiveRenderSession("virtual", rig.capture, rig.devices) { rig.nanos }
        try {
            session.prepare(routes) {}
            repeat(120_000) { tick ->
                rig.advance(); session.pump(routes) {}
                if (tick % 1000 == 0) {
                    val status = session.status()
                    assertTrue(status.bufferedFrames in 0..96_000)
                    if (tick > 10_000) {
                        assertEquals(LiveInputPhase.Forwarding, status.phase)
                        assertTrue(status.bufferedFrames in 1000..20_000, status.toString())
                    }
                }
            }
            val status = session.status()
            assertEquals(175.0 - -350.0, status.correctionPpm.getValue("a"), 10.0)
            assertEquals(175.0 - 250.0, status.correctionPpm.getValue("b"), 10.0)
            val timings = session.timingSnapshot()
            assertTrue(abs(timings[0].sourcePositionFrames!! - timings[1].sourcePositionFrames!!) < 600)
            assertTrue(timings.all { it.eq?.limitedFrames == 0L })
        } finally { session.close() }
    }

    @Test fun gapDiscardsNativeQueuesAndFreshInputRequiresNewPrebuffer() {
        val rig = LiveRig()
        val session = LiveRenderSession("virtual", rig.capture, rig.devices) { rig.nanos }
        try {
            session.prepare(routes) {}; pump(rig, session, 25)
            // No native queue is allowed to drain to zero before the bounded idle gap
            // is recognized; use a single worker observation after the source stops.
            rig.silentGap = true; rig.advance(110); session.pump(routes) {}
            assertEquals(LiveInputPhase.Idle, session.status().phase)
            assertEquals(0, session.status().bufferedFrames)
            assertTrue(rig.endpoints.values.all { !it.running && it.queued == 0 })
            rig.silentGap = false; session.pump(routes) {}
            pump(rig, session, 10)
            assertTrue(rig.endpoints.values.none { it.running })
            pump(rig, session, 15)
            assertTrue(rig.endpoints.values.all { it.started == 2 })
        } finally { session.close() }
    }

    @Test fun manualDelayAndEqUseSamePhysicalProcessingPathAndChangesNeedRestart() {
        val rig = LiveRig()
        val eq = SpeakerEq(enabled = true, mid = EqBand(1000.0, 6.0, 1.0))
        val delayed = routes.map { if (it.endpointId == "a") it.copy(delayMillis = 100, eq = eq) else it }
        val session = LiveRenderSession("virtual", rig.capture, rig.devices) { rig.nanos }
        try {
            session.prepare(delayed) {}; pump(rig, session, 21, delayed)
            assertEquals(setOf(0f), samples(rig.endpoints.getValue("a").lastBytes).toSet())
            pump(rig, session, 30, delayed)
            val timing = session.timingSnapshot().first()
            assertEquals(4800, timing.delayFrames)
            assertEquals(6.0, timing.eq!!.headroomDb)
            assertTrue(samples(rig.endpoints.getValue("a").lastBytes).all { it > 0f && it < .1f })
            assertFailsWith<IllegalStateException> { session.pump(routes) {} }
        } finally { session.close() }
    }

    @Test fun preparationFailureAndCancellationCloseEveryPartiallyOpenedResource() {
        val rig = LiveRig().apply { failOpenId = "b" }
        val session = LiveRenderSession("virtual", rig.capture, rig.devices) { rig.nanos }
        assertFails { session.prepare(routes) {} }
        assertEquals(1, rig.closedCapture); assertEquals(1, rig.closedDevices)
        assertEquals(1, rig.endpoints.getValue("a").closed)
        val second = LiveRig()
        val cancelled = LiveRenderSession("virtual", second.capture, second.devices) { second.nanos }
        try {
            cancelled.prepare(routes) {}
            pump(second, cancelled, 20)
            second.advance()
            var starts = 0
            assertFails { cancelled.pump(routes) { check(starts++ < 1) { "Cancelled between targets" } } }
        } finally { cancelled.close() }
        assertTrue(second.endpoints.values.all { !it.running && it.closed == 1 })
    }

    @Test fun selfRoutingDuplicatesAndUnsupportedNativeBuffersAreRejectedBeforeStart() {
        for (invalid in listOf(listOf(routes[0].copy(endpointId = "virtual")), listOf(routes[0], routes[0]))) {
            val rig = LiveRig()
            val session = LiveRenderSession("virtual", rig.capture, rig.devices)
            assertFailsWith<IllegalArgumentException> { session.prepare(invalid) {} }
            assertTrue(rig.endpoints.isEmpty())
            assertEquals(1, rig.closedCapture)
        }
    }

    @Test fun stalledWorkerStopsRatherThanReplayingOldCapturedAudio() {
        val rig = LiveRig()
        val session = LiveRenderSession("virtual", rig.capture, rig.devices) { rig.nanos }
        try {
            session.prepare(routes) {}; pump(rig, session, 25)
            val polls = rig.polls
            rig.advance(251)
            assertFailsWith<IllegalStateException> { session.pump(routes) {} }
            assertEquals(polls, rig.polls)
        } finally { session.close() }
        assertTrue(rig.endpoints.values.all { it.queued == 0 && it.closed == 1 })
    }

    @Test fun slowTargetPreparationDoesNotAccumulateStaleCapturePackets() {
        val rig = LiveRig()
        rig.beforeOpen = { assertEquals(0, rig.startedCapture); rig.advance(1000) }
        val session = LiveRenderSession("virtual", rig.capture, rig.devices) { rig.nanos }
        try {
            session.prepare(routes) {}
            assertEquals(1, rig.startedCapture)
            assertEquals(0L, session.status().inputFrames)
            pump(rig, session, 25)
            assertEquals(LiveInputPhase.Forwarding, session.status().phase)
            assertTrue(session.status().inputFrames < 48_000)
        } finally { session.close() }
    }
}
