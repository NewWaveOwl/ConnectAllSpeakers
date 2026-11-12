package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.LivePcmBuffer
import com.example.connectallspeakers.audio.dsp.LiveRateControl
import com.example.connectallspeakers.audio.input.windows.LiveCaptureFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.*

/** Offline PCM/clock/format contracts; no COM or endpoint is constructed. */
class LivePcmBufferTest {
    @Test fun independentReadersRetainCubicBoundariesAcrossWrapWithoutGrowingHistory() {
        val buffer = LivePcmBuffer()
        val packet = FloatArray(960)
        repeat(500) { block ->
            repeat(480) { frame ->
                val value = ((block * 480 + frame) % 4096) / 4096f
                packet[frame * 2] = value; packet[frame * 2 + 1] = -value
            }
            buffer.append(packet, 480, block * 480L, block * 100_000L, (block + 1) * 100_000L, block == 0, false)
            val slow = maxOf(0, block * 480L - 1000)
            val fast = maxOf(0, block * 480L - 50)
            assertEquals((slow % 4096) / 4096f, buffer.sample(slow.toDouble(), 0))
            assertEquals(-((fast % 4096) / 4096f), buffer.sample(fast.toDouble(), 1))
            buffer.retainFrom(maxOf(0, slow - 1))
            assertTrue(buffer.retainedFrames < 2000)
        }
        assertFalse(buffer.canRead(0.0))
        assertEquals(240_000L, buffer.writtenFrames)
        buffer.clear()
        assertEquals(0, buffer.retainedFrames)
        assertFalse(buffer.canRead(0.0))
        buffer.append(packet, 480, 9_000_000, 9_000_000, 9_100_000, true, false)
        assertEquals(packet[0], buffer.sample(0.0, 0))
    }

    @Test fun slowReaderOverflowsRatherThanSilentlyOverwritingUnreadPcm() {
        val buffer = LivePcmBuffer()
        val packet = FloatArray(96_000)
        buffer.append(packet, 48_000, 0, 0, 0, false, false)
        buffer.append(packet, 48_000, 48_000, 10_000_000, 10_000_000, false, false)
        assertFailsWith<IllegalStateException> { buffer.append(FloatArray(2), 1, 96_000, 20_000_000, 20_000_000, false, false) }
        assertEquals(96_000, buffer.retainedFrames)
    }

    @Test fun invalidPacketsAreRejectedBeforeChangingAcceptedData() {
        fun reject(frames: Long = 480, qpc: Long = 100_000, now: Long = qpc, discontinuity: Boolean = false,
            error: Boolean = false, pcm: FloatArray = FloatArray(960)) {
            val buffer = LivePcmBuffer()
            buffer.append(FloatArray(960) { .2f }, 480, 0, 0, 0, false, false)
            assertFails { buffer.append(pcm, 480, frames, qpc, now, discontinuity, error) }
            assertEquals(480L, buffer.writtenFrames)
            assertEquals(.2f, buffer.sample(0.0, 0))
        }
        reject(frames = 481); reject(discontinuity = true); reject(error = true)
        reject(qpc = 1); reject(now = 3_000_001); reject(now = 0)
        reject(pcm = FloatArray(960) { if (it == 959) Float.NaN else 0f })
    }

    @Test fun normalizedInputAndStaleTimestampHaveExplicitPolicies() {
        val buffer = LivePcmBuffer()
        buffer.append(FloatArray(960) { 2f }, 480, 1000, 10_000, 110_000, true, false)
        assertEquals(960L, buffer.limitedSamples)
        assertEquals(1f, buffer.sample(.5, 0))
        assertEquals(480.0, buffer.positionAt(110_000))
        assertFailsWith<IllegalStateException> { buffer.positionAt(2_510_001) }
        buffer.clear()
        buffer.append(FloatArray(960) { (it / 2) / 480f }, 480, 0, 0, 0, false, false)
        assertEquals(100.25f / 480f, buffer.sample(100.25, 0), .000001f)
    }

    @Test fun twentyMinuteServoRunTracksIndependentInputAndOutputRatesWithinBounds() {
        for (skew in listOf(-700.0, -100.0, 100.0, 700.0)) {
            val control = LiveRateControl()
            var error = 0.0
            var correction = 0.0
            repeat(120_000) { tick ->
                error += (skew - correction) / 1_000_000 * .01
                val previous = correction
                correction = control.update(error, .01)
                assertTrue(abs(correction - previous) <= 1.000001)
                if (tick > 110_000) assertTrue(abs(error) < .00001, "skew=$skew error=$error")
            }
            assertEquals(skew, correction, .1)
        }
        assertFailsWith<IllegalStateException> { LiveRateControl().update(.051, .01) }
        assertFailsWith<IllegalArgumentException> { LiveRateControl().update(Double.NaN, .01) }
        val saturated = LiveRateControl()
        assertFailsWith<IllegalStateException> { repeat(1000) { saturated.update(.04, .01) } }
    }

    private fun format(bits: Int = 32, tag: Int = 3, extended: Boolean = false): ByteArray {
        val bytes = ByteBuffer.allocate(if (extended) 40 else 18).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putShort(if (extended) 0xfffe.toShort() else tag.toShort()).putShort(2).putInt(48000)
            .putInt(48000 * 2 * bits / 8).putShort((2 * bits / 8).toShort()).putShort(bits.toShort())
            .putShort(if (extended) 22 else 0)
        if (extended) bytes.putShort(bits.toShort()).putInt(3).putInt(tag).putShort(0).putShort(0x10)
            .put(byteArrayOf(0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71))
        return bytes.array()
    }

    @Test fun onlyVerifiedStereo48kPcm16AndFloatMixFormatsAreAccepted() {
        assertTrue(LiveCaptureFormat.parse(format()))
        assertTrue(LiveCaptureFormat.parse(format(extended = true)))
        assertFalse(LiveCaptureFormat.parse(format(16, 1)))
        assertFalse(LiveCaptureFormat.parse(format(16, 1, true)))
        for (bytes in listOf(format().copyOf(16), format(24, 1), format().also { it[2] = 1 },
            format().also { it[4] = 0 }, format().also { it[12] = 1 },
            format(extended = true).also { it[18] = 16 }, format(extended = true).also { it[16] = 24 },
            format(extended = true).also { it[39] = 0 })) {
            assertFailsWith<IllegalArgumentException> { LiveCaptureFormat.parse(bytes) }
        }
    }
}
