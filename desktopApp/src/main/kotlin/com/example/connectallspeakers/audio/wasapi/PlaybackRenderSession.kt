package com.example.connectallspeakers.audio.wasapi

import com.example.connectallspeakers.audio.dsp.ChannelRenderer
import com.example.connectallspeakers.audio.dsp.DecodedAudio
import com.example.connectallspeakers.audio.dsp.PcmDelayLine
import com.example.connectallspeakers.audio.dsp.AdaptivePcmRenderer
import com.example.connectallspeakers.audio.dsp.OutputChannelEq
import com.example.connectallspeakers.model.audio.MAX_PLAYBACK_OUTPUTS
import com.example.connectallspeakers.model.audio.PlaybackRoute
import com.example.connectallspeakers.model.audio.OutputTiming
import com.example.connectallspeakers.model.audio.outputDelayFrames

/** Owns all selected streams and their independent cursors on one thread.
 * All endpoints are prepared before starting any. Failure is atomic at the session boundary.
 * @param source Borrowed immutable PCM retained for this session.
 * @param devices Owned COM lifetime, released after endpoints even on partial initialization.
 * @param driftReferenceEndpointId Opt-in zero-delay reference for two-output file playback; null disables correction.
 * @param monotonicNanos Local clock for rejecting slow diagnostic batches; virtual in tests.
 */
