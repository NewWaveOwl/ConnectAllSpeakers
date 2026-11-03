package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.DecodedAudio
import com.example.connectallspeakers.audio.wasapi.*
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.*

/** Pumps the actual renderer against independently consuming fake endpoints on virtual time. */
class DriftPlaybackSessionTest {
    private class Devices(val rate: Int, followerPpm: Double) : PlaybackDeviceSession {
        var nanos = 0L
        val endpoints = mapOf("a" to Endpoint(this, 0.0), "b" to Endpoint(this, followerPpm))
        var closed = 0
        override fun openEndpoint(endpointId: String, sampleRate: Int) = endpoints.getValue(endpointId).also { check(rate == sampleRate) }
        fun advance(milliseconds: Int) {
            nanos += milliseconds * 1_000_000L
            endpoints.values.forEach { it.advance(milliseconds) }
        }
        override fun close() { closed++ }
    }
    private class Endpoint(private val devices: Devices, private val ppm: Double) : PlaybackEndpoint {
        override val channels = 2
        override val capacityFrames = devices.rate / 10
        var played = 0.0
        var submitted = 0
        var started = false
        var unavailableClock = false
        var clockJumpMillis = 0.0
        var closed = 0
        var maximumQueue = 0
        var samplePeak = 0f
        fun advance(milliseconds: Int) {
            if (started) played = minOf(submitted.toDouble(), played + milliseconds * devices.rate / 1000.0 * (1 + ppm / 1_000_000))
        }
        override fun queuedFrames() = submitted - floor(played).toInt()
        override fun clockReading(): NativeClockReading? = if (unavailableClock) null else NativeClockReading(
            ((played * 1000 / devices.rate + clockJumpMillis) * 1000).toLong(), 1_000_000,
            100_000_000 + devices.nanos / 100, 100_000_001 + devices.nanos / 100)
        override fun submit(samples: ByteArray, frames: Int) {
            check(queuedFrames() + frames <= capacityFrames)
            val values = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames * channels) { samplePeak = maxOf(samplePeak, abs(values.float)) }
            submitted += frames
            maximumQueue = maxOf(maximumQueue, queuedFrames())
        }
        override fun start() { started = true }
        override fun stop() { started = false }
        override fun reset() { submitted = 0; played = 0.0 }
        override fun close() { closed++ }
    }
    private val routes = listOf(PlaybackRoute("a", SpeakerChannel.Left, 80), PlaybackRoute("b", SpeakerChannel.Right, 60, 40))
    private fun source(rate: Int, seconds: Int) = DecodedAudio("generated", rate, 2,
        FloatArray(rate * seconds * 2) { if (it % 2 == 0) .25f else -.5f })

    @Test fun actualResamplingHoldsFiveMinuteEngineSkewBelowOneMillisecondWithBoundedQueues() {
        for (ppm in listOf(-200.0, 200.0)) {
            val devices = Devices(8_000, ppm)
            val session = PlaybackRenderSession(source(8_000, 302), devices, "a", { devices.nanos })
            var worst = 0.0
            try {
                session.prepare(routes) {}
                repeat(60_000) { tick ->
                    devices.advance(5)
                    assertFalse(session.pump(routes))
                    if (tick % 40 == 0) {
                        val timing = session.measureTimings(devices.nanos, false)
                        if (tick >= 24_000) { // Target declared for 120 seconds onward.
                            val actualDifference = (timing.last().sourcePositionFrames!! - timing.first().sourcePositionFrames!!) * 1000.0 / devices.rate
                            worst = maxOf(worst, abs(actualDifference - timing.last().drift!!.initialEngineOffsetMillis!!))
                        }
                    }
                }
                assertTrue(worst <= 1.0, "$ppm ppm: $worst ms")
                assertTrue(devices.endpoints.values.all { it.maximumQueue <= it.capacityFrames })
                assertEquals(.2f, devices.endpoints.getValue("a").samplePeak)
                assertEquals(.3f, devices.endpoints.getValue("b").samplePeak)
                println("Five-minute actual PCM renderer $ppm ppm: maximum queue-derived residual after 120 s = $worst ms")
            } finally { session.close() }
            assertEquals(1, devices.closed)
            assertTrue(devices.endpoints.values.all { it.closed == 1 })
        }
    }

    @Test fun correctedEofDrainsAtSeveralFormatsAndZeroCorrectionPreservesRoutingAndGain() {
        for (rate in listOf(8_000, 44_100, 48_000)) {
            val devices = Devices(rate, 0.0)
            val session = PlaybackRenderSession(source(rate, 1), devices, "a", { devices.nanos })
            session.prepare(routes) {}
            var completed = false
            repeat(250) {
                if (!completed) {
                    devices.advance(5); completed = session.pump(routes)
                    if (!completed && it % 40 == 0) session.measureTimings(devices.nanos, false)
                }
            }
            assertTrue(completed)
            assertEquals(rate, devices.endpoints.getValue("a").submitted)
            assertEquals(rate + outputDelayFrames(40, rate), devices.endpoints.getValue("b").submitted)
            assertEquals(1000L, session.positionMillis)
            assertEquals(0.0, session.timingSnapshot().last().drift!!.correctionPpm)
            session.close()
        }
    }

    @Test fun repeatedPauseResumeRetainsPcmAndDelayButRebaselinesClockHistory() {
        val devices = Devices(8_000, 200.0)
        val session = PlaybackRenderSession(source(8_000, 30), devices, "a", { devices.nanos })
        try {
            session.prepare(routes) {}
            repeat(2_000) { tick ->
                devices.advance(5); session.pump(routes)
                if (tick % 40 == 0) session.measureTimings(devices.nanos, false)
            }
            val before = devices.endpoints.getValue("b").submitted
            repeat(3) {
                session.pause(); devices.advance(60_000)
                assertEquals(DriftPhase.Paused, session.measureTimings(devices.nanos, true).last().drift!!.phase)
                assertEquals(before, devices.endpoints.getValue("b").submitted)
                session.resume {}
                assertEquals(DriftPhase.WarmingUp, session.measureTimings(devices.nanos, false).last().drift!!.phase)
            }
            assertEquals(320, session.timingSnapshot().last().delayFrames)
        } finally { session.close() }
    }

    @Test fun activeNonzeroCorrectionStillFlushesTheCompleteDelayTailAtEof() {
        for (ppm in listOf(-200.0, 200.0)) {
            val devices = Devices(8_000, ppm)
            val session = PlaybackRenderSession(source(8_000, 20), devices, "a", { devices.nanos })
            try {
                session.prepare(routes) {}
                var completed = false
                repeat(4_200) { tick ->
                    if (!completed) {
                        devices.advance(5); completed = session.pump(routes)
                        if (!completed && tick % 40 == 0) session.measureTimings(devices.nanos, false)
                    }
                }
                assertTrue(completed)
                val final = session.timingSnapshot().last()
                assertEquals(160_000L, final.sourcePositionFrames)
                assertEquals(0, final.queuedFrames)
                assertEquals(20_000L, session.positionMillis)
                assertEquals(DriftPhase.Draining, final.drift!!.phase)
                assertTrue(abs(final.drift!!.correctionPpm) > 1.0)
                assertNotEquals(160_320, devices.endpoints.getValue("b").submitted)
            } finally { session.close() }
        }
    }

    @Test fun lostClockAndDiscontinuityFailExplicitlyAndLeaveWholeSessionCleanupPossible() {
        for (jump in listOf(false, true)) {
            val devices = Devices(8_000, 0.0)
            val session = PlaybackRenderSession(source(8_000, 20), devices, "a", { devices.nanos })
            session.prepare(routes) {}
            repeat(2_000) { tick ->
                devices.advance(5); session.pump(routes)
                if (tick % 40 == 0) session.measureTimings(devices.nanos, false)
            }
            devices.endpoints.getValue("b").apply { unavailableClock = !jump; clockJumpMillis = if (jump) 50.0 else 0.0 }
            assertFailsWith<IllegalStateException> {
                repeat(800) { tick ->
                    devices.advance(5); session.pump(routes)
                    if (tick % 40 == 0) session.measureTimings(devices.nanos, false)
                }
            }
            assertEquals(DriftPhase.Failed, session.timingSnapshot().last().drift!!.phase)
            session.close()
            assertEquals(1, devices.closed)
        }
    }
}
