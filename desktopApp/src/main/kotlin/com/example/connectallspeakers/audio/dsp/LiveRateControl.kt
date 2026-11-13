package com.example.connectallspeakers.audio.dsp

import kotlin.math.abs
import kotlin.math.exp

/** Queue/source-clock PI servo, independent for each output; not an acoustic measurement.
 * Input/output rate mismatch is corrected around a common declared source-lag target.
 * Error is filtered (one-second time constant), P=0.2/s, I=0.01/s², ±2000 ppm,
 * slew ≤100 ppm/s; a >50 ms position error or five seconds at the demand limit stops live playback.
 */
internal class LiveRateControl {
    private var integral = 0.0
    private var filteredError = 0.0
    private var limitedSeconds = 0.0
    /** Source-frame step correction in ppm, reset on each live epoch. */
    var correctionPpm = 0.0
        private set

    /** @param errorSeconds Desired minus observed played-source position, seconds.
     * @param elapsedSeconds Positive observation interval, ≤0.25 seconds.
     * @return Bounded smoothed source-rate correction, ppm.
     */
    fun update(errorSeconds: Double, elapsedSeconds: Double): Double {
        require(errorSeconds.isFinite() && elapsedSeconds.isFinite() && elapsedSeconds > 0 && elapsedSeconds <= .25)
        check(abs(errorSeconds) <= .05) { "Live output timing exceeded 50 ms. Restart live input." }
        filteredError += (errorSeconds - filteredError) * (1 - exp(-elapsedSeconds))
        integral = (integral + filteredError * 10_000 * elapsedSeconds).coerceIn(-2000.0, 2000.0)
        val demand = filteredError * 200_000 + integral
        limitedSeconds = if (abs(demand) >= 2000) limitedSeconds + elapsedSeconds else 0.0
        check(limitedSeconds < 5) { "Live output clock correction exceeded its supported range. Restart live input." }
        val maximumChange = 100 * elapsedSeconds
        correctionPpm += (demand.coerceIn(-2000.0, 2000.0) - correctionPpm).coerceIn(-maximumChange, maximumChange)
        return correctionPpm
    }
}
