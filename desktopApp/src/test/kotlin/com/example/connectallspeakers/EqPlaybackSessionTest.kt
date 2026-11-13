package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.*
import com.example.connectallspeakers.audio.wasapi.*
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import kotlin.test.*

/** Independent endpoint PCM captures, no native calls; covers both fixed and adaptive render paths. */
class EqPlaybackSessionTest {
    private class Endpoint(override val channels: Int) : PlaybackEndpoint {
        override val capacityFrames = 128
        var queued = 0
        val samples = mutableListOf<Float>()
        var closed = 0
        override fun queuedFrames() = queued
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
        var opens = 0; var closed = 0
        override fun openEndpoint(endpointId: String, sampleRate: Int): PlaybackEndpoint { opens++; return endpoints.getValue(endpointId) }
        override fun close() { closed++ }
    }
    private val cut = SpeakerEq(true, mid = EqBand(1000.0, -12.0))
    private fun routes(eq: SpeakerEq = cut, channel: SpeakerChannel = SpeakerChannel.Right) = listOf(
        PlaybackRoute("a", SpeakerChannel.Left, 80), PlaybackRoute("b", channel, 50, 7, eq))
    private fun source(rate: Int) = DecodedAudio("generated stereo", rate, 2,
        FloatArray(rate * 2) { index ->
            val frame = index / 2
            if (index % 2 == 0) (.25 * sin(2 * PI * 120 * frame / rate)).toFloat()
            else (.6 * sin(2 * PI * 1000 * frame / rate)).toFloat()
        })

    /** @param adaptive Whether b uses the drift-capable source cursor. @param pause Whether to interrupt queues.
     * @param rate Actual source rate. @param channel Source assignment for b. @return Fully captured endpoint samples.
     */
    private fun render(adaptive: Boolean, pause: Boolean, rate: Int, channel: SpeakerChannel = SpeakerChannel.Right): Map<String, List<Float>> {
        val devices = Devices(); val source = source(rate)
        val routes = routes(channel = channel)
        val session = PlaybackRenderSession(source, devices, if (adaptive) "a" else null)
        session.prepare(routes) {}
        var blocks = 0
        while (true) {
            devices.endpoints.values.forEach { it.queued = (it.queued - 64).coerceAtLeast(0) }
            if (pause && blocks++ == 5) {
                val counts = devices.endpoints.mapValues { it.value.samples.size }
                repeat(4) { session.pause(); session.measureTimings(it.toLong(), true); session.resume {} }
                assertEquals(counts, devices.endpoints.mapValues { it.value.samples.size })
            }
            if (session.pump(routes)) break
        }
        val timing = session.timingSnapshot().first { it.endpointId == "b" }
        assertEquals(0.0, timing.eq!!.headroomDb)
        assertEquals(0L, timing.eq!!.limitedFrames)
        assertEquals(7, timing.delayMillis)
        assertEquals(source.frames.toLong(), timing.sourcePositionFrames)
        session.close()
        assertEquals(1, devices.closed)
        assertTrue(devices.endpoints.values.all { it.closed == 1 })
        return devices.endpoints.mapValues { it.value.samples.toList() }
    }

    @Test fun eqOnlyChangesItsEndpointAndRetainsChannelGainDelayAndFiniteTail() {
        for (adaptive in listOf(false, true)) for (rate in listOf(8000, 48000)) {
            val output = render(adaptive, false, rate)
            val source = source(rate)
            val expected = ByteArray(source.frames * 2 * 4)
            ChannelRenderer.render(source, 0, source.frames, 2, routes().first(), expected)
            val expectedFloats = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN)
            output.getValue("a").forEach { assertEquals(expectedFloats.float.toRawBits(), it.toRawBits()) }
            val b = output.getValue("b")
            val delay = outputDelayFrames(7, rate)
            assertEquals(source.frames + rate / 2 + delay, b.size)
            assertTrue(b.take(delay).all { it == 0f })
            assertTrue(b.all { it.isFinite() && it in -1f..1f })
            val steady = b.subList(delay + rate / 2, delay + rate)
            val rms = sqrt(steady.sumOf { it.toDouble() * it } / steady.size)
            assertEquals(.6 * .5 * 10.0.pow(-12.0 / 20) / sqrt(2.0), rms, .00001)
            assertEquals(0f, b.last())
        }
    }

    @Test fun pauseRetainsFilterHistoryAndEveryRestartAndSourceRateGetsFreshState() {
        for (adaptive in listOf(false, true)) for (rate in listOf(8000, 44100)) {
            assertEquals(render(adaptive, false, rate), render(adaptive, true, rate))
            assertEquals(render(adaptive, true, rate), render(adaptive, true, rate))
        }
    }

    @Test fun monoRoutingRemainsAnAverageAndDoesNotAffectTheOtherEndpoint() {
        val right = render(false, false, 8000, SpeakerChannel.Right)
        val left = render(false, false, 8000, SpeakerChannel.Left)
        val mono = render(false, false, 8000, SpeakerChannel.Mono)
        assertEquals(right.getValue("a"), mono.getValue("a"))
        val b = mono.getValue("b")
        for (index in b.indices) assertEquals((left.getValue("b")[index] + right.getValue("b")[index]) * .5f, b[index], .000001f)
    }

    @Test fun unsupportedInitialFrequencyRejectsBeforeAnyEndpointAndBadLiveUpdateRetainsSafeEq() {
        val unsupported = cut.copy(high = EqBand(16000.0))
        val failed = Devices(); val invalidSession = PlaybackRenderSession(source(8000), failed)
        assertFailsWith<IllegalArgumentException> { invalidSession.prepare(routes(unsupported)) {} }
        assertEquals(0, failed.opens); assertEquals(1, failed.closed)
        val devices = Devices(); val session = PlaybackRenderSession(source(8000), devices)
        session.prepare(routes()) {}
        devices.endpoints.values.forEach { it.queued -= 64 }
        assertFalse(session.pump(routes(unsupported)))
        assertTrue(session.timingSnapshot().first { it.endpointId == "b" }.eq!!.message!!.contains("3600 Hz"))
        assertEquals(0, devices.closed)
        assertTrue(devices.endpoints.values.all { endpoint -> endpoint.samples.all { it.isFinite() } })
        session.close()
    }

    @Test fun fractionalDriftCursorWithEqKeepsItsHistoryAndDrainsBeforeManualDelayEnds() {
        for (ppm in listOf(-1000.0, 1000.0)) {
            val source = source(8000)
            val eq = OutputChannelEq(8000, cut)
            val renderer = AdaptivePcmRenderer(source, 2, 128, 56, eq)
            renderer.setCorrection(ppm)
            val buffer = ByteArray(128 * 2 * 4)
            var frames = 0
            while (!renderer.finished) {
                val count = renderer.render(buffer, 128, routes().last())
                assertTrue(count > 0)
                val bytes = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                repeat(count) {
                    val left = bytes.float; val right = bytes.float
                    assertEquals(left.toRawBits(), right.toRawBits())
                    assertTrue(left.isFinite() && left in -1f..1f)
                }
                frames += count
            }
            val sourceFrames = ceil(source.frames / (1 + ppm / 1_000_000)).toInt()
            assertTrue(abs(frames - (sourceFrames + 4000 + 56)) <= 1)
            assertEquals(source.frames.toDouble(), renderer.sourcePosition(frames.toDouble()))
            assertEquals(0, eq.remainingTailFrames)
        }
    }
}
