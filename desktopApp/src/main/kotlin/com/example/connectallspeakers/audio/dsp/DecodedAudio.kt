package com.example.connectallspeakers.audio.dsp

import com.example.connectallspeakers.model.audio.PlaybackSource
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Worker-owned decoded samples. The array never enters shared state or crosses to the UI.
 * @property name Source file name or explicit tone label.
 * @property sampleRate Frames per second.
 * @property channels One or two interleaved source channels.
 * @property samples Owned normalized finite samples, clamped to -1..1 by the decoder.
 */
internal class DecodedAudio(val name: String, val sampleRate: Int, val channels: Int, val samples: FloatArray) {
    init {
        require(sampleRate in 8_000..192_000 && channels in 1..2)
        require(samples.isNotEmpty() && samples.size % channels == 0)
    }
    /** Number of source frames, independent of channel count. */
    val frames: Int get() = samples.size / channels
    /** Portable metadata copied into playback state. */
    val description: PlaybackSource get() = PlaybackSource(name, frames * 1000L / sampleRate, sampleRate, channels)
}

/** Creates a one-second stereo test tone at 10% peak amplitude, with 10 ms click-reducing ramps.
 * This only creates samples; it never opens or plays an audio device.
 * @return Worker-owned 440 Hz test source; both source channels contain the same signal.
 */
internal fun speakerTestTone(): DecodedAudio {
    val rate = 48_000
    val data = FloatArray(rate * 2)
    val rampFrames = rate / 100
    for (frame in 0 until rate) {
        val envelope = min(1.0, min(frame.toDouble() / rampFrames, (rate - 1 - frame).toDouble() / rampFrames))
        val sample = (sin(2.0 * PI * 440.0 * frame / rate) * .1 * envelope).toFloat()
        data[frame * 2] = sample; data[frame * 2 + 1] = sample
    }
    return DecodedAudio("Speaker test tone", rate, 2, data)
}
