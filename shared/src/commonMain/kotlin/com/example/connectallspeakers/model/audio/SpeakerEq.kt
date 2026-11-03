package com.example.connectallspeakers.model.audio

/** Bounds for three serial peaking-bell filters; frequencies are additionally limited by the source rate. */
const val EQ_MIN_FREQUENCY_HZ = 40.0
const val EQ_MAX_FREQUENCY_HZ = 16_000.0
const val EQ_MAX_GAIN_DB = 12.0
const val EQ_MIN_Q = .3
const val EQ_MAX_Q = 2.0
const val EQ_BAND_COUNT = 3

/** Immutable bell-filter parameters; invalid floating-point values never enter a route/profile.
 * @property frequencyHz Center frequency, 40..16000 Hz, further checked against the processing rate.
 * @property gainDb Peak gain, -12..12 decibels.
 * @property q Dimensionless bandwidth control, 0.3..2.0 (larger means narrower).
 */
data class EqBand(val frequencyHz: Double, val gainDb: Double = 0.0, val q: Double = 1.0) {
    init {
        require(frequencyHz.isFinite() && frequencyHz in EQ_MIN_FREQUENCY_HZ..EQ_MAX_FREQUENCY_HZ) { "EQ frequency must be 40–16000 Hz." }
        require(gainDb.isFinite() && gainDb in -EQ_MAX_GAIN_DB..EQ_MAX_GAIN_DB) { "EQ gain must be −12 to +12 dB." }
        require(q.isFinite() && q in EQ_MIN_Q..EQ_MAX_Q) { "EQ Q must be 0.3–2.0." }
    }
}

/** Three independent bell bands with explicit bypass. Default/reset is bypassed and neutral.
 * @property enabled False bypasses all filters/headroom while retaining band edits.
 * @property low First bell; initially 120 Hz.
 * @property mid Second bell; initially 1000 Hz.
 * @property high Third bell; initially 3000 Hz, supported even at 8000 Hz source rate.
 */
data class SpeakerEq(val enabled: Boolean = false, val low: EqBand = EqBand(120.0),
    val mid: EqBand = EqBand(1000.0), val high: EqBand = EqBand(3000.0)) {
    /** True only when filtering actually changes the signal. Neutral/bypassed processing is exact identity. */
    val isActive: Boolean get() = enabled && (low.gainDb != 0.0 || mid.gainDb != 0.0 || high.gainDb != 0.0)
    /** Positive automatic attenuation in decibels: sum of all positive band gains, zero when inactive. */
    val headroomDb: Double get() = if (isActive) maxOf(0.0, low.gainDb) + maxOf(0.0, mid.gainDb) + maxOf(0.0, high.gainDb) else 0.0

    /** @param index Band index, 0..2. @return Immutable selected band. */
    fun band(index: Int): EqBand = when (index) { 0 -> low; 1 -> mid; 2 -> high; else -> throw IllegalArgumentException("Choose an EQ band from 1 to 3.") }

    /** @param index Band index, 0..2. @param band Validated replacement. @return Independent settings. */
    fun withBand(index: Int, band: EqBand): SpeakerEq = when (index) {
        0 -> copy(low = band); 1 -> copy(mid = band); 2 -> copy(high = band)
        else -> throw IllegalArgumentException("Choose an EQ band from 1 to 3.")
    }

    /** Validates enabled filters against the actual source/output-processing rate, not the Windows mix format.
     * Bypassed edits can be retained at other rates; enabling them must pass this check again.
     * @param sampleRate Processing frames/second, 8000..192000.
     * @throws IllegalArgumentException For unsupported rates or any enabled center above 0.45 * sampleRate.
     */
    fun validateForSampleRate(sampleRate: Int) {
        val maximum = maximumEqFrequency(sampleRate)
        if (enabled) for (index in 0 until EQ_BAND_COUNT) require(band(index).frequencyHz <= maximum) {
            "EQ band ${index + 1} must be at most ${maximum.toInt()} Hz for a $sampleRate Hz source. Lower its frequency or bypass EQ."
        }
    }
}

/** @param sampleRate Actual processing frames/second. @return Supported center-frequency ceiling in Hz. */
fun maximumEqFrequency(sampleRate: Int): Double {
    require(sampleRate in 8_000..192_000) { "EQ supports source rates from 8000 to 192000 Hz." }
    return minOf(EQ_MAX_FREQUENCY_HZ, sampleRate * .45)
}

/** Copied session diagnostics; never persisted or presented as acoustic evidence.
 * @property headroomDb Applied target attenuation in dB; transitions blend banks with their own headroom.
 * @property limitedFrames Frames hitting the final safety guard, counted once before physical-channel duplication.
 * @property transitioning Whether a 100 ms crossfade is in progress.
 * @property message Rejected-update/fallback/drain explanation, or null for ordinary rendering.
 */
data class OutputEqTiming(val headroomDb: Double, val limitedFrames: Long, val transitioning: Boolean, val message: String? = null)
