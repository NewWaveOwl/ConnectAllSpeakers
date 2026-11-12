package com.example.connectallspeakers.audio.dsp

import com.example.connectallspeakers.model.audio.*

/** Independent, fixed-size EQ histories per physical channel, with common per-device settings.
 * @param sampleRate Processing frames/second. @param initial Validated settings for every channel.
 * @param channels One/two physical channels. No processing allocates or shares a channel's history.
 */
internal class OutputChannelEq(sampleRate: Int, initial: SpeakerEq = SpeakerEq(), channels: Int = 2) {
    private val processors = Array(channels) { OutputEqProcessor(sampleRate, initial) }
    init { require(channels in 1..2) }
    val tailFrames: Int get() = processors.maxOf { it.tailFrames }
    val remainingTailFrames: Int get() = processors.maxOf { it.remainingTailFrames }
    /** @param settings Common immutable device EQ; both channels transition independently. */
    fun request(settings: SpeakerEq) { processors.forEach { it.request(settings) } }
    /** @param input Routed/gained sample. @param endOfSource Whether bounded EOF drain is active.
     * @param channel Physical channel index. @return Filtered sample for that channel alone.
     */
    fun process(input: Float, endOfSource: Boolean, channel: Int): Float = processors[channel].process(input, endOfSource)
    /** Processes existing interleaved PCM without duplicating its left channel.
     * @param bytes Borrowed float32 LE storage. @param frames Complete output frames.
     * @param channels Physical channel count. @param sourceFrames Frames before EOF drain.
     */
    fun process(bytes: ByteArray, frames: Int, channels: Int, sourceFrames: Int) {
        require(channels == processors.size && sourceFrames in 0..frames && bytes.size >= frames * channels * 4)
        var offset = 0
        repeat(frames) { frame -> repeat(channels) { channel ->
            val bits = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) or
                ((bytes[offset + 2].toInt() and 255) shl 16) or (bytes[offset + 3].toInt() shl 24)
            val output = process(Float.fromBits(bits), frame >= sourceFrames, channel).toRawBits()
            bytes[offset++] = output.toByte(); bytes[offset++] = (output ushr 8).toByte()
            bytes[offset++] = (output ushr 16).toByte(); bytes[offset++] = (output ushr 24).toByte()
        } }
    }
    /** @return Aggregate device counters; headroom and transition retain their original units. */
    fun snapshot(): OutputEqTiming {
        val readings = processors.map { it.snapshot() }
        return readings.first().copy(limitedFrames = readings.maxOf { it.limitedFrames },
            transitioning = readings.any { it.transitioning }, message = readings.firstNotNullOfOrNull { it.message })
    }
}
