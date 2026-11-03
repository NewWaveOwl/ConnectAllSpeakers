package com.example.connectallspeakers.model.audio

/** Maximum manual delay in milliseconds; bounds per-output PCM memory at every supported rate. */
const val MAX_OUTPUT_DELAY_MILLIS = 500
/** Timing packets and clock samples older than this many milliseconds must not appear live. */
const val TIMING_STALE_AFTER_MILLIS = 1_000L

/** Converts validated delay to source-rate frames, rounding up so the requested delay is never shortened.
 * @param delayMillis Whole milliseconds, 0..500.
 * @param sampleRate Source frames per second, 8000..192000.
 * @return Delay frames; rounding error is strictly less than one source frame.
 */
fun outputDelayFrames(delayMillis: Int, sampleRate: Int): Int {
    require(delayMillis in 0..MAX_OUTPUT_DELAY_MILLIS) { "Delay must be between 0 and $MAX_OUTPUT_DELAY_MILLIS ms." }
    require(sampleRate in 8_000..192_000) { "Unsupported delay sample rate." }
    return ((delayMillis.toLong() * sampleRate + 999) / 1000).toInt()
}

/** Copied, validated Windows clock reading. These are engine measurements, never acoustic latency.
 * @property positionTicks Stream-relative clock units; do not assume these are audio frames.
 * @property frequencyTicksPerSecond Clock units per second for this stream.
 * @property qpcPosition100Nanos Correlated QPC timestamp, already converted to 100-ns units by Windows.
 * @property positionMillis Stream position calculated as positionTicks * 1000 / frequency.
 */
data class OutputClockTiming(val positionTicks: Long, val frequencyTicksPerSecond: Long,
    val qpcPosition100Nanos: Long, val positionMillis: Double)

/** Immutable diagnostic packet for one explicitly selected output.
 * @property endpointId Exact endpoint identity.
 * @property sampleRate Source and nominal output-format frames/second; output counts differ from source progress after resampling.
 * @property delayMillis Prepared manual delay, fixed for this session.
 * @property delayFrames Prepared source-rate delay frames, rounded up.
 * @property capacityFrames Native queue capacity in output-format frames.
 * @property submittedFrames Complete stream frames submitted, including leading delay and final drain.
 * @property queuedFrames Current output-format queue count; null after Stop, failure or stale data.
 * @property sourcePositionFrames Queue-derived source progress excluding delay, mapped through actual resampling history;
 * floored to whole source frames, or null if stale/inactive/unavailable.
 * @property clock Valid recent native clock sample, or null for Unknown.
 * @property clockMessage Explicit reason for an Unknown clock; null for a valid reading.
 * @property streamLatencyMillis Windows-reported stream latency, not Bluetooth/acoustic latency; null if unavailable.
 * @property underruns Detected empty running queues before the end of this session.
 * @property isLive Whether this packet still describes an open session.
 * @property drift Experimental drift-control diagnostics, or null when correction is disabled.
 * @property eq Per-output EQ guard/transition diagnostics, or null without a prepared processor.
 */
data class OutputTiming(val endpointId: String, val sampleRate: Int, val delayMillis: Int,
    val delayFrames: Int, val capacityFrames: Int, val submittedFrames: Long,
    val queuedFrames: Int?, val sourcePositionFrames: Long?, val clock: OutputClockTiming? = null,
    val clockMessage: String? = "Clock unavailable", val streamLatencyMillis: Double? = null,
    val underruns: Int = 0, val isLive: Boolean = true, val drift: OutputDriftTiming? = null,
    val eq: OutputEqTiming? = null) {
    /** Nominal queue duration at the output-format rate, or Unknown; not acoustic latency. */
    val queuedMillis: Double? get() = queuedFrames?.let { it * 1000.0 / sampleRate }

    /** Removes stale runtime measurements while retaining the last session's counters/configuration.
     * @param reason Readable explanation of why measurements are Unknown.
     * @return Inactive packet with no cached position/latency presented as current.
     */
    fun unavailable(reason: String): OutputTiming = copy(isLive = false, queuedFrames = null,
        sourcePositionFrames = null, clock = null, clockMessage = reason, streamLatencyMillis = null,
        drift = drift?.unavailable(reason), eq = eq?.copy(transitioning = false, message = reason))
}
