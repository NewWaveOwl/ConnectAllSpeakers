package com.example.connectallspeakers.audio.dsp

import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.PlaybackRoute
import com.example.connectallspeakers.model.audio.PlaybackMix

/** Routes one source channel (or a mono average) into every physical output channel.
 * Samples are finite, clamped floats; gain is linear and never exceeds unity.
 */
internal object ChannelRenderer {
    /** Writes little-endian IEEE float32 into reusable endpoint storage.
     * @param source Borrowed decoded interleaved mono/stereo samples.
     * @param firstFrame First source frame, inclusive.
     * @param frames Number of complete source frames to write.
     * @param outputChannels Physical channel count, one or two.
     * @param route Explicit channel assignment and app gain; never Windows master volume.
     * @param destination Caller-owned buffer, at least frames * outputChannels * 4 bytes.
     */
    fun render(source: DecodedAudio, firstFrame: Int, frames: Int, outputChannels: Int,
        route: PlaybackRoute, destination: ByteArray) {
        require(outputChannels in 1..2 && firstFrame >= 0 && frames >= 0 && firstFrame <= source.frames - frames)
        require(destination.size >= frames * outputChannels * 4)
        var offset = 0
        for (frame in firstFrame until firstFrame + frames) {
            val left = source.samples[frame * source.channels]
            val right = if (source.channels == 2) source.samples[frame * 2 + 1] else left
            repeat(outputChannels) { channel ->
                val bits = sample(left, right, channel, outputChannels, route).toRawBits()
                destination[offset++] = bits.toByte()
                destination[offset++] = (bits ushr 8).toByte()
                destination[offset++] = (bits ushr 16).toByte()
                destination[offset++] = (bits ushr 24).toByte()
            }
        }
    }
    /** Selects one physical channel without allocating or sharing filter state.
     * @param left Source L, or the sole source channel. @param right Source R, or L for mono sources.
     * @param channel Physical channel index. @param outputChannels One or two physical channels.
     * @param route Explicit mix/gain. @return Finite bounded normalized sample; mono uses (L+R)/2.
     */
    fun sample(left: Float, right: Float, channel: Int, outputChannels: Int, route: PlaybackRoute): Float {
        val input = if (route.mix == PlaybackMix.FullStereo) {
            if (outputChannels == 1) (left + right) * .5f else if (channel == 0) left else right
        } else when (route.channel) {
            SpeakerChannel.Left -> left
            SpeakerChannel.Right -> right
            SpeakerChannel.Mono -> (left + right) * .5f
        }
        return if (route.muted || !input.isFinite()) 0f else (input * (route.volumePercent / 100f)).coerceIn(-1f, 1f)
    }
}
