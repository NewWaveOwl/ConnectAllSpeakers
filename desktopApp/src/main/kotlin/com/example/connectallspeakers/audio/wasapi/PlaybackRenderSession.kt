package com.example.connectallspeakers.audio.wasapi

import com.example.connectallspeakers.audio.dsp.ChannelRenderer
import com.example.connectallspeakers.audio.dsp.DecodedAudio
import com.example.connectallspeakers.audio.dsp.PcmDelayLine
import com.example.connectallspeakers.model.audio.MAX_PLAYBACK_OUTPUTS
import com.example.connectallspeakers.model.audio.PlaybackRoute
import com.example.connectallspeakers.model.audio.OutputTiming
import com.example.connectallspeakers.model.audio.outputDelayFrames

/** Owns all selected streams and their independent cursors on one thread.
 * All endpoints are prepared before starting any. Failure is atomic at the session boundary.
 * @param source Borrowed immutable PCM retained for this session.
 * @param devices Owned COM lifetime, released after endpoints even on partial initialization.
 */
internal class PlaybackRenderSession(private val source: DecodedAudio, private val devices: PlaybackDeviceSession) : AutoCloseable {
    private data class Stream(val routeId: String, val endpoint: PlaybackEndpoint, val bytes: ByteArray,
        val delayMillis: Int, val delayFrames: Int, val delay: PcmDelayLine,
        val clock: OutputClockTracker = OutputClockTracker(), var cursor: Int = 0, var queued: Int = 0, var underruns: Int = 0)
    private val streams = mutableListOf<Stream>()
    private var started = false
    private var closed = false
    private var positionFrames = 0

    /** Prepares and primes every endpoint; cancellation is checked before every acquisition/start.
     * @param routes Unique endpoint IDs with source routing and gain.
     * @param checkCurrent Throws when an explicit newer command or shutdown supersedes this work.
     */
    fun prepare(routes: List<PlaybackRoute>, checkCurrent: () -> Unit) {
        require(routes.size in 1..MAX_PLAYBACK_OUTPUTS && routes.map { it.endpointId }.distinct().size == routes.size)
        check(streams.isEmpty() && !closed)
        try {
            routes.forEach { route ->
                checkCurrent()
                val endpoint = devices.openEndpoint(route.endpointId, source.sampleRate)
                try {
                    require(endpoint.channels in 1..2 && endpoint.capacityFrames in 1..source.sampleRate * 2) { "Unsupported output buffer format." }
                    val delayFrames = outputDelayFrames(route.delayMillis, source.sampleRate)
                    streams += Stream(route.endpointId, endpoint, ByteArray(endpoint.capacityFrames * endpoint.channels * 4),
                        route.delayMillis, delayFrames, PcmDelayLine(delayFrames, endpoint.channels, endpoint.capacityFrames))
                } catch (failure: Throwable) { endpoint.close(); throw failure }
            }
            pump(routes)
            checkCurrent()
            resume(checkCurrent)
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Refills available capacity; an empty running buffer before EOF is an explicit underrun failure.
     * @param routes Latest settings for exactly the same endpoint set.
     * @return True once every stream has drained its final frame.
     */
    fun pump(routes: List<PlaybackRoute>): Boolean {
        check(!closed)
        require(routes.map { it.endpointId }.toSet() == streams.map { it.routeId }.toSet())
        var earliest = source.frames
        var completed = true
        streams.forEach { stream ->
            val queued = stream.endpoint.queuedFrames()
            check(queued in 0..minOf(stream.endpoint.capacityFrames, stream.cursor)) { "Windows reported an invalid playback buffer size." }
            stream.queued = queued
            val totalFrames = source.frames + stream.delayFrames
            if (started && queued == 0 && stream.cursor in 1 until totalFrames) {
                stream.underruns++
                error("Playback buffer underrun. The session stopped; press Play to restart.")
            }
            earliest = minOf(earliest, (stream.cursor - queued - stream.delayFrames).coerceIn(0, source.frames))
            val frames = minOf(stream.endpoint.capacityFrames - queued, totalFrames - stream.cursor)
            if (frames > 0) {
                val sourceFrames = minOf(frames, (source.frames - stream.cursor).coerceAtLeast(0))
                ChannelRenderer.render(source, minOf(stream.cursor, source.frames), sourceFrames, stream.endpoint.channels,
                    routes.first { it.endpointId == stream.routeId }, stream.bytes)
                stream.bytes.fill(0, sourceFrames * stream.endpoint.channels * 4, frames * stream.endpoint.channels * 4)
                stream.delay.process(stream.bytes, frames)
                stream.endpoint.submit(stream.bytes, frames)
                stream.cursor += frames
                stream.queued += frames
            }
            if (stream.cursor < totalFrames || queued + frames > 0) completed = false
        }
        positionFrames = maxOf(positionFrames, earliest)
        return completed
    }

    /** Source position based on the slowest endpoint's queued frames, not acoustic latency. */
    val positionMillis: Long get() = positionFrames * 1000L / source.sampleRate

    /** Refreshes native diagnostics independently of rendering, including while paused.
     * @param nowNanos Monotonic observation time, injected by tests where needed.
     * @param paused Whether stream consumption is intentionally frozen.
     * @return Copied portable measurements; native failures propagate for whole-session cleanup.
     */
    fun measureTimings(nowNanos: Long, paused: Boolean): List<OutputTiming> {
        check(!closed)
        streams.forEach { stream ->
            val queued = stream.endpoint.queuedFrames()
            check(queued in 0..minOf(stream.endpoint.capacityFrames, stream.cursor)) { "Invalid diagnostic queue size." }
            stream.queued = queued
            stream.clock.observe(stream.endpoint.clockReading(), nowNanos, stream.cursor * 1000.0 / source.sampleRate, paused)
        }
        return timingSnapshot()
    }

    /** Copies cached counts without further native calls, including an underrun immediately before failure.
     * @return Latest per-output counters/configuration; caller invalidates live values after closure.
     */
    fun timingSnapshot(): List<OutputTiming> = streams.map { stream ->
        OutputTiming(stream.routeId, source.sampleRate, stream.delayMillis, stream.delayFrames,
            stream.endpoint.capacityFrames, stream.cursor.toLong(), stream.queued,
            (stream.cursor - stream.queued - stream.delayFrames).coerceIn(0, source.frames).toLong(),
            stream.clock.value, stream.clock.message,
            stream.endpoint.streamLatencyMillis?.takeIf { it.isFinite() && it >= 0 }, stream.underruns)
    }

    /** Pauses all streams; any error is propagated for whole-session cleanup by the controller. */
    fun pause() { streams.forEach { it.endpoint.stop() }; started = false }

    /** Resumes prepared streams without discarding their queues.
     * @param checkCurrent Cancellation check before each Start; no claim of sample-synchronous start.
     */
    fun resume(checkCurrent: () -> Unit) {
        streams.forEach { checkCurrent(); it.endpoint.start() }
        started = true
    }

    /** Stops, resets and releases every endpoint exactly once, including partial initialization. */
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                val previous = failure
                if (previous == null) failure = problem else previous.addSuppressed(problem)
            }
        }
        streams.asReversed().forEach { stream ->
            cleanup { stream.endpoint.stop() }
            cleanup { stream.endpoint.reset() }
            cleanup { stream.endpoint.close() }
        }
        streams.clear()
        cleanup { devices.close() }
        failure?.let { throw it }
    }
}
