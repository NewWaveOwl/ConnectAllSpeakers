package com.example.connectallspeakers.audio.wasapi

/** A thread-confined native lifetime; fakes exercise orchestration without devices. */
internal interface PlaybackDeviceSession : AutoCloseable {
    /** @param endpointId Exact render ID, with no default fallback. @param sampleRate Source frames/second.
     * @return Owned initialized, stopped endpoint. The caller must close it before closing this session.
     */
    fun openEndpoint(endpointId: String, sampleRate: Int): PlaybackEndpoint
}

/** One stopped or running float32 render stream, used only on its owning worker thread. */
internal interface PlaybackEndpoint : AutoCloseable {
    /** Physical output channels; initial support is mono/stereo only. */
    val channels: Int
    /** Allocated native buffer capacity in source-rate frames. */
    val capacityFrames: Int
    /** @return Queued source-rate frames not yet consumed by the engine. */
    fun queuedFrames(): Int
    /** @param samples Borrowed interleaved little-endian float32 bytes. @param frames Complete frames to queue. */
    fun submit(samples: ByteArray, frames: Int)
    /** Starts or resumes this explicitly selected endpoint. */
    fun start()
    /** Pauses this stream, retaining buffered frames. */
    fun stop()
    /** Discards queued frames while stopped. */
    fun reset()
}
