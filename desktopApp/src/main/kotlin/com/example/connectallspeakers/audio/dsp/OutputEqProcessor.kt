package com.example.connectallspeakers.audio.dsp

import com.example.connectallspeakers.model.audio.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Worker-confined three-bell EQ with two fixed banks and a latest-target coefficient slot.
 * Routing/resampling and app gain precede this stage; manual delay follows it.
 * Uses normalized RBJ peaking coefficients (W3C Audio EQ Cookbook) and double transposed-direct-form-II state.
 * Headroom is minus the sum of positive gains. A final finite/-1..1 guard counts limited frames.
 * Live edits crossfade complete stable banks over 100 ms; coefficients are never interpolated.
 * No buffers, collections, locks, I/O or UI work are created by per-frame processing.
 * @param sampleRate Actual source/output-processing frames per second, 8000..192000.
 * @param initial Valid initial parameters; each new session/rate gets fresh history.
 */
internal class OutputEqProcessor(private val sampleRate: Int, initial: SpeakerEq = SpeakerEq()) {
    private val banks = arrayOf(Bank(), Bank())
    private val prepared = DoubleArray(EQ_BAND_COUNT * 5)
    private val candidateCoefficients = DoubleArray(EQ_BAND_COUNT * 5)
    private val crossfadeFrames = sampleRate / 10
    private val maximumTailFrames = sampleRate / 2
    private val tailFadeFrames = sampleRate / 100
    private var currentIndex = 0
    private var current = initial
    private var target = initial
    private var pending = initial
    private var fadeFrame = crossfadeFrames
    private var usedFilters = initial.isActive
    private var drainedFrames = 0
    private var draining = false
    private var failed = false
    private var limited = 0L
    private var problem: String? = null

    init {
        initial.validateForSampleRate(sampleRate)
        prepare(initial)
        banks[0].configure(initial, prepared)
    }

    /** Bounded extra zero-input frames after EOF; a neutral/bypassed-only session has no added tail. */
    val tailFrames: Int get() = if (usedFilters) maximumTailFrames else 0
    /** Remaining EQ drain, excluding the separate manual-delay FIFO. */
    val remainingTailFrames: Int get() = (tailFrames - drainedFrames).coerceAtLeast(0)

    /** Validates/calculates at a block boundary, retaining the last safe target on failure.
     * Rapid requests coalesce while the current crossfade completes; tail-time edits apply next session.
     * @param settings Latest immutable per-output request.
     * @return False for a rejected source-rate/coefficient update, without altering active filters.
     */
    fun request(settings: SpeakerEq): Boolean {
        if (draining) return true
        if (failed && settings == pending) return false
        if (settings == pending && !failed) { problem = null; return true }
        try {
            settings.validateForSampleRate(sampleRate)
            prepare(settings)
        } catch (invalid: IllegalArgumentException) {
            problem = invalid.message ?: "Invalid EQ update; previous safe EQ retained."
            return false
        }
        pending = settings
        problem = null
        if (failed) {
            banks.forEach { it.reset() }
            current = SpeakerEq()
            banks[currentIndex].configure(current, prepared) // Identity does not use the prepared coefficients.
            failed = false
            fadeFrame = crossfadeFrames
        }
        usedFilters = usedFilters || settings.isActive
        if (fadeFrame == crossfadeFrames) beginTransition()
        return true
    }

    /** Processes one routed/gained sample; physical output channels receive the same result.
     * @param input Finite normalized input; non-finite input becomes silence and increments the guard count.
     * @param endOfSource True only for zero-input EOF/manual-delay drain, never for an ordinary silent sample.
     * @return Finite float in -1..1. Stable bypass/neutral is bit-exact for valid input.
     */
    fun process(input: Float, endOfSource: Boolean = false): Float {
        if (endOfSource) {
            draining = true
            if (remainingTailFrames == 0) return 0f
            drainedFrames++
        }
        val clean = if (input.isFinite()) input.toDouble() else { limited++; 0.0 }
        if (failed) return clean.coerceIn(-1.0, 1.0).toFloat()
        var value = banks[currentIndex].process(clean)
        if (fadeFrame < crossfadeFrames) {
            val next = banks[1 - currentIndex].process(clean)
            val fraction = (fadeFrame + 1).toDouble() / crossfadeFrames
            val mix = fraction * fraction * (3 - 2 * fraction)
            if (next != value) value += (next - value) * mix
            fadeFrame++
            if (fadeFrame == crossfadeFrames) {
                currentIndex = 1 - currentIndex
                current = target
                if (pending != current && !draining) beginTransition()
            }
        }
        if (!value.isFinite()) {
            banks.forEach { it.reset() }
            failed = true
            problem = "EQ became non-finite; this output is safely bypassed until a valid update/restart."
            limited++
            return clean.coerceIn(-1.0, 1.0).toFloat()
        }
        if (endOfSource && remainingTailFrames < tailFadeFrames) value *= remainingTailFrames.toDouble() / tailFadeFrames
        if (value < -1 || value > 1) limited++
        return value.coerceIn(-1.0, 1.0).toFloat()
    }

