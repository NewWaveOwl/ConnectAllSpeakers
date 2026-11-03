package com.example.connectallspeakers.model.audio

/** Maximum fractional source-rate correction; 1,000 ppm is 0.1 percent. */
const val MAX_DRIFT_CORRECTION_PPM = 1_000.0

/** Experimental controller lifecycle; Tracking is an engine estimate, never acoustic certification. */
enum class DriftPhase { WarmingUp, Tracking, Degraded, Paused, Draining, Inactive, Failed }

/** Portable diagnostics for one output relative to an explicitly selected reference.
 * @property referenceEndpointId Exact endpoint identity, never a substituted default.
 * @property isReference Whether this output is held at its original source rate.
 * @property phase Estimator/control lifecycle.
 * @property relativeRatePpm Follower clock rate minus reference rate, in parts per million; positive means faster.
 * @property skewMillis Source-progress change since the post-warmup baseline; positive means follower ahead.
 * @property initialEngineOffsetMillis Baseline follower-minus-reference source position, not acoustic latency.
 * @property correctionPpm Applied source frames per output frame minus one, in ppm; positive advances source faster.
 * @property uncertaintyMillis Estimated engine sampling uncertainty, not an acoustic confidence interval.
 * @property message Reason for degraded/unknown state or readable controller status.
 */
data class OutputDriftTiming(val referenceEndpointId: String, val isReference: Boolean, val phase: DriftPhase,
    val relativeRatePpm: Double? = null, val skewMillis: Double? = null, val initialEngineOffsetMillis: Double? = null,
    val correctionPpm: Double = 0.0, val uncertaintyMillis: Double? = null, val message: String) {
    init { require(correctionPpm.isFinite() && correctionPpm in -MAX_DRIFT_CORRECTION_PPM..MAX_DRIFT_CORRECTION_PPM) }

    /** Invalidates live estimates while retaining the last applied rate as historical information.
     * @param reason Explanation of the ended/stale session.
     * @return A non-tracking immutable packet.
     */
    fun unavailable(reason: String): OutputDriftTiming = copy(
        phase = if (phase == DriftPhase.Failed) phase else DriftPhase.Inactive,
        relativeRatePpm = null, skewMillis = null, uncertaintyMillis = null,
        message = if (phase == DriftPhase.Failed) message else reason)
}
