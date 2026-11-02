package com.example.connectallspeakers.audio.dsp

import kotlinx.coroutines.CancellationException
import java.io.RandomAccessFile

/** Validated contiguous MPEG Layer III range, excluding leading ID3v2 and trailing ID3v1/v2 tags.
 * @property start First MPEG frame offset in bytes.
 * @property end Exclusive audio byte bound.
 * @property sampleRate Frames/second, constant throughout the file.
 * @property channels One or two interleaved channels, constant throughout the file.
 * @property frameCount Number of complete MPEG frames, including encoder information frames.
 * @property samplesPerFrame PCM frames per MPEG frame (1152 or 576).
 */
internal data class Mp3FrameLayout(val start: Long, val end: Long, val sampleRate: Int, val channels: Int,
    val frameCount: Int, val samplesPerFrame: Int) {
    /** Exact upper bound for the decoded interleaved sample array. */
    val sampleCount: Int get() = frameCount * samplesPerFrame * channels

    companion object {
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
        private const val MAX_PCM_SAMPLES = 64L * 1024 * 1024 / 2 // 64 MiB of decoded PCM16.
        private val MPEG1_BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val MPEG2_BITRATES = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        private val BASE_RATES = intArrayOf(44_100, 48_000, 32_000)

        /** Scans headers and frame bounds without decoding payload or allocating metadata tags.
         * Rejects truncation, free-format bitrates, other MPEG layers and format-changing concatenation.
         * @param input Borrowed seekable reader; caller retains ownership and may reposition it.
         * @param cancelled Checked for each tag/frame; throws CancellationException when superseded.
         * @return Bounded layout permitting one exact PCM allocation before decoding.
         */
        fun inspect(input: RandomAccessFile, cancelled: () -> Boolean): Mp3FrameLayout {
            val length = input.length()
            if (length !in 4..MAX_FILE_BYTES) fail("MP3 files must be nonempty and at most 64 MiB.")
            var end = length
            if (end >= 128 && signature(input, end - 128, 0x544147)) end -= 128 // ID3v1
            var start = 0L
            var tags = 0
            while (end - start >= 3 && signature(input, start, 0x494433)) {
                checkCancelled(cancelled)
                if (++tags > 16) fail("The MP3 contains too many metadata tags.")
                start = tagEnd(input, start, end)
            }
            var position = start
            var frameCount = 0
            var expectedRate = 0
            var expectedChannels = 0
            var expectedSamples = 0
            while (position < end) {
                checkCancelled(cancelled)
                if (end - position >= 3 && signature(input, position, 0x494433)) {
                    if (tagEnd(input, position, end) != end) fail("MP3 metadata interrupts the audio frames.")
                    end = position
                    break
                }
                if (end - position < 4) fail("The MP3 ends with a truncated frame header.")
                input.seek(position)
                val header = input.readInt()
                val version = (header ushr 19) and 3
                val layer = (header ushr 17) and 3
                val bitrateIndex = (header ushr 12) and 15
                val rateIndex = (header ushr 10) and 3
                if (header ushr 21 != 0x7FF || version == 1 || layer != 1 || bitrateIndex !in 1..14 || rateIndex == 3 || header and 3 == 2) {
                    fail("Use a complete MPEG Layer III MP3 with a standard bitrate.")
                }
                val rate = BASE_RATES[rateIndex] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
                val channels = if ((header ushr 6) and 3 == 3) 1 else 2
                val samples = if (version == 3) 1152 else 576
                if (frameCount == 0) { expectedRate = rate; expectedChannels = channels; expectedSamples = samples }
                if (rate != expectedRate || channels != expectedChannels || samples != expectedSamples) {
                    fail("MP3 sample rate and channel count must stay constant throughout the file.")
                }
                val bitrate = (if (version == 3) MPEG1_BITRATES else MPEG2_BITRATES)[bitrateIndex]
                val bytes = (if (version == 3) 144_000 else 72_000) * bitrate / rate + ((header ushr 9) and 1)
                if (bytes > end - position) fail("The MP3 contains a truncated audio frame.")
                frameCount++
                if (frameCount.toLong() * samples * channels > MAX_PCM_SAMPLES) {
                    fail("The MP3 exceeds the 64 MiB decoded PCM16 limit. Choose a shorter sound-check file.")
                }
                position += bytes
            }
            if (frameCount == 0) fail("The MP3 contains no complete audio frames.")
            return Mp3FrameLayout(start, end, expectedRate, expectedChannels, frameCount, expectedSamples)
        }

        /** Skips ID3v2 metadata by checked size; tags never reach JLayer's allocating ID3 parser.
         * @param input Borrowed reader. @param position Tag header offset. @param end Exclusive file bound.
         * @return Byte immediately following header, tag body and optional v2.4 footer.
         */
        private fun tagEnd(input: RandomAccessFile, position: Long, end: Long): Long {
            if (end - position < 10) fail("The MP3 metadata header is incomplete.")
            input.seek(position + 3)
            val version = input.readUnsignedByte()
            val revision = input.readUnsignedByte()
            val flags = input.readUnsignedByte()
            if (version !in 2..4 || revision == 255 || flags and when (version) { 2 -> 63; 3 -> 31; else -> 15 } != 0) {
                fail("Unsupported MP3 metadata header.")
            }
            var size = 0L
            repeat(4) {
                val part = input.readUnsignedByte()
                if (part and 128 != 0) fail("The MP3 metadata size is invalid.")
                size = (size shl 7) or part.toLong()
            }
            val footer = if (version == 4 && flags and 16 != 0) 10 else 0
            val next = position + 10 + size + footer
            if (next > end) fail("The MP3 metadata is truncated.")
            if (footer != 0 && !signature(input, next - 10, 0x334449)) fail("The MP3 metadata footer is invalid.")
            return next
        }

        /** @param input Borrowed reader. @param position Three-byte offset. @param expected ASCII signature.
         * @return Whether the copied bytes match the requested metadata marker.
         */
        private fun signature(input: RandomAccessFile, position: Long, expected: Int): Boolean {
            input.seek(position)
            return (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte() == expected
        }
        /** @param cancelled Cooperative cancellation probe. */
        private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) throw CancellationException("MP3 load cancelled") }
        /** @param message Safe format error. @return Never returns. */
        private fun fail(message: String): Nothing = throw AudioFileException(message)
    }
}
