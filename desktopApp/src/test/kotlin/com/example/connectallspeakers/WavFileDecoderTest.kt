package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.WavFileDecoder
import com.example.connectallspeakers.audio.dsp.WavFileException
import kotlinx.coroutines.CancellationException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class WavFileDecoderTest {
    @get:Rule val folder = TemporaryFolder()
    private val decoder = WavFileDecoder()
    private fun le(size: Int, write: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(write).array()
    private fun chunk(id: String, data: ByteArray): ByteArray = id.toByteArray() + le(4) { putInt(data.size) } +
        data + if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
    private fun format(bits: Int = 16, tag: Int = 1, channels: Int = 1, rate: Int = 8_000): ByteArray = le(16) {
        putShort(tag.toShort()); putShort(channels.toShort()); putInt(rate)
        putInt(rate * channels * bits / 8); putShort((channels * bits / 8).toShort()); putShort(bits.toShort())
    }
    private fun wave(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { write("WAVE".toByteArray()); chunks.forEach { write(it) } }.toByteArray()
        return "RIFF".toByteArray() + le(4) { putInt(body.size) } + body
    }
    private fun decode(bytes: ByteArray, cancelled: () -> Boolean = { false }) =
        folder.newFile().apply { writeBytes(bytes) }.let { decoder.decode(it.path, cancelled) }

    @Test fun signedPcm16IsDecodedAndUnknownOddChunksAreSkipped() {
        val result = decode(wave(chunk("JUNK", byteArrayOf(7)), chunk("fmt ", format()),
            chunk("data", le(6) { putShort(Short.MIN_VALUE); putShort(0); putShort(16384) })))
        assertContentEquals(floatArrayOf(-1f, 0f, .5f), result.samples)
        assertEquals(8_000, result.sampleRate)
    }
    @Test fun pcm24AndPcm32PreserveSignAndScale() {
        val pcm24 = decode(wave(chunk("fmt ", format(24)), chunk("data", byteArrayOf(0, 0, 0x80.toByte(), 0, 0, 0x40))))
        assertContentEquals(floatArrayOf(-1f, .5f), pcm24.samples)
        val pcm32 = decode(wave(chunk("fmt ", format(32)), chunk("data", le(8) { putInt(Int.MIN_VALUE); putInt(0x40000000) })))
        assertContentEquals(floatArrayOf(-1f, .5f), pcm32.samples)
    }
    @Test fun floatInputIsClampedAndNonFiniteSamplesAreRejected() {
        val result = decode(wave(chunk("fmt ", format(32, 3)), chunk("data", le(8) { putFloat(-2f); putFloat(.75f) })))
        assertContentEquals(floatArrayOf(-1f, .75f), result.samples)
        assertFailsWith<WavFileException> { decode(wave(chunk("fmt ", format(32, 3)),
            chunk("data", le(4) { putFloat(Float.NaN) }))) }
    }
    @Test fun stereoDataBeforeFormatIsAccepted() {
        val result = decode(wave(chunk("data", le(8) { putShort(100); putShort(200); putShort(300); putShort(400) }),
            chunk("fmt ", format(channels = 2, rate = 48_000))))
        assertEquals(2, result.frames)
        assertEquals(2, result.channels)
    }
    @Test fun extensiblePcmIsAcceptedAndUnknownSubformatRejected() {
        val extension = le(24) {
            putShort(22); putShort(16); putInt(4)
            putInt(1); putShort(0); putShort(0x10)
            put(byteArrayOf(0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71))
        }
        val fmt = format(tag = 0xFFFE) + extension
        assertEquals(1, decode(wave(chunk("fmt ", fmt), chunk("data", byteArrayOf(0, 0)))).frames)
        fmt[24] = 6
        assertFailsWith<WavFileException> { decode(wave(chunk("fmt ", fmt), chunk("data", byteArrayOf(0, 0)))) }
    }
    @Test fun truncationDuplicateChunksMissingDataAndMisalignedFramesFail() {
        val valid = wave(chunk("fmt ", format()), chunk("data", byteArrayOf(0, 0)))
        assertFailsWith<WavFileException> { decode(valid.copyOf(valid.size - 1)) }
        assertFailsWith<WavFileException> { decode(wave(chunk("fmt ", format()), chunk("fmt ", format()), chunk("data", byteArrayOf(0, 0)))) }
        assertFailsWith<WavFileException> { decode(wave(chunk("fmt ", format()))) }
        assertFailsWith<WavFileException> { decode(wave(chunk("fmt ", format()), chunk("data", byteArrayOf(0)))) }
    }
    @Test fun unsupportedCodecChannelCountRateAndInvalidByteRateFail() {
        listOf(format(tag = 6), format(channels = 6), format(rate = 7_999), format(bits = 8)).forEach { fmt ->
            assertFailsWith<WavFileException> { decode(wave(chunk("fmt ", fmt), chunk("data", ByteArray(12)))) }
        }
        val fmt = format().apply { this[8] = 0 }
        assertFailsWith<WavFileException> { decode(wave(chunk("fmt ", fmt), chunk("data", byteArrayOf(0, 0)))) }
    }
    @Test fun oversizedDataIsRejectedBeforeAllocation() {
        val file = folder.newFile()
        val header = wave(chunk("fmt ", format()), chunk("data", byteArrayOf(0, 0)))
        java.io.RandomAccessFile(file, "rw").use {
            val size = 64 * 1024 * 1024 + 2
            ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).putInt(4, size + 36).putInt(40, size)
            it.write(header); it.setLength(44L + size)
        }
        assertFailsWith<WavFileException> { decoder.decode(file.path) { false } }
    }
    @Test fun loadCancellationClosesFileAndReadFailureDoesNotExposePath() {
        assertFailsWith<CancellationException> {
            decode(wave(chunk("fmt ", format()), chunk("data", byteArrayOf(0, 0)))) { true }
        }
        val failure = assertFailsWith<WavFileException> { decoder.decode(folder.root.resolve("private-missing.wav").path) { false } }
        assertFalse(failure.message.orEmpty().contains(folder.root.path))
    }
    @Test fun partialChunkHeadersAndTruncatedFormatExtensionsAreRejected() {
        val withTail = wave(chunk("fmt ", format()), chunk("data", byteArrayOf(0, 0))) + byteArrayOf(1, 2)
        ByteBuffer.wrap(withTail).order(ByteOrder.LITTLE_ENDIAN).putInt(4, withTail.size - 8)
        assertFailsWith<WavFileException> { decode(withTail) }
        assertFailsWith<WavFileException> { decode(wave(chunk("fmt ", format() + le(2) { putShort(22) }),
            chunk("data", byteArrayOf(0, 0)))) }
    }
}
