package com.example.connectallspeakers.audio.dsp

import kotlin.math.floor

/** Fixed ring mapping output-frame boundaries to fractional source progress.
 * @param capacity Retained boundaries; caller includes native queue, manual delay and clock-lag reserve.
 */
internal class SourceFrameHistory(capacity: Int) {
    private val positions = DoubleArray(capacity.also { require(it in 2..864_002) })
    private var newest = 0

    /** Appends the next output boundary without allocating.
     * @param sourceFrame Nondecreasing finite source progress, in source frames.
     */
    fun append(sourceFrame: Double) {
        require(sourceFrame.isFinite() && sourceFrame >= positions[newest % positions.size])
        newest++
        positions[newest % positions.size] = sourceFrame
    }

    /** Interpolates retained frame boundaries, never fabricating an evicted/future position.
     * @param outputFrame Fractional output-frame boundary after excluding manual delay.
     * @return Source frames; null when unavailable. Nonpositive boundaries are initial silence.
     */
    fun position(outputFrame: Double): Double? {
        if (!outputFrame.isFinite()) return null
        if (outputFrame <= 0) return 0.0
        if (outputFrame > newest || outputFrame < maxOf(0, newest - positions.size + 1)) return null
        val first = floor(outputFrame).toInt()
        val fraction = outputFrame - first
        val left = positions[first % positions.size]
        return if (fraction == 0.0) left else left + (positions[(first + 1) % positions.size] - left) * fraction
    }
}
