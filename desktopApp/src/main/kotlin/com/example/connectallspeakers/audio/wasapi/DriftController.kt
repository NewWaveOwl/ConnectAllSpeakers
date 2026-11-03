package com.example.connectallspeakers.audio.wasapi

import com.example.connectallspeakers.model.audio.*
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** One copied observation; source progress is mapped through the actual prepared delay/resampling history.
 * @property clock Valid native position/frequency/QPC, or null for Unknown.
 * @property sourceMillis Source progress at this clock's timestamp, not acoustic arrival.
 * @property observedQpc100Nanos Current QPC read after the native clock call, in 100-ns units.
 * @property discontinuity Whether native monotonicity/frequency validation failed.
 */
internal data class DriftObservation(val clock: OutputClockTiming?, val sourceMillis: Double?,
    val observedQpc100Nanos: Long?, val discontinuity: Boolean = false)

/** Bounded experimental clock servo: five-second minimum/51-sample regression, feed-forward rate
 * and a slow proportional phase trim. Initial engine offset is retained, not treated as acoustic delay.
 * @param referenceEndpointId Explicit zero-manual-delay reference identity.
 * @param sampleRate Output-format frames/second, used only for quantization uncertainty.
 */
internal class DriftController(private val referenceEndpointId: String, private val sampleRate: Int) {
    private val times = DoubleArray(51)
    private val referencePositions = DoubleArray(51)
    private val followerPositions = DoubleArray(51)
    private var count = 0
    private var next = 0
    private var originQpc: Long? = null
    private var firstGoodNanos: Long? = null
    private var invalidSince: Long? = null
    private var limitedSince: Long? = null
    private var previousQpc: Long? = null
    private var previousReference = 0.0
    private var previousFollower = 0.0
    private var previousNanos: Long? = null
    private var baseline: Double? = null
    private var filteredRate = 0.0
    private var filteredSkew = 0.0
    private var relativeRate: Double? = null
    private var skew: Double? = null
    private var uncertainty: Double? = null
    private var phase = DriftPhase.WarmingUp
    private var message = "Experimental: waiting for reliable clocks, then warming up."
    /** Actual bounded source-rate correction; zero means one source frame per output frame. */
    var correctionPpm = 0.0; private set

    /** Starts a new observation baseline while retaining the last safe rate across paused buffers. */
    fun rebaseline() {
        count = 0; next = 0; originQpc = null; firstGoodNanos = null; invalidSince = null; limitedSince = null
        previousQpc = null; previousNanos = null; baseline = null
        filteredRate = (1 / (1 + correctionPpm / 1_000_000) - 1) * 1_000_000; filteredSkew = 0.0
        relativeRate = null; skew = null; uncertainty = null; phase = DriftPhase.WarmingUp
        message = "Experimental: rebaselining clocks; manual delay and buffered source are retained."
    }

    /** Marks intentional clock freezing; paused wall time never enters regression or failure deadlines. */
    fun pause() { rebaseline(); phase = DriftPhase.Paused; message = "Paused; drift estimates Unknown. Rate/buffers retained." }

    /** Holds the current ratio during normal source/delay/queue exhaustion without extrapolating EOF progress. */
    fun drain() { phase = DriftPhase.Draining; relativeRate = null; skew = null; uncertainty = null; message = "Draining source/delay queues; correction held." }

    /** Holds a trusted bounded rate briefly, then stops instead of running blind indefinitely.
     * @param nowNanos Monotonic observation time.
     * @param reason Readable reason that clock/source timing is unusable.
     */
    fun unreliable(nowNanos: Long, reason: String) {
        val since = invalidSince ?: nowNanos.also { invalidSince = it }
        if (nowNanos - since > if (baseline == null) 5_000_000_000L else 2_000_000_000L) {
            fail("Reliable drift timing was lost: $reason. Reconnect/refresh as needed and explicitly restart, or disable drift correction.")
        }
        phase = DriftPhase.Degraded; relativeRate = null; skew = null; uncertainty = null
        message = "$reason. Holding the last bounded rate briefly; estimates Unknown."
    }

