package com.example.connectallspeakers.audio.dsp

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Generates fifteen seconds of identical-channel manual calibration cues; never plays anything.
 * Three seconds of low-level warmup precede twelve 80-ms, 1-kHz pulses one second apart.
 * @return Owned 48-kHz stereo PCM, at most 10% peak, with click-reducing ramps.
 */
internal fun calibrationSignal(): DecodedAudio {
    val rate = 48_000
    val frames = rate * 15
    val samples = FloatArray(frames * 2)
    for (frame in 0 until frames) {
        val warmup = frame < rate * 3
        val local = if (warmup) frame else (frame - rate * 3) % rate
        val length = if (warmup) rate * 3 else rate * 80 / 1000
        val ramp = rate * 5 / 1000
        val value = if (local >= length) 0f else {
            val envelope = min(1.0, min(local.toDouble() / ramp, (length - 1 - local).toDouble() / ramp))
            (sin(2 * PI * (if (warmup) 440 else 1000) * frame / rate) * (if (warmup) .03 else .1) * envelope).toFloat()
        }
        samples[frame * 2] = value
        samples[frame * 2 + 1] = value
    }
    return DecodedAudio("Manual calibration cues", rate, 2, samples)
}
