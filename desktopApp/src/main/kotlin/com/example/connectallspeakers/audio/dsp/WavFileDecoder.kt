package com.example.connectallspeakers.audio.dsp

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Explicit supported-source failure, safe to show without exposing a local directory.
 * @param message Supported-format or file-integrity explanation.
 */
internal class WavFileException(message: String) : IllegalArgumentException(message)

/** File decoding seam for deterministic controller tests; no audio-device methods exist here. */
internal fun interface AudioFileDecoder {
    /** @param path User-selected native path. @param cancelled Cooperative load cancellation. @return Owned PCM. */
    fun decode(path: String, cancelled: () -> Boolean): DecodedAudio
}

/** Bounded RIFF/WAVE decoder for mono/stereo PCM16/24/32 and IEEE float32.
 * RIFX, RF64, compressed codecs, malformed chunks and non-finite float samples are rejected.
 */
internal class WavFileDecoder : AudioFileDecoder {
    /** Reads at most 64 MiB of sample data and retains a single decoded sample array.
     * @param path User-selected WAV file.
     * @param cancelled Checked during parsing/decoding; stale loads throw CancellationException.
     * @return Owned, normalized samples, with no live file handle.
     */
    override fun decode(path: String, cancelled: () -> Boolean): DecodedAudio {
        try {
            RandomAccessFile(path, "r").use { file ->
                if (file.length() < 12 || file.readInt() != 0x52494646) fail("Choose a RIFF/WAVE file, not a compressed audio file.")
                val riffEnd = uint(file) + 8
                if (file.readInt() != 0x57415645 || riffEnd > file.length() || riffEnd < 12) fail("The WAV header is incomplete.")
                var format: Format? = null
                var dataOffset = -1L
                var dataSize = 0L
                var chunks = 0
                while (file.filePointer + 8 <= riffEnd) {
                    if (cancelled()) throw CancellationException("Audio load cancelled")
                    if (++chunks > 10_000) fail("The WAV contains too many chunks.")
                    val id = file.readInt()
                    val size = uint(file)
                    val start = file.filePointer
                    if (size > riffEnd - start) fail("The WAV contains a truncated chunk.")
                    when (id) {
                        0x666D7420 -> { if (format != null) fail("The WAV has duplicate format chunks."); format = readFormat(file, size) }
                        0x64617461 -> { if (dataOffset >= 0) fail("The WAV has multiple data chunks."); dataOffset = start; dataSize = size }
                    }
                    val next = start + size + (size and 1)
                    if (next > riffEnd) fail("The WAV chunk padding is incomplete.")
                    file.seek(next)
                }
                if (file.filePointer != riffEnd) fail("The WAV contains an incomplete chunk header.")
                val fmt = format ?: fail("The WAV has no format chunk.")
                if (dataOffset < 0 || dataSize == 0L) fail("The WAV contains no audio samples.")
                if (dataSize > MAX_DATA_BYTES || dataSize % fmt.blockAlign != 0L) fail("WAV sample data must be frame-aligned and at most 64 MiB.")
                val count = (dataSize / (fmt.bits / 8)).toInt()
                val result = FloatArray(count)
                file.seek(dataOffset)
                val block = ByteArray(8192 * fmt.blockAlign)
                var sampleIndex = 0
                var remaining = dataSize
                while (remaining > 0) {
                    if (cancelled()) throw CancellationException("Audio load cancelled")
                    val bytes = minOf(remaining, block.size.toLong()).toInt()
                    file.readFully(block, 0, bytes)
                    var offset = 0
                    while (offset < bytes) {
                        val value = when {
                            fmt.floating -> Float.fromBits(int32(block, offset)).also { if (!it.isFinite()) fail("The WAV contains non-finite float samples.") }
                            fmt.bits == 16 -> ((block[offset].toInt() and 255) or (block[offset + 1].toInt() shl 8)).toShort() / 32768f
                            fmt.bits == 24 -> (((block[offset].toInt() and 255) or ((block[offset + 1].toInt() and 255) shl 8) or
                                (block[offset + 2].toInt() shl 16))) / 8388608f
                            else -> (int32(block, offset).toDouble() / 2147483648.0).toFloat()
                        }
                        result[sampleIndex++] = value.coerceIn(-1f, 1f)
                        offset += fmt.bits / 8
                    }
                    remaining -= bytes
                }
                return DecodedAudio(File(path).name, fmt.rate, fmt.channels, result)
            }
        } catch (cancelledLoad: CancellationException) { throw cancelledLoad
        } catch (_: IOException) { fail("The selected WAV file could not be read completely.")
        } catch (_: SecurityException) { fail("The selected WAV file is not accessible.") }
    }

