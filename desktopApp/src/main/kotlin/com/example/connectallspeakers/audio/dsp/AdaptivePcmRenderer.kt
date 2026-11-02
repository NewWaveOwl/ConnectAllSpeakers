package com.example.connectallspeakers.audio.dsp

import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.MAX_DRIFT_CORRECTION_PPM
import com.example.connectallspeakers.model.audio.PlaybackRoute

/** Fractional source cursor with four-point cubic interpolation, followed by the existing delay FIFO.
 * Uses the decoded file's bounded random access; no input queue grows with playback duration.
 * @param source Borrowed immutable decoded samples.
 * @param channels Physical output channels, one or two.
 * @param capacityFrames Maximum output block/native queue size.
 * @param delayFrames Prepared manual delay in output-format frames, unchanged by correction.
 */
internal class AdaptivePcmRenderer(private val source: DecodedAudio, private val channels: Int,
    private val capacityFrames: Int, private val delayFrames: Int) {
    private val history = SourceFrameHistory(capacityFrames + delayFrames + source.sampleRate * 2 + 2)
    private val delay = PcmDelayLine(delayFrames, channels, capacityFrames)
    private var sourceFrame = 0.0
    private var tailFrames = delayFrames
    private var correctionPpm = 0.0
    /** True after source interpolation and the complete manual-delay tail have been submitted. */
    val finished: Boolean get() = sourceFrame >= source.frames && tailFrames == 0
    /** True once source input is exhausted; the native queue/delay can still be draining. */
    val sourceEnded: Boolean get() = sourceFrame >= source.frames

    /** Sets the bounded rate chosen by the smoothed controller; never rewinds or jumps the source cursor.
     * @param ppm Source frames/output frame minus one, in parts per million.
     */
    fun setCorrection(ppm: Double) {
        require(ppm.isFinite() && ppm in -MAX_DRIFT_CORRECTION_PPM..MAX_DRIFT_CORRECTION_PPM)
        correctionPpm = ppm
    }

    /** Renders up to the available native capacity and drains the manual delay exactly once.
     * @param bytes Borrowed reusable float32 little-endian storage.
     * @param maximumFrames Available native frames, bounded by prepared capacity.
     * @param route Latest deliberate channel/gain; delay is fixed at preparation.
     * @return Complete output frames written, including trailing delay silence.
     */
    fun render(bytes: ByteArray, maximumFrames: Int, route: PlaybackRoute): Int {
        require(maximumFrames in 0..capacityFrames && bytes.size >= maximumFrames * channels * 4)
        var frames = 0
        var offset = 0
        val gain = route.volumePercent / 100f
        val step = 1.0 + correctionPpm / 1_000_000.0
        while (frames < maximumFrames && !finished) {
            val sample = if (!sourceEnded) {
                val index = sourceFrame.toInt()
                val fraction = sourceFrame - index
                val left = interpolate(index, fraction, 0)
                val right = if (source.channels == 1) left else interpolate(index, fraction, 1)
                val input = when (route.channel) {
                    SpeakerChannel.Left -> left
                    SpeakerChannel.Right -> right
                    SpeakerChannel.Mono -> (left + right) * .5f
                }
                sourceFrame = minOf(source.frames.toDouble(), sourceFrame + step)
                if (input.isFinite()) (input * gain).coerceIn(-1f, 1f) else 0f
            } else { tailFrames--; 0f }
            history.append(sourceFrame)
            val bits = sample.toRawBits()
            repeat(channels) {
                bytes[offset++] = bits.toByte(); bytes[offset++] = (bits ushr 8).toByte()
                bytes[offset++] = (bits ushr 16).toByte(); bytes[offset++] = (bits ushr 24).toByte()
            }
            frames++
        }
        delay.process(bytes, frames)
        return frames
    }

    /** Maps queued/native-clock output progress through the actual historical resampling ratios.
     * @param playedFrames Output-format frames from the stream start, including manual-delay silence.
     * @return Fractional source progress, or null when the clock falls outside retained history.
     */
    fun sourcePosition(playedFrames: Double): Double? = history.position(playedFrames - delayFrames)

    /** @param index Whole source frame. @param fraction Fraction from that frame, in [0, 1).
     * @param channel Source channel index. @return One interpolated source channel without channel mixing.
     */
    private fun interpolate(index: Int, fraction: Double, channel: Int): Float {
        fun sample(frame: Int) = source.samples[frame.coerceIn(0, source.frames - 1) * source.channels + channel].toDouble()
        val center = sample(index)
        if (fraction == 0.0) return center.toFloat()
        val previous = sample(index - 1)
        val next = sample(index + 1)
        val after = sample(index + 2)
        return (center + .5 * fraction * (next - previous + fraction *
            (2 * previous - 5 * center + 4 * next - after + fraction * (3 * (center - next) + after - previous)))).toFloat()
    }
}
