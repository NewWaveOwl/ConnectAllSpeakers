package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.PcmDelayLine
import com.example.connectallspeakers.audio.dsp.calibrationSignal
import com.example.connectallspeakers.model.audio.outputDelayFrames
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.*

/** Offline PCM checks: no audio endpoint is opened or played. */
class PcmDelayLineTest {
    @Test fun zeroDelayIsAnExactByteBypass() {
        val bytes = ByteArray(64) { (it * 7).toByte() }
        val expected = bytes.copyOf()
        PcmDelayLine(0, 2, 8).process(bytes, 8)
        assertContentEquals(expected, bytes)
    }

    @Test fun delaysAcrossWrapsAndUnevenBlocksPreserveEveryChannelAndTailAtSeveralRates() {
        for (rate in listOf(8_000, 44_100, 48_000, 192_000)) for (channels in 1..2) {
            val frames = 151
            val delay = outputDelayFrames(7, rate)
            val line = PcmDelayLine(delay, channels, 37)
            val input = FloatArray(frames * channels) { (it + 1) / 1024f }
            val actual = mutableListOf<Float>()
            var cursor = 0
            while (cursor < frames + delay) {
                val count = minOf(if (cursor % 2 == 0) 37 else 13, frames + delay - cursor)
                val bytes = ByteBuffer.allocate(count * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
                repeat(count * channels) { offset -> bytes.putFloat(input.getOrElse(cursor * channels + offset) { 0f }) }
                line.process(bytes.array(), count)
                bytes.rewind()
                repeat(count * channels) { actual += bytes.float }
                cursor += count
            }
            assertContentEquals(FloatArray(delay * channels) + input, actual.toFloatArray(), "$rate Hz/$channels channels")
        }
    }

    @Test fun maximumDelayIsBoundedAndInvalidBuffersAreRejected() {
        val line = PcmDelayLine(96_000, 2, 1)
        val bytes = ByteArray(8) { 1 }
        line.process(bytes, 1)
        assertTrue(bytes.all { it == 0.toByte() })
        assertFailsWith<IllegalArgumentException> { PcmDelayLine(-1, 2, 80) }
        assertFailsWith<IllegalArgumentException> { PcmDelayLine(96_001, 2, 80) }
        assertFailsWith<IllegalArgumentException> { PcmDelayLine(1, 3, 80) }
        assertFailsWith<IllegalArgumentException> { line.process(ByteArray(7), 1) }
        assertFailsWith<IllegalArgumentException> { line.process(ByteArray(16), 2) }
    }

    @Test fun calibrationSignalHasWarmupThenTwelveIdenticalStereoPulsesAndSilence() {
        val signal = calibrationSignal()
        assertEquals(48_000, signal.sampleRate)
        assertEquals(15_000L, signal.description.durationMillis)
        assertTrue(signal.samples.all { it.isFinite() && abs(it) <= .100001f })
        for (frame in 0 until signal.frames) assertEquals(signal.samples[frame * 2], signal.samples[frame * 2 + 1])
        assertTrue(signal.samples.take(48_000 * 2).any { abs(it) > .029f })
        for (second in 3..14) {
            val start = second * 48_000 * 2
            assertTrue(signal.samples.sliceArray(start until start + 7_680).any { abs(it) > .099f })
            assertTrue(signal.samples.sliceArray(start + 7_680 until start + 96_000).all { it == 0f })
        }
        assertEquals(0f, signal.samples.first())
        assertEquals(0f, signal.samples.last())
    }
}
