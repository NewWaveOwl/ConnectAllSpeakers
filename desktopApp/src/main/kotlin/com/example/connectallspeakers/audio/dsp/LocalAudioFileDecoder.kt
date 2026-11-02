package com.example.connectallspeakers.audio.dsp

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.RandomAccessFile

/** Dispatches by file signature rather than extension; no installed codec or external player is used. */
internal class LocalAudioFileDecoder : AudioFileDecoder {
    /** Selects the bounded WAV or MP3 decoder from a copied four-byte signature.
     * @param path User-selected local source, regardless of filename capitalization/extension.
     * @param cancelled Cooperative stale-load/shutdown check.
     * @return Worker-owned decoded samples; reading a file never starts playback.
     */
    override fun decode(path: String, cancelled: () -> Boolean): DecodedAudio {
        if (cancelled()) throw CancellationException("Audio load cancelled")
        val signature = try {
            RandomAccessFile(path, "r").use { input ->
                if (input.length() < 4) throw AudioFileException("Choose a complete WAV or MP3 file.")
                input.readInt()
            }
        } catch (_: IOException) { throw AudioFileException("The selected audio file could not be read.")
        } catch (_: SecurityException) { throw AudioFileException("The selected audio file is not accessible.") }
        val decoder = when {
            signature == 0x52494646 -> WavFileDecoder()
            signature ushr 8 == 0x494433 || signature ushr 21 == 0x7FF -> Mp3FileDecoder()
            else -> throw AudioFileException("Choose a supported WAV or MP3 file.")
        }
        return decoder.decode(path, cancelled)
    }
}
