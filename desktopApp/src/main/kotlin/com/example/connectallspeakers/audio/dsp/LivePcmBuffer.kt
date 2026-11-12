package com.example.connectallspeakers.audio.dsp

import com.example.connectallspeakers.model.audio.LIVE_SAMPLE_RATE
import kotlin.math.abs
import kotlin.math.floor

/** Fixed two-second stereo history. The audio worker owns producer and all independent read cursors.
 * Overflow is a failure, never an overwrite of a slow output's unread frames.
 */
internal class LivePcmBuffer {
    private val samples = FloatArray(CAPACITY_FRAMES * 2)
    private var firstDeviceFrame: Long? = null
    private var expectedDeviceFrame = 0L
    private var lastDeviceFrame = 0L
    private var lastQpc = 0L
    private var oldestFrame = 0L
    /** Accepted source frames in this epoch, not a session-duration-sized allocation. */
    var writtenFrames = 0L
        private set
    /** Retained source frames; bounded by CAPACITY_FRAMES. */
    val retainedFrames: Int get() = (writtenFrames - oldestFrame).toInt()
    /** Input samples clamped to normalized range, never silently reported as bit-exact capture. */
    var limitedSamples = 0L
        private set

    /** Copies a borrowed packet after validating identity/timing and the complete input before mutation.
     * @param input Borrowed interleaved stereo float32.
     * @param frames Complete packet frames.
     * @param deviceFrame Native first frame in the stream's frame domain.
     * @param qpc100Nanos Correlated first-frame performance timestamp.
     * @param now100Nanos Same-domain current observation.
     * @param discontinuity True only for the first packet of a new epoch.
     * @param timestampError Rejects unreliable Windows timing.
     */
    fun append(input: FloatArray, frames: Int, deviceFrame: Long, qpc100Nanos: Long, now100Nanos: Long,
        discontinuity: Boolean, timestampError: Boolean) {
        require(frames in 1..LIVE_SAMPLE_RATE && frames <= input.size / 2) { "Invalid live PCM packet length." }
        require(!timestampError && qpc100Nanos >= 0 && now100Nanos >= qpc100Nanos &&
            now100Nanos - qpc100Nanos <= 2_500_000L) { "Virtual input timestamps are invalid or over 250 ms old. Restart live input." }
        require(deviceFrame >= 0 && deviceFrame <= Long.MAX_VALUE - frames) { "Invalid live input frame position." }
        if (firstDeviceFrame != null) {
            check(!discontinuity && deviceFrame == expectedDeviceFrame && qpc100Nanos > lastQpc) { "Virtual input timeline changed. Restart live input." }
            val expected = (deviceFrame - lastDeviceFrame) * 10_000_000.0 / LIVE_SAMPLE_RATE
            check(abs((qpc100Nanos - lastQpc) - expected) <= maxOf(20_000.0, expected * .005)) { "Virtual input clock jumped. Restart live input." }
        }
        check(writtenFrames <= Long.MAX_VALUE - frames && writtenFrames + frames - oldestFrame <= CAPACITY_FRAMES) {
            "Live input overflow: an output fell behind the bounded two-second history. Restart live input."
        }
        for (index in 0 until frames * 2) require(input[index].isFinite()) { "Virtual input supplied non-finite PCM." }
        if (firstDeviceFrame == null) firstDeviceFrame = deviceFrame
        for (frame in 0 until frames) {
            val offset = ((writtenFrames + frame) % CAPACITY_FRAMES).toInt() * 2
            for (channel in 0..1) {
                val value = input[frame * 2 + channel]
                if (value < -1 || value > 1) limitedSamples++
                samples[offset + channel] = value.coerceIn(-1f, 1f)
            }
        }
        writtenFrames += frames
        expectedDeviceFrame = deviceFrame + frames
        lastDeviceFrame = deviceFrame
        lastQpc = qpc100Nanos
    }

    /** Releases only frames older than every output's retained interpolation boundary.
     * @param firstNeeded Earliest still-needed source frame, preserving cubic look-behind.
     */
    fun retainFrom(firstNeeded: Long) { oldestFrame = firstNeeded.coerceIn(oldestFrame, writtenFrames) }

    /** @param now100Nanos Current correlated performance time. @return Projected live source position. */
    fun positionAt(now100Nanos: Long): Double {
        val origin = checkNotNull(firstDeviceFrame)
        check(now100Nanos >= lastQpc && now100Nanos - lastQpc <= 2_500_000L) { "The live source clock is stale." }
        return (lastDeviceFrame - origin) + (now100Nanos - lastQpc) * LIVE_SAMPLE_RATE / 10_000_000.0
    }

    /** @param position Fractional source frame. @return Whether cubic look-ahead is retained. */
    fun canRead(position: Double): Boolean = position.isFinite() && position >= oldestFrame &&
        floor(position) + 2 < writtenFrames && maxOf(0, floor(position).toLong() - 1) >= oldestFrame

    /** Four-point cubic interpolation, without crossing channels or allocating.
     * @param position Source frame. @param channel Left=0/right=1. @return Interpolated source sample.
     */
    fun sample(position: Double, channel: Int): Float {
        check(canRead(position) && channel in 0..1) { "Live PCM history is unavailable. Restart live input." }
        val index = floor(position).toLong(); val fraction = position - index
        fun at(frame: Long): Double {
            val bounded = maxOf(0, frame)
            check(bounded >= oldestFrame) { "Live interpolation history was evicted." }
            return samples[(bounded % CAPACITY_FRAMES).toInt() * 2 + channel].toDouble()
        }
        val center = at(index)
        if (fraction == 0.0) return center.toFloat()
        val previous = at(index - 1); val next = at(index + 1); val after = at(index + 2)
        return (center + .5 * fraction * (next - previous + fraction *
            (2 * previous - 5 * center + 4 * next - after + fraction * (3 * (center - next) + after - previous)))).toFloat()
    }

    /** Discards retained audio and timing on Stop/idle/source epoch change. */
    fun clear() {
        samples.fill(0f); firstDeviceFrame = null; writtenFrames = 0; expectedDeviceFrame = 0
        lastDeviceFrame = 0; lastQpc = 0; oldestFrame = 0; limitedSamples = 0
    }

    companion object { const val CAPACITY_FRAMES = LIVE_SAMPLE_RATE * 2 }
}
