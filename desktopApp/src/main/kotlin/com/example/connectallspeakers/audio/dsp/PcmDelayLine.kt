package com.example.connectallspeakers.audio.dsp

/** Fixed, preallocated byte FIFO for interleaved float32 PCM. Zero delay is an exact bypass.
 * @param delayFrames Nonnegative prepared source-rate delay.
 * @param channels Physical output channel count, one or two.
 * @param blockFrames Maximum frames passed to process; scratch memory is allocated once.
 */
internal class PcmDelayLine(delayFrames: Int, channels: Int, private val blockFrames: Int) {
    private val alignment = channels * 4
    private val ring: ByteArray
    private val scratch: ByteArray
    private var cursor = 0
    init {
        require(delayFrames in 0..96_000 && channels in 1..2 && blockFrames in 1..384_000)
        ring = ByteArray(delayFrames * alignment)
        scratch = ByteArray(if (delayFrames == 0) 0 else minOf(delayFrames, blockFrames) * alignment)
    }
    /** Replaces each input frame with its delayed predecessor; initial contents are silence.
     * Feed exactly delayFrames of trailing silence to drain all source frames at EOF.
     * @param bytes Borrowed in-place interleaved float32 buffer, at least frames * channels * 4 bytes.
     * @param frames Complete frames, bounded by the prepared block capacity.
     */
    fun process(bytes: ByteArray, frames: Int) {
        require(frames in 0..blockFrames && bytes.size >= frames * alignment)
        if (ring.isEmpty()) return
        var offset = 0
        val size = frames * alignment
        while (offset < size) {
            val count = minOf(size - offset, ring.size - cursor, scratch.size)
            bytes.copyInto(scratch, 0, offset, offset + count)
            ring.copyInto(bytes, offset, cursor, cursor + count)
            scratch.copyInto(ring, cursor, 0, count)
            offset += count
            cursor = (cursor + count) % ring.size
        }
    }
}
