package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.DecodedAudio
import com.example.connectallspeakers.audio.wasapi.PlaybackRenderSession
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.PlaybackRoute
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class PlaybackRenderSessionTest {
    private val routes = listOf(PlaybackRoute("a", SpeakerChannel.Left, 80), PlaybackRoute("b", SpeakerChannel.Right, 80))
    private fun source(frames: Int = 160) = DecodedAudio("test", 8_000, 1, FloatArray(frames) { .25f })

    @Test fun allOutputsArePreparedBeforeAnyStartsAndCloseIsIdempotent() {
        val devices = FakePlaybackDevices()
        val session = PlaybackRenderSession(source(), devices)
        session.prepare(routes) {}
        assertTrue(devices.events.indexOf("submit:b") < devices.events.indexOf("start:a"))
        session.close(); session.close()
        assertEquals(1, devices.closed)
        devices.endpoints.values.forEach { assertEquals(1, it.closed) }
        assertEquals("close:devices", devices.events.last())
    }
    @Test fun secondOpenFailureNeverStartsFirstAndReleasesEverything() {
        val devices = FakePlaybackDevices().apply { failOpen = "b" }
        val session = PlaybackRenderSession(source(), devices)
        assertFailsWith<IllegalStateException> { session.prepare(routes) {} }
        assertFalse(devices.events.any { it.startsWith("start:") })
        assertEquals(1, devices.endpoints.getValue("a").closed)
        assertEquals(1, devices.closed)
    }
    @Test fun cancellationBeforeStartReleasesPreparedOutputsWithoutPlaying() {
        val devices = FakePlaybackDevices()
        val session = PlaybackRenderSession(source(), devices)
        assertFailsWith<CancellationException> {
            session.prepare(routes) { if (devices.endpoints.size == 2) throw CancellationException() }
        }
        assertFalse(devices.events.any { it.startsWith("start:") })
        assertEquals(1, devices.closed)
    }
    @Test fun startFailureStopsAlreadyStartedOutput() {
        val devices = FakePlaybackDevices()
        val session = PlaybackRenderSession(source(), devices)
        assertFailsWith<IllegalStateException> {
            session.prepare(routes) { devices.endpoints["b"]?.failStart = true }
        }
        assertFalse(devices.endpoints.getValue("a").started)
        assertEquals(1, devices.closed)
    }
    @Test fun finalBufferMustDrainAndPositionUsesSlowestOutput() {
        val devices = FakePlaybackDevices()
        val session = PlaybackRenderSession(source(80), devices)
        session.prepare(routes) {}
        devices.endpoints.getValue("a").queued = 0
        devices.endpoints.getValue("b").queued = 40
        assertFalse(session.pump(routes))
        assertEquals(5L, session.positionMillis)
        devices.endpoints.getValue("b").queued = 0
        assertTrue(session.pump(routes))
        assertEquals(10L, session.positionMillis)
        session.close()
    }
    @Test fun pauseResumeRetainsQueuesAndLiveVolumeIsUsedOnNextBuffer() {
        val devices = FakePlaybackDevices()
        val session = PlaybackRenderSession(source(), devices)
        session.prepare(routes) {}
        session.pause()
        assertTrue(devices.endpoints.values.all { it.queued == 80 && !it.started })
        session.resume {}
        devices.endpoints.values.forEach { it.queued = 40 }
        session.pump(routes.map { it.copy(volumePercent = 0) })
        assertTrue(devices.endpoints.values.all { it.submitted.all { byte -> byte == 0.toByte() } })
        session.close()
    }
    @Test fun underrunAndMembershipChangeAreExplicitFailures() {
        val devices = FakePlaybackDevices()
        val session = PlaybackRenderSession(source(), devices)
        session.prepare(routes) {}
        devices.endpoints.getValue("a").queued = 0
        assertFailsWith<IllegalStateException> { session.pump(routes) }
        assertFailsWith<IllegalArgumentException> { session.pump(routes.take(1)) }
        session.close()
    }
    @Test fun cleanupContinuesWhenStopFails() {
        val devices = FakePlaybackDevices()
        val session = PlaybackRenderSession(source(), devices)
        session.prepare(routes) {}
        devices.endpoints.getValue("b").failStop = true
        assertFailsWith<IllegalStateException> { session.close() }
        assertEquals(1, devices.closed)
        assertTrue(devices.endpoints.values.all { it.closed == 1 })
    }
}
