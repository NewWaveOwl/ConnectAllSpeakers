package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.DecodedAudio
import com.example.connectallspeakers.audio.wasapi.*
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.PlaybackRoute
import com.example.connectallspeakers.model.audio.outputDelayFrames
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

/** Captures rendered bytes and simulates consumption without constructing Windows services. */
class DelayedPlaybackSessionTest {
    private class Endpoint(override val channels: Int) : PlaybackEndpoint {
        override val capacityFrames = 80
        override val streamLatencyMillis = 12.5
        var queued = 0
        val samples = mutableListOf<Float>()
        var closed = 0
        override fun queuedFrames() = queued
        override fun clockReading() = NativeClockReading(0, 192_000, 10_000_000, 10_000_001)
        override fun submit(samples: ByteArray, frames: Int) {
            check(queued + frames <= capacityFrames)
            val bytes = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames * channels) { this.samples += bytes.float }
            queued += frames
        }
        override fun start() = Unit
        override fun stop() = Unit
        override fun reset() { queued = 0 }
        override fun close() { closed++ }
    }
    private class Devices : PlaybackDeviceSession {
        val endpoints = mapOf("a" to Endpoint(2), "b" to Endpoint(1))
        var closed = 0
        override fun openEndpoint(endpointId: String, sampleRate: Int) = endpoints.getValue(endpointId)
        override fun close() { closed++ }
    }
    private fun source(rate: Int, frames: Int = 151) = DecodedAudio("generated", rate, 2,
        FloatArray(frames * 2) { if (it % 2 == 0) .25f else -.5f })
    private fun routes(delay: Int) = listOf(PlaybackRoute("a", SpeakerChannel.Left, 100), PlaybackRoute("b", SpeakerChannel.Right, 50, delay))

    @Test fun independentDelaysPreserveRoutingGainAndTailAcrossRatesIncludingMaximum() {
        for (rate in listOf(8_000, 44_100, 48_000, 192_000)) for (delay in listOf(0, 7, 500)) {
            val devices = Devices()
            val source = source(rate)
            val session = PlaybackRenderSession(source, devices)
            val routes = routes(delay)
            session.prepare(routes) {}
            var completed = false
            repeat((source.frames + outputDelayFrames(delay, rate)) / 40 + 5) {
                if (!completed) {
                    devices.endpoints.values.forEach { it.queued = (it.queued - 40).coerceAtLeast(0) }
                    completed = session.pump(routes)
                }
            }
            assertTrue(completed, "$rate Hz/$delay ms")
            assertContentEquals(FloatArray(source.frames * 2) { .25f }, devices.endpoints.getValue("a").samples.toFloatArray())
            assertContentEquals(FloatArray(outputDelayFrames(delay, rate)) + FloatArray(source.frames) { -.25f },
                devices.endpoints.getValue("b").samples.toFloatArray())
            assertEquals(source.description.durationMillis, session.positionMillis)
            session.close()
            assertEquals(1, devices.closed)
            assertTrue(devices.endpoints.values.all { it.closed == 1 })
        }
    }

    @Test fun pauseRetainsDelayLineAndRestartPrimesExactlyOneFreshDelay() {
        val routes = routes(7)
        fun render(pause: Boolean): List<Float> {
            val devices = Devices()
            val session = PlaybackRenderSession(source(8_000), devices)
            session.prepare(routes) {}
            devices.endpoints.values.forEach { it.queued -= 40 }
            session.pump(routes)
            if (pause) {
                val before = devices.endpoints.getValue("b").samples.toList()
                repeat(3) { session.pause(); session.measureTimings(it.toLong(), true); session.resume {} }
                assertEquals(before, devices.endpoints.getValue("b").samples)
            }
            while (true) {
                devices.endpoints.values.forEach { it.queued = (it.queued - 40).coerceAtLeast(0) }
                if (session.pump(routes)) break
            }
            return devices.endpoints.getValue("b").samples.toList().also { session.close() }
        }
        assertEquals(render(false), render(true))
        assertEquals(render(true), render(true)) // A new session starts from fresh silence, not old FIFO contents.
    }

    @Test fun diagnosticSourcePositionExcludesDelayAndUnderrunCountsRemainAvailableBeforeCleanup() {
        val devices = Devices()
        val session = PlaybackRenderSession(source(8_000, 300), devices)
        val routes = routes(7) // 56 frames of initial silence on b.
        session.prepare(routes) {}
        devices.endpoints.values.forEach { it.queued = 40 }
        val timing = session.measureTimings(0, false).last()
        assertEquals(56, timing.delayFrames)
        assertEquals(80L, timing.submittedFrames)
        assertEquals(0L, timing.sourcePositionFrames)
        assertEquals(5.0, timing.queuedMillis)
        assertEquals(12.5, timing.streamLatencyMillis)
        assertNotNull(timing.clock)
        devices.endpoints.getValue("b").queued = 1
        assertEquals(23L, session.measureTimings(1, false).last().sourcePositionFrames)
        devices.endpoints.getValue("b").queued = 0
        assertFailsWith<IllegalStateException> { session.pump(routes) }
        assertEquals(1, session.timingSnapshot().last().underruns)
        session.close()
    }

    @Test fun invalidDiagnosticQueueFailsExplicitlyAndAllResourcesCanStillClose() {
        val devices = Devices()
        val session = PlaybackRenderSession(source(48_000), devices)
        session.prepare(routes(10)) {}
        devices.endpoints.getValue("a").queued = 81
        assertFailsWith<IllegalStateException> { session.measureTimings(0, false) }
        session.close()
        assertEquals(1, devices.closed)
    }
}