    /** Applies EQ once per frame to the already duplicated one/two-channel routed byte buffer.
     * @param bytes Borrowed reusable little-endian float32 buffer.
     * @param frames Complete output frames, bounded by the owning native queue.
     * @param channels Physical channels, one or two; both already contain the same routed input.
     * @param sourceFrames Input frames in this block; subsequent frames are zero-input EOF drain.
     */
    fun process(bytes: ByteArray, frames: Int, channels: Int, sourceFrames: Int) {
        require(channels in 1..2 && sourceFrames in 0..frames && bytes.size >= frames * channels * 4)
        var offset = 0
        repeat(frames) { frame ->
            val bits = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) or
                ((bytes[offset + 2].toInt() and 255) shl 16) or (bytes[offset + 3].toInt() shl 24)
            val output = process(Float.fromBits(bits), frame >= sourceFrames).toRawBits()
            repeat(channels) {
                bytes[offset++] = output.toByte(); bytes[offset++] = (output ushr 8).toByte()
                bytes[offset++] = (output ushr 16).toByte(); bytes[offset++] = (output ushr 24).toByte()
            }
        }
    }

    /** @return Copied guard/transition state; never exposes mutable filter history. */
    fun snapshot(): OutputEqTiming = OutputEqTiming(if (failed) 0.0 else target.headroomDb, limited,
        !failed && fadeFrame < crossfadeFrames, problem ?: if (draining && usedFilters) "EQ tail draining; new edits apply on the next Play." else null)

    /** Loads the already validated latest-target slot into the unused bank with fresh zero history. */
    private fun beginTransition() {
        if (pending == current) return
        target = pending
        banks[1 - currentIndex].configure(target, prepared)
        fadeFrame = 0
    }

    /** Calculates the latest-target slot without mutating either live bank.
     * @param settings Validated parameters. @throws IllegalArgumentException For non-finite/unstable coefficients.
     */
    private fun prepare(settings: SpeakerEq) {
        val calculated = candidateCoefficients
        for (index in 0 until EQ_BAND_COUNT) {
            val band = settings.band(index)
            val offset = index * 5
            if (!settings.isActive || band.gainDb == 0.0) {
                calculated[offset] = 1.0
                for (coefficient in 1..4) calculated[offset + coefficient] = 0.0
                continue
            }
            val amplitude = 10.0.pow(band.gainDb / 40)
            val omega = 2 * PI * band.frequencyHz / sampleRate
            val alpha = sin(omega) / (2 * band.q)
            val denominator = 1 + alpha / amplitude
            val cosine = cos(omega)
            calculated[offset] = (1 + alpha * amplitude) / denominator
            calculated[offset + 1] = -2 * cosine / denominator
            calculated[offset + 2] = (1 - alpha * amplitude) / denominator
            calculated[offset + 3] = -2 * cosine / denominator
            calculated[offset + 4] = (1 - alpha / amplitude) / denominator
            for (coefficient in 0..4) require(calculated[offset + coefficient].isFinite()) { "EQ coefficients are not finite; previous EQ retained." }
            val a1 = calculated[offset + 3]; val a2 = calculated[offset + 4]
            require(abs(a2) < 1 && 1 + a1 + a2 > 0 && 1 - a1 + a2 > 0) { "EQ coefficients are unstable; previous EQ retained." }
        }
        calculated.copyInto(prepared)
    }

    /** Fixed storage for a complete stable filter chain; never shared across outputs. */
    private class Bank {
        private val coefficients = DoubleArray(EQ_BAND_COUNT * 5)
        private val history = DoubleArray(EQ_BAND_COUNT * 2)
        private var active = false
        private var gain = 1.0
        /** @param settings Target settings. @param prepared Validated normalized coefficients to copy. */
        fun configure(settings: SpeakerEq, prepared: DoubleArray) {
            prepared.copyInto(coefficients)
            active = settings.isActive
            gain = 10.0.pow(-settings.headroomDb / 20)
            reset()
        }
        /** Clears history on a new target bank or failed filter, never on pause. */
        fun reset() { history.fill(0.0) }
        /** @param input Routed/gained source value. @return Unclamped double output with bank-specific headroom. */
        fun process(input: Double): Double {
            if (!active) return input
            var value = input * gain
            for (index in 0 until EQ_BAND_COUNT) {
                val c = index * 5; val h = index * 2
                val output = coefficients[c] * value + history[h]
                val first = coefficients[c + 1] * value - coefficients[c + 3] * output + history[h + 1]
                val second = coefficients[c + 2] * value - coefficients[c + 4] * output
                history[h] = if (abs(first) < 1e-30) 0.0 else first
                history[h + 1] = if (abs(second) < 1e-30) 0.0 else second
                value = output
            }
            return value
        }
    }
}