    /** Validates a bounded format chunk, including extensible PCM/float subformats.
     * @param file Borrowed reader at the beginning of fmt data.
     * @param size Format-chunk byte count.
     * @return Supported PCM format.
     */
    private fun readFormat(file: RandomAccessFile, size: Long): Format {
        if (size < 16) fail("The WAV format chunk is incomplete.")
        var tag = ushort(file)
        val channels = ushort(file)
        val rate = uint(file)
        val bytesPerSecond = uint(file)
        val align = ushort(file)
        val bits = ushort(file)
        val extensionSize = if (size > 16) {
            if (size < 18) fail("The WAV format extension is incomplete.")
            ushort(file).also { if (it > size - 18) fail("The WAV format extension is truncated.") }
        } else 0
        if (tag == 0xFFFE) {
            if (size < 40 || extensionSize < 22) fail("The extensible WAV format is incomplete.")
            val validBits = ushort(file)
            val mask = uint(file)
            val subtype = ByteArray(16).also { file.readFully(it) }
            if (validBits !in 1..bits || !subtype.copyOfRange(4, 16).contentEquals(SUBTYPE_TAIL)) fail("Unsupported WAV subformat.")
            tag = int32(subtype, 0)
            if (mask != 0L && mask != (if (channels == 1) 4L else 3L)) fail("Only standard mono/stereo WAV channel layouts are supported.")
            if (tag == 3 && validBits != 32) fail("Float WAV samples must have 32 valid bits.")
        }
        if (channels !in 1..2 || rate !in 8_000..192_000) fail("Use a mono/stereo WAV at 8–192 kHz.")
        if ((tag == 1 && bits !in setOf(16, 24, 32)) || (tag == 3 && bits != 32) || tag !in setOf(1, 3)) {
            fail("Supported WAV encodings: PCM16/24/32 or IEEE float32.")
        }
        if (align != channels * bits / 8 || bytesPerSecond != rate * align) fail("The WAV frame size or byte rate is invalid.")
        return Format(rate.toInt(), channels, bits, align, tag == 3)
    }

    /** @param file Borrowed reader. @return Unsigned little-endian 16-bit value. */
    private fun ushort(file: RandomAccessFile): Int = java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xFFFF
    /** @param file Borrowed reader. @return Unsigned little-endian 32-bit value as Long. */
    private fun uint(file: RandomAccessFile): Long = java.lang.Integer.reverseBytes(file.readInt()).toLong() and 0xFFFFFFFFL
    /** @param bytes PCM bytes. @param offset First byte. @return Signed little-endian 32-bit value. */
    private fun int32(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 255) or
        ((bytes[offset + 1].toInt() and 255) shl 8) or ((bytes[offset + 2].toInt() and 255) shl 16) or (bytes[offset + 3].toInt() shl 24)
    /** @param message Readable source failure. @return Never returns. */
    private fun fail(message: String): Nothing = throw WavFileException(message)

    private data class Format(val rate: Int, val channels: Int, val bits: Int, val blockAlign: Int, val floating: Boolean)

    companion object {
        private const val MAX_DATA_BYTES = 64L * 1024 * 1024
        private val SUBTYPE_TAIL = byteArrayOf(0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)
    }
}
