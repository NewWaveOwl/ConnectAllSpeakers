package com.example.connectallspeakers.audio.wasapi

import com.example.connectallspeakers.model.audio.OutputClockTiming
import com.example.connectallspeakers.model.audio.TIMING_STALE_AFTER_MILLIS

/** Raw copied clock values. Signed-negative UINT64 values are deliberately rejected, not reinterpreted.
 * @property positionTicks Native stream position units.
 * @property frequencyTicksPerSecond Native clock units per second.
 * @property qpc100Nanos Correlated sample timestamp in Windows 100-ns QPC units.
 * @property observedQpc100Nanos Current QPC timestamp in the same units, read after the clock call.
 * @property accurate False for S_FALSE/low-confidence native readings.
 */
internal data class NativeClockReading(val positionTicks: Long, val frequencyTicksPerSecond: Long,
    val qpc100Nanos: Long, val observedQpc100Nanos: Long, val accurate: Boolean = true)

/** Validates clock history independently of hardware. A clock never supplies acoustic latency. */
internal class OutputClockTracker {
    private var previous: NativeClockReading? = null
    private var progressAt = 0L
    /** Current validated clock, or null for Unknown. */
    var value: OutputClockTiming? = null; private set
    /** Reason for Unknown, or null when valid. */
    var message: String? = "Clock unavailable"; private set
    /** True when the current sample violates stream monotonicity/frequency; correction must stop. */
    var discontinuityDetected: Boolean = false; private set

    /** Checks native units, monotonicity, freshness and plausibility against submitted stream data.
     * @param reading Copied native reading, or null if unsupported/unavailable.
     * @param nowNanos Monotonic local time; tests inject virtual time.
     * @param submittedMillis Submitted stream duration, including delay; clock may lead by at most one second.
     * @param paused Whether engine consumption is intentionally frozen.
     */
    fun observe(reading: NativeClockReading?, nowNanos: Long, submittedMillis: Double, paused: Boolean) {
        value = null
        discontinuityDetected = false
        fun unknown(reason: String) { message = reason }
        if (paused) { progressAt = nowNanos; unknown("Paused; clock comparison unavailable"); return }
        if (reading == null) { unknown("Clock unavailable"); return }
        if (!reading.accurate || reading.positionTicks < 0 || reading.frequencyTicksPerSecond <= 0 ||
            reading.qpc100Nanos <= 0 || reading.observedQpc100Nanos < reading.qpc100Nanos) {
            unknown("Invalid or low-confidence clock reading"); return
        }
        val old = previous
        if (old != null && (old.frequencyTicksPerSecond != reading.frequencyTicksPerSecond ||
            reading.positionTicks < old.positionTicks || reading.qpc100Nanos < old.qpc100Nanos)) {
            discontinuityDetected = true
            unknown("Clock discontinuity; restart playback to reset timing"); return
        }
        if (reading.observedQpc100Nanos - reading.qpc100Nanos > TIMING_STALE_AFTER_MILLIS * 10_000) {
            unknown("Clock timestamp is stale"); return
        }
        val positionMillis = reading.positionTicks.toDouble() / reading.frequencyTicksPerSecond * 1000.0
        if (!positionMillis.isFinite() || positionMillis > submittedMillis + 1000.0) {
            unknown("Clock position is inconsistent with submitted data"); return
        }
        if (old == null || old.positionTicks != reading.positionTicks) progressAt = nowNanos
        previous = reading
        if (nowNanos - progressAt > TIMING_STALE_AFTER_MILLIS * 1_000_000) {
            unknown("Clock has not advanced"); return
        }
        message = null
        value = OutputClockTiming(reading.positionTicks, reading.frequencyTicksPerSecond, reading.qpc100Nanos, positionMillis)
    }
}
