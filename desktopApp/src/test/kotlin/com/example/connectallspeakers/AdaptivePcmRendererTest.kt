package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.*
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.PlaybackRoute
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.*

/** Offline fractional-cursor/routing checks; all storage is fixed at preparation. */
class AdaptivePcmRendererTest {
    @Test fun zeroCorrectionMatchesExistingChannelGainAndDelayBytesExactly() {
        for (channels in 1..2) for (channel in SpeakerChannel.entries) {
            val source = DecodedAudio("generated", 8_000, 2, FloatArray(302) { if (it % 2 == 0) .25f else -.5f })
            val route = PlaybackRoute("b", channel, 37, 7)
            val adaptive = AdaptivePcmRenderer(source, channels, 37, 56)
            val expected = ByteArray((source.frames + 56) * channels * 4)
            val actual = mutableListOf<Byte>()
            ChannelRenderer.render(source, 0, source.frames, channels, route, expected)
            PcmDelayLine(56, channels, source.frames + 56).process(expected, source.frames + 56)
            val buffer = ByteArray(37 * channels * 4)
            while (!adaptive.finished) {
                val frames = adaptive.render(buffer, 37, route)
                actual += buffer.take(frames * channels * 4)
            }
            assertContentEquals(expected, actual.toByteArray())
        }
    }

    @Test fun positiveNegativeAndChangingRatesKeepFractionalProgressContinuousAndChannelsIndependent() {
        for (rate in listOf(8_000, 44_100, 48_000, 192_000)) for (ppm in listOf(-1000.0, 1000.0)) {
            val source = DecodedAudio("generated", rate, 2, FloatArray(4000) { if (it % 2 == 0) .25f else -.5f })
            val renderer = AdaptivePcmRenderer(source, 2, 80, 10)
            val route = PlaybackRoute("b", SpeakerChannel.Right, 50)
            renderer.setCorrection(ppm)
            var count = 0
            val output = mutableListOf<Float>()
            val buffer = ByteArray(640)
            while (!renderer.finished) {
                val frames = renderer.render(buffer, 80, route)
                val floats = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                repeat(frames * 2) { output += floats.float }
                count += frames
                assertTrue(renderer.sourcePosition((count - 40).coerceAtLeast(0).toDouble())!!.isFinite())
            }
            assertTrue(abs(count - (ceil(source.frames / (1 + ppm / 1_000_000)).toInt() + 10)) <= 1)
            assertTrue(output.take(20).all { it == 0f })
            assertTrue(output.drop(20).all { abs(it + .25f) < .000001f })
            assertEquals(source.frames.toDouble(), renderer.sourcePosition(count.toDouble()))
        }
    }

    @Test fun historyWrapsWithinItsCapacityAndRefusesEvictedFutureAndInvalidPositions() {
        val history = SourceFrameHistory(5)
        repeat(100) { history.append((it + 1) * 2.0) }
        assertEquals(195.0, history.position(97.5))
        assertEquals(200.0, history.position(100.0))
        assertNull(history.position(95.0)); assertNull(history.position(100.1))
        assertNull(history.position(Double.NaN)); assertNull(history.position(Double.POSITIVE_INFINITY))
        assertEquals(0.0, history.position(-1.0))
        assertFailsWith<IllegalArgumentException> { history.append(1.0) }
        assertFailsWith<IllegalArgumentException> { SourceFrameHistory(864_003) }
    }

    @Test fun fractionalOutputReconstructsAnAnalyticalRampWithoutCrossingChannels() {
        val source = DecodedAudio("stereo ramps", 8_000, 2, FloatArray(2000) {
            val frame = it / 2
            if (it % 2 == 0) frame / 2000f else -.25f - frame / 4000f
        })
        val renderer = AdaptivePcmRenderer(source, 2, 80, 0)
        renderer.setCorrection(1000.0)
        val bytes = ByteArray(640)
        val route = PlaybackRoute("b", SpeakerChannel.Right, 50)
        repeat(4) { block ->
            assertEquals(80, renderer.render(bytes, 80, route))
            val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            repeat(80) { frame ->
                val index = block * 80 + frame
                val position = index * 1.001
                val expected = ((-.25 - position / 4000) * .5).toFloat()
                val left = floats.float
                val right = floats.float
                if (index >= 2) assertEquals(expected, left, .000001f)
                assertEquals(left, right)
            }
        }
    }

    @Test fun correctionBoundsAndRestartAreExplicitAndCubicOutputRemainsFiniteAndBounded() {
        val source = DecodedAudio("impulses", 8_000, 1, FloatArray(2000) { if (it % 9 == 0) 1f else -1f })
        val renderer = AdaptivePcmRenderer(source, 1, 80, 8)
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1000.1, 1000.1))
            assertFailsWith<IllegalArgumentException> { renderer.setCorrection(invalid) }
        val buffer = ByteArray(320)
        var blocks = 0
        while (!renderer.finished) {
            renderer.setCorrection(if (blocks++ % 2 == 0) 1000.0 else -1000.0)
            val frames = renderer.render(buffer, 80, PlaybackRoute("a", SpeakerChannel.Mono, 100))
            val bytes = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) { assertTrue(bytes.float.let { it.isFinite() && it in -1f..1f }) }
        }
        val fresh = AdaptivePcmRenderer(source, 1, 80, 8)
        fresh.render(buffer, 80, PlaybackRoute("a", SpeakerChannel.Mono, 100))
        assertTrue(buffer.take(8 * 4).all { it == 0.toByte() })
    }
}
