package com.example.connectallspeakers.audio.dsp

/** Supported-source failure with a message safe to show without a local directory.
 * @param message Format, size or integrity explanation.
 */
internal open class AudioFileException(message: String) : IllegalArgumentException(message)

/** Local-file decoding seam. Implementations return owned PCM and never open playback devices. */
internal fun interface AudioFileDecoder {
    /** Decodes a user-selected source on the playback worker without starting audio.
     * @param path Native path to a local WAV or MP3 file.
     * @param cancelled Cooperative cancellation, checked while reading/decoding.
     * @return Worker-owned, interleaved normalized PCM with no remaining file handle.
     */
    fun decode(path: String, cancelled: () -> Boolean): DecodedAudio
}