internal class PlaybackRenderSession(private val source: DecodedAudio, private val devices: PlaybackDeviceSession,
    private val driftReferenceEndpointId: String? = null, private val monotonicNanos: () -> Long = System::nanoTime) : AutoCloseable {
    private data class Stream(val routeId: String, val endpoint: PlaybackEndpoint, val bytes: ByteArray,
        val delayMillis: Int, val delayFrames: Int, val delay: PcmDelayLine?, val adaptive: AdaptivePcmRenderer?,
        val eq: OutputChannelEq, val clock: OutputClockTracker = OutputClockTracker(), var cursor: Int = 0, var queued: Int = 0, var underruns: Int = 0,
        var nativeReading: NativeClockReading? = null)
    private val streams = mutableListOf<Stream>()
    private val drift = driftReferenceEndpointId?.let { DriftController(it, source.sampleRate) }
    private var started = false
    private var closed = false
    private var positionFrames = 0

    /** Prepares and primes every endpoint; cancellation is checked before every acquisition/start.
     * @param routes Unique endpoint IDs with source routing and gain.
     * @param checkCurrent Throws when an explicit newer command or shutdown supersedes this work.
     */
    fun prepare(routes: List<PlaybackRoute>, checkCurrent: () -> Unit) {
        require(routes.size in 1..MAX_PLAYBACK_OUTPUTS && routes.map { it.endpointId }.distinct().size == routes.size)
        require(driftReferenceEndpointId == null || routes.size == 2 && routes.any {
            it.endpointId == driftReferenceEndpointId && it.delayMillis == 0
        }) { "Drift correction needs two outputs and an explicit zero-delay reference." }
        check(streams.isEmpty() && !closed)
        try {
            routes.forEach { it.eq.validateForSampleRate(source.sampleRate) }
            routes.forEach { route ->
                checkCurrent()
                val endpoint = devices.openEndpoint(route.endpointId, source.sampleRate)
                try {
                    require(endpoint.channels in 1..2 && endpoint.capacityFrames in 1..source.sampleRate * 2) { "Unsupported output buffer format." }
                    val delayFrames = outputDelayFrames(route.delayMillis, source.sampleRate)
                    val eq = OutputChannelEq(source.sampleRate, route.eq, endpoint.channels)
                    val adaptive = if (drift != null && route.endpointId != driftReferenceEndpointId)
                        AdaptivePcmRenderer(source, endpoint.channels, endpoint.capacityFrames, delayFrames, eq) else null
                    streams += Stream(route.endpointId, endpoint, ByteArray(endpoint.capacityFrames * endpoint.channels * 4),
                        route.delayMillis, delayFrames, if (adaptive == null) PcmDelayLine(delayFrames, endpoint.channels, endpoint.capacityFrames) else null,
                        adaptive, eq)
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
            if (started && queued == 0 && stream.cursor > 0 && !finished(stream)) {
                stream.underruns++
                error("Playback buffer underrun. The session stopped; press Play to restart.")
            }
            earliest = minOf(earliest, checkNotNull(sourcePosition(stream, (stream.cursor - queued).toDouble())) {
                "Queued source history is unavailable; playback stopped."
            }.toInt())
            val route = routes.first { it.endpointId == stream.routeId }
            if (stream.adaptive == null && stream.cursor < source.frames) stream.eq.request(route.eq)
            val available = stream.endpoint.capacityFrames - queued
            val frames = stream.adaptive?.render(stream.bytes, available, route)
                ?: minOf(available, source.frames + stream.eq.tailFrames + stream.delayFrames - stream.cursor)
            if (frames > 0) {
                if (stream.adaptive == null) {
                    val sourceFrames = minOf(frames, (source.frames - stream.cursor).coerceAtLeast(0))
                    ChannelRenderer.render(source, minOf(stream.cursor, source.frames), sourceFrames, stream.endpoint.channels, route, stream.bytes)
                    stream.bytes.fill(0, sourceFrames * stream.endpoint.channels * 4, frames * stream.endpoint.channels * 4)
                    stream.eq.process(stream.bytes, frames, stream.endpoint.channels, sourceFrames)
                    stream.delay!!.process(stream.bytes, frames)
                }
                if (route.muted) stream.bytes.fill(0, 0, frames * stream.endpoint.channels * 4)
                stream.endpoint.submit(stream.bytes, frames)
                stream.cursor += frames
                stream.queued += frames
            }
            if (!finished(stream) || queued + frames > 0) completed = false
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
            stream.nativeReading = stream.endpoint.clockReading()
            stream.clock.observe(stream.nativeReading, nowNanos, stream.cursor * 1000.0 / source.sampleRate, paused)
        }
        if (drift != null && !paused) {
            val reference = streams.first { it.routeId == driftReferenceEndpointId }
            val follower = streams.first { it.routeId != driftReferenceEndpointId }
            when {
                streams.any { it.clock.discontinuityDetected } -> drift.observe(observation(reference), observation(follower), nowNanos)
                streams.any { it.adaptive?.sourceEnded ?: (it.cursor >= source.frames) } -> drift.drain()
                monotonicNanos() - nowNanos > 1_000_000_000 -> drift.unreliable(nowNanos, "Timing batch took over one second")
                else -> drift.observe(observation(reference), observation(follower), nowNanos)
            }
            follower.adaptive!!.setCorrection(drift.correctionPpm)
        }
        return timingSnapshot()
    }

    /** Copies cached counts without further native calls, including an underrun immediately before failure.
     * @return Latest per-output counters/configuration; caller invalidates live values after closure.
     */
    fun timingSnapshot(): List<OutputTiming> = streams.map { stream ->
        OutputTiming(stream.routeId, source.sampleRate, stream.delayMillis, stream.delayFrames,
            stream.endpoint.capacityFrames, stream.cursor.toLong(), stream.queued,
            sourcePosition(stream, (stream.cursor - stream.queued).toDouble())?.toLong(),
            stream.clock.value, stream.clock.message,
            stream.endpoint.streamLatencyMillis?.takeIf { it.isFinite() && it >= 0 }, stream.underruns,
            drift = drift?.snapshot(stream.routeId == driftReferenceEndpointId), eq = stream.eq.snapshot())
    }

    /** @param stream Prepared output. @return Whether its full source and manual-delay tail were submitted. */
    private fun finished(stream: Stream): Boolean = stream.adaptive?.finished ?: (stream.cursor == source.frames + stream.eq.tailFrames + stream.delayFrames)

    /** @param stream Prepared output. @param playedFrames Native/output-format frame boundary.
     * @return Source frames using actual historical interpolation ratios, or null outside retained history.
     */
    private fun sourcePosition(stream: Stream, playedFrames: Double): Double? = stream.adaptive?.sourcePosition(playedFrames)
        ?: if (stream.adaptive == null) (playedFrames - stream.delayFrames).coerceIn(0.0, source.frames.toDouble()) else null

    /** @param stream Prepared output. @return Clock and fractional source progress copied without extrapolating missing history. */
    private fun observation(stream: Stream): DriftObservation {
        val clock = stream.clock.value
        val frames = clock?.positionMillis?.let { it * source.sampleRate / 1000 }
        val position = if (frames != null && frames <= stream.cursor) sourcePosition(stream, frames) else null
        return DriftObservation(clock, position?.let { it * 1000 / source.sampleRate },
            stream.nativeReading?.observedQpc100Nanos, stream.clock.discontinuityDetected)
    }

    /** Pauses all streams; any error is propagated for whole-session cleanup by the controller. */
    fun pause() { streams.forEach { it.endpoint.stop() }; started = false; drift?.pause() }

    /** Resumes prepared streams without discarding their queues.
     * @param checkCurrent Cancellation check before each Start; no claim of sample-synchronous start.
     */
    fun resume(checkCurrent: () -> Unit) {
        drift?.rebaseline()
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
