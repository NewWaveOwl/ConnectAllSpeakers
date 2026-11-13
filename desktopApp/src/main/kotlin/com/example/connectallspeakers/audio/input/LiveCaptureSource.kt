package com.example.connectallspeakers.audio.input

/** Worker-confined capture lifetime; test implementations supply generated PCM without Windows APIs. */
internal interface LiveCaptureSource : AutoCloseable {
    /** Starts capture only after every physical output is prepared, preventing startup backlog. */
    fun start()
    /** Copies all currently available bounded packets into the consumer, never waiting for another packet.
     * @param consumer Same-worker borrowed-buffer consumer; it must copy samples before returning.
     * @return Delivered frame count; zero means ordinary idle, not file EOF.
     */
    fun poll(consumer: LivePcmConsumer): Int
}

/** Callback that borrows one native packet's copied stereo float32 storage on the capture worker. */
internal fun interface LivePcmConsumer {
    /** @param samples Borrowed interleaved stereo data, valid only for this call.
     * @param frames Complete frames in samples, at 48000 Hz.
     * @param deviceFrame Native first-frame position, in source frames.
     * @param qpc100Nanos Correlated first-frame QPC time in 100 ns units.
     * @param now100Nanos Observation QPC time in the same units/domain.
     * @param discontinuity Windows reports a gap; allowed only as the first packet of a new epoch.
     * @param timestampError Windows could not establish reliable packet timing.
     */
    fun accept(samples: FloatArray, frames: Int, deviceFrame: Long, qpc100Nanos: Long,
        now100Nanos: Long, discontinuity: Boolean, timestampError: Boolean)
}
