package com.example.connectallspeakers.audio.windows

import com.example.connectallspeakers.model.audio.AudioOutputDevice

/** Thread-affine native seam. Every method is called on the repository's single COM thread. */
internal interface WindowsAudioBackend : AutoCloseable {
    /** Initializes COM and registers notifications, cleaning partial initialization on failure.
     * @param onChanged Nonblocking queue signal; never enumerates or updates UI in the callback.
     */
    fun open(onChanged: () -> Unit)

    /** Enumerates only active render endpoints.
     * @return Current playback endpoints; empty is a successful result.
     * @throws Exception If the list cannot be established reliably.
     */
    fun enumerate(): List<AudioOutputDevice>

    /** Unregisters notifications, releases COM objects and balances COM initialization once. */
    override fun close()
}
