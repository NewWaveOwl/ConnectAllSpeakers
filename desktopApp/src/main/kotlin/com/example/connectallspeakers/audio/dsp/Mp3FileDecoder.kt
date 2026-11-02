package com.example.connectallspeakers.audio.dsp

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** Pure-Java MP3 decoding into the same PCM used by WAV/WASAPI; never uses JLayer's player APIs. */
internal class Mp3FileDecoder : AudioFileDecoder {
    /** Decodes checked MPEG-1/2/2.5 Layer III mono/stereo CBR/VBR frames.
     * @param path User-selected local MP3; compressed and decoded sizes are bounded before allocation.
     * @param cancelled Checked at each frame and underlying read, including library synchronization.
     * @return Owned normalized PCM. Encoder delay/padding is retained; no gapless trimming is claimed.
     */
    override fun decode(path: String, cancelled: () -> Boolean): DecodedAudio {
        try {
            RandomAccessFile(path, "r").use { input ->
                val layout = Mp3FrameLayout.inspect(input, cancelled)
                input.seek(layout.start)
                val decoded = FloatArray(layout.sampleCount)
                val bitstream = Bitstream(FrameInput(input, layout.end, cancelled))
                try {
                    val decoder = Decoder()
                    var offset = 0
                    repeat(layout.frameCount) {
                        checkCancelled(cancelled)
                        val header = bitstream.readFrame() ?: throw AudioFileException("The MP3 could not be decoded completely.")
                        try {
                            check(header.layer() == 3 && header.frequency() == layout.sampleRate) { "Unexpected MP3 frame format." }
                            val block = decoder.decodeFrame(header, bitstream) as? SampleBuffer
                                ?: throw AudioFileException("Unsupported MP3 decoder output.")
                            if (block.channelCount != layout.channels || block.sampleFrequency != layout.sampleRate ||
                                block.bufferLength != layout.samplesPerFrame * layout.channels) {
                                throw AudioFileException("The MP3 changed format while decoding.")
                            }
                            for (index in 0 until block.bufferLength) decoded[offset++] = block.buffer[index] / 32768f
                        } finally { bitstream.closeFrame() }
                    }
                    checkCancelled(cancelled)
                    if (bitstream.readFrame() != null) throw AudioFileException("The MP3 changed while it was being read.")
                    return DecodedAudio(File(path).name, layout.sampleRate, layout.channels, decoded)
                } finally { bitstream.close() }
            }
        } catch (cancelledLoad: CancellationException) { throw cancelledLoad
        } catch (format: AudioFileException) { throw format
        } catch (_: IOException) { throw AudioFileException("The selected MP3 could not be read completely.")
        } catch (_: SecurityException) { throw AudioFileException("The selected MP3 is not accessible.")
        } catch (_: Exception) { throw AudioFileException("The MP3 is damaged or uses an unsupported encoding.") }
    }

    /** Reader limited to validated audio bytes, excluding metadata and checking cancellation.
     * @param input Borrowed seekable file, closed by the outer decoder owner.
     * @param end Exclusive audio byte bound. @param cancelled Cooperative cancellation probe.
     */
    private class FrameInput(private val input: RandomAccessFile, private val end: Long,
        private val cancelled: () -> Boolean) : InputStream() {
        /** @return One unsigned byte or -1 at the validated audio boundary. */
        override fun read(): Int {
            checkCancelled(cancelled)
            return if (input.filePointer >= end) -1 else input.read()
        }
        /** @param buffer Destination. @param offset First destination byte. @param length Requested bytes.
         * @return Bounded byte count or -1 at audio EOF; zero-length reads return zero.
         */
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkCancelled(cancelled)
            if (length == 0) return 0
            val remaining = end - input.filePointer
            return if (remaining <= 0) -1 else input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        }
    }

    companion object {
        /** @param cancelled Cooperative cancellation probe; never converted into a format failure. */
        private fun checkCancelled(cancelled: () -> Boolean) { if (cancelled()) throw CancellationException("MP3 load cancelled") }
    }
}