    /** Observes independently timestamped clocks, extrapolating at most 50 ms to their common QPC.
     * @param reference Zero-delay reference observation.
     * @param follower Corrected output observation.
     * @param nowNanos Monotonic time; virtual in ordinary tests.
     * @return Smoothed source-rate correction in ppm. Throws on unsafe demand/discontinuity.
     */
    fun observe(reference: DriftObservation, follower: DriftObservation, nowNanos: Long): Double {
        if (reference.discontinuity || follower.discontinuity) fail("Audio clock discontinuity. Playback stopped; explicitly restart to rebaseline.")
        val a = reference.clock
        val b = follower.clock
        val sourceA = reference.sourceMillis
        val sourceB = follower.sourceMillis
        fun invalid(reason: String): Double { unreliable(nowNanos, reason); return correctionPpm }
        if (a == null || b == null || sourceA == null || sourceB == null || !sourceA.isFinite() || !sourceB.isFinite())
            return invalid("Clock/source history unavailable")
        if (!a.positionMillis.isFinite() || !b.positionMillis.isFinite() || a.positionTicks < 0 || b.positionTicks < 0 ||
            a.frequencyTicksPerSecond <= 0 || b.frequencyTicksPerSecond <= 0 || sourceA < 0 || sourceB < 0)
            return invalid("Invalid clock/source units")
        val observedA = reference.observedQpc100Nanos ?: return invalid("Clock age Unknown")
        val observedB = follower.observedQpc100Nanos ?: return invalid("Clock age Unknown")
        if (a.qpcPosition100Nanos <= 0 || b.qpcPosition100Nanos <= 0 ||
            observedA < a.qpcPosition100Nanos || observedB < b.qpcPosition100Nanos ||
            observedA - a.qpcPosition100Nanos > 500_000 || observedB - b.qpcPosition100Nanos > 500_000 ||
            abs(a.qpcPosition100Nanos - b.qpcPosition100Nanos) > 500_000)
            return invalid("Clock timestamps exceed the 50 ms drift-comparison bound")
        if (sourceA <= 0 || sourceB <= 0) return invalid("Waiting for source progress after manual-delay silence")
        val common = maxOf(a.qpcPosition100Nanos, b.qpcPosition100Nanos)
        val advanceA = (common - a.qpcPosition100Nanos) / 10_000.0
        val advanceB = (common - b.qpcPosition100Nanos) / 10_000.0
        val clockA = a.positionMillis + advanceA
        val clockB = b.positionMillis + advanceB
        val difference = sourceB + advanceB * (1 + correctionPpm / 1_000_000) - sourceA - advanceA
        val lastQpc = previousQpc
        if (lastQpc != null) {
            if (common < lastQpc || clockA < previousReference || clockB < previousFollower)
                fail("Audio clock moved backwards. Playback stopped; explicitly restart.")
            if (common == lastQpc) return invalid("Repeated clock timestamp ignored")
            val elapsed = (common - lastQpc) / 10_000.0
            val errorA = abs(clockA - previousReference - elapsed)
            val errorB = abs(clockB - previousFollower - elapsed)
            if (maxOf(errorA, errorB) > 10.0) fail("Audio clock jumped by over 10 ms. Playback stopped; explicitly restart.")
            if (maxOf(errorA, errorB) > 2.0) return invalid("Transient clock outlier rejected")
            if (elapsed > 1_000) return invalid("Clock observations are too far apart")
        }
        previousQpc = common; previousReference = clockA; previousFollower = clockB
        val elapsedSeconds = ((nowNanos - (previousNanos ?: nowNanos)) / 1_000_000_000.0).coerceIn(0.0, 1.0)
        previousNanos = nowNanos
        val first = firstGoodNanos ?: nowNanos.also { firstGoodNanos = it }
        if (nowNanos - first < 2_000_000_000L) {
            invalidSince = null
            phase = DriftPhase.WarmingUp; message = "Settling clocks for 2 seconds; initial offset is not corrected."
            return correctionPpm
        }
        if (baseline == null) baseline = difference
        val initial = baseline!!
        val origin = originQpc ?: common.also { originQpc = it }
        times[next] = (common - origin) / 10_000.0
        referencePositions[next] = clockA; followerPositions[next] = clockB
        next = (next + 1) % times.size
        count = minOf(count + 1, times.size)
        val rawSkew = difference - initial
        if (abs(rawSkew) > 25.0) fail("Accumulated engine skew exceeded 25 ms. Playback stopped instead of skipping audio; explicitly restart.")
        filteredSkew += (rawSkew - filteredSkew) * (1 - exp(-elapsedSeconds / 2))
        skew = filteredSkew
        val firstIndex = if (count == times.size) next else 0
        val lastIndex = (next + times.size - 1) % times.size
        if (count < 20 || times[lastIndex] - times[firstIndex] < 5_000) {
            invalidSince = null
            phase = DriftPhase.WarmingUp; message = "Collecting at least 5 seconds of clock history; correction held."
            return correctionPpm
        }
        var meanTime = 0.0; var meanA = 0.0; var meanB = 0.0
        repeat(count) { meanTime += times[it]; meanA += referencePositions[it]; meanB += followerPositions[it] }
        meanTime /= count; meanA /= count; meanB /= count
        var sumXX = 0.0; var sumXA = 0.0; var sumXB = 0.0
        repeat(count) {
            val x = times[it] - meanTime
            sumXX += x * x; sumXA += x * (referencePositions[it] - meanA); sumXB += x * (followerPositions[it] - meanB)
        }
        val slopeA = sumXA / sumXX
        val slopeB = sumXB / sumXX
        if (!slopeA.isFinite() || !slopeB.isFinite() || slopeA !in .995..1.005 || slopeB !in .995..1.005)
            fail("Native clock rate is outside the supported measurement range. Playback stopped; disable correction or explicitly restart.")
        val rate = (slopeB / slopeA - 1) * 1_000_000
        var squareError = 0.0
        repeat(count) {
            val predicted = (meanB - meanA) + (slopeB - slopeA) * (times[it] - meanTime)
            val error = followerPositions[it] - referencePositions[it] - predicted
            squareError += error * error
        }
        val rms = sqrt(squareError / count)
        val rateUncertainty = max(1.0, 3 * rms / sqrt(sumXX) * 1_000_000)
        uncertainty = rms + 1000.0 / sampleRate + abs(advanceB - advanceA) * .01
        if (uncertainty!! > 2.0) return invalid("Clock uncertainty exceeds 2 ms")
        invalidSince = null
        if (abs(rate) - rateUncertainty > 501.0)
            fail("Measured relative clock drift exceeds the supported ±500 ppm range. Playback stopped; disable correction or explicitly restart.")
        filteredRate += (rate - filteredRate) * (1 - exp(-elapsedSeconds / 5))
        relativeRate = filteredRate
        val rateTerm = if (abs(filteredRate) <= max(10.0, rateUncertainty)) 0.0
            else (1 / (1 + filteredRate / 1_000_000) - 1) * 1_000_000
        val phaseTerm = if (abs(filteredSkew) <= .25 + uncertainty!!) 0.0 else filteredSkew * 100
        val demand = rateTerm - phaseTerm
        if (abs(demand) > MAX_DRIFT_CORRECTION_PPM) {
            val since = limitedSince ?: nowNanos.also { limitedSince = it }
            if (nowNanos - since > 5_000_000_000L) fail("Required drift correction exceeded ±1000 ppm for 5 seconds. Playback stopped; recalibrate and explicitly restart.")
            phase = DriftPhase.Degraded; message = "Correction capped at ±1000 ppm; stopping if demand persists for 5 seconds."
        } else {
            limitedSince = null; phase = DriftPhase.Tracking
            message = "Experimental engine drift tracking; acoustic alignment is unverified."
        }
        val bounded = demand.coerceIn(-MAX_DRIFT_CORRECTION_PPM, MAX_DRIFT_CORRECTION_PPM)
        val maximumChange = 100.0 * elapsedSeconds
        correctionPpm += (bounded - correctionPpm).coerceIn(-maximumChange, maximumChange)
        return correctionPpm
    }

    /** @param isReference Whether this packet describes the unchanged reference output.
     * @return Immutable copied diagnostics, with explicit Unknown estimates outside usable observations.
     */
    fun snapshot(isReference: Boolean): OutputDriftTiming = OutputDriftTiming(referenceEndpointId, isReference, phase,
        relativeRate?.let { if (isReference) 0.0 else it }, skew?.let { if (isReference) 0.0 else it },
        baseline?.let { if (isReference) 0.0 else it }, if (isReference) 0.0 else correctionPpm,
        uncertainty, message)

    /** @param reason Actionable whole-session failure; no output substitution or position skip occurs. */
    private fun fail(reason: String): Nothing {
        phase = DriftPhase.Failed; message = reason; relativeRate = null; skew = null; uncertainty = null
        throw IllegalStateException(reason)
    }
}
