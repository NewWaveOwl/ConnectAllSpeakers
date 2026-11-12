package com.example.connectallspeakers.audio.wasapi

import com.example.connectallspeakers.audio.dsp.*
import com.example.connectallspeakers.audio.input.LiveCaptureSource
import com.example.connectallspeakers.audio.input.LivePcmConsumer
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import kotlin.math.floor

/** Owns continuous capture, bounded shared history and independent physical streams on one COM worker.
 * All native sources/targets are explicit. Idle/Stop discard backlog; no live recording is retained.
 * @param inputId Exact owned virtual endpoint, excluded from every target.
 * @param capture Owned capture lifetime; closed after physical endpoints even on failure.
 * @param devices Owned physical render lifetime.
 * @param monotonicNanos Monotonic clock; virtual in offline tests.
 */
internal class LiveRenderSession(private val inputId: String, private val capture: LiveCaptureSource,
    private val devices: PlaybackDeviceSession, private val monotonicNanos: () -> Long = System::nanoTime) : AutoCloseable, LivePcmConsumer {
    private class Stream(val id: String, val endpoint: PlaybackEndpoint, val route: PlaybackRoute) {
        val bytes = ByteArray(endpoint.capacityFrames * endpoint.channels * 4)
        val delayFrames = outputDelayFrames(route.delayMillis, LIVE_SAMPLE_RATE)
        var eq = OutputEqProcessor(LIVE_SAMPLE_RATE, route.eq)
        var delay = PcmDelayLine(delayFrames, endpoint.channels, endpoint.capacityFrames)
        var history = SourceFrameHistory(endpoint.capacityFrames + delayFrames + LIVE_SAMPLE_RATE * 2 + 4)
        var rate = LiveRateControl()
        var cursor = 0.0
        var submitted = 0L
        var queued = 0
        /** Resets processing only after the native stream has stopped and its queue was discarded. */
        fun reset() {
            eq = OutputEqProcessor(LIVE_SAMPLE_RATE, route.eq)
            delay = PcmDelayLine(delayFrames, endpoint.channels, endpoint.capacityFrames)
            history = SourceFrameHistory(endpoint.capacityFrames + delayFrames + LIVE_SAMPLE_RATE * 2 + 4)
            rate = LiveRateControl(); cursor = 0.0; submitted = 0; queued = 0
        }
    }
    private val buffer = LivePcmBuffer()
    private val streams = mutableListOf<Stream>()
    private var closed = false
    private var running = false
    private var lastPacketNanos: Long? = null
    private var packetObservationNanos = 0L
    private var packetObservationQpc = 0L
    private var startedNanos = 0L
    private var lastControlNanos = 0L
    private var sourceLagFrames = 0

    /** Opens all stopped physical streams; no output starts until sufficient fresh input is buffered.
     * @param routes One/two unique physical targets, never inputId.
     * @param checkCurrent Cancellation check before each native acquisition.
     */
    fun prepare(routes: List<PlaybackRoute>, checkCurrent: () -> Unit) {
        try {
            require(routes.size in 1..2 && routes.map { it.endpointId }.distinct().size == routes.size && routes.none { it.endpointId == inputId }) { "Choose one or two distinct physical targets; the virtual input cannot be a target." }
            check(streams.isEmpty() && !closed)
            routes.forEach { route ->
                route.eq.validateForSampleRate(LIVE_SAMPLE_RATE)
                checkCurrent()
                val endpoint = devices.openEndpoint(route.endpointId, LIVE_SAMPLE_RATE)
                try {
                    require(endpoint.channels in 1..2 && endpoint.capacityFrames in 1..LIVE_SAMPLE_RATE / 2) { "Unsupported live output buffer; maximum is 500 ms." }
                    streams += Stream(route.endpointId, endpoint, route)
                } catch (failure: Throwable) { endpoint.close(); throw failure }
            }
            sourceLagFrames = streams.maxOf { it.endpoint.capacityFrames } + LIVE_SAMPLE_RATE / 10 + 4
            checkCurrent()
            capture.start()
        } catch (failure: Throwable) { try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }; throw failure }
    }

    /** Accepts a borrowed native packet and links QPC to the local monotonic observation clock. */
    override fun accept(samples: FloatArray, frames: Int, deviceFrame: Long, qpc100Nanos: Long,
        now100Nanos: Long, discontinuity: Boolean, timestampError: Boolean) {
        buffer.append(samples, frames, deviceFrame, qpc100Nanos, now100Nanos, discontinuity, timestampError)
        packetObservationNanos = monotonicNanos()
        packetObservationQpc = now100Nanos
        lastPacketNanos = packetObservationNanos
    }

    /** Polls bounded capture and fills outputs. Lack of input is idle; native output starvation is an error.
     * @param routes Latest settings for the prepared targets; delay/membership changes require restart.
     * @param checkCurrent Cancellation check immediately before each native Start.
     */
    fun pump(routes: List<PlaybackRoute>, checkCurrent: () -> Unit) {
        check(!closed && streams.isNotEmpty())
        check(routes.map { it.endpointId }.toSet() == streams.map { it.id }.toSet()) { "Live targets changed; restart live input." }
        val now = monotonicNanos()
        if (running) check(now - lastControlNanos in 0L..250_000_000L) { "Live rendering stalled for more than 250 ms. Restart live input." }
        lastPacketNanos?.let { if (now - it > 100_000_000L) resetForIdle() }
        capture.poll(this)
        if (!running && buffer.writtenFrames < sourceLagFrames) return
        val controlNow = monotonicNanos()
        if (!running) {
            // Packet arrival is quantized. Start at the timestamp-derived common
            // lag, not frame zero of whichever packet happened to cross prebuffer.
            // Otherwise a normal packet-sized offset can saturate the slow servo.
            val initialCursor = inputPositionAt(controlNow) - sourceLagFrames
            if (initialCursor < 0) return
            streams.forEach { it.cursor = initialCursor }
        }
        val elapsed = if (running) (controlNow - lastControlNanos) / 1_000_000_000.0 else 0.0
        if (running) check(elapsed in 0.0..0.25) { "Live rendering stalled for more than 250 ms. Restart live input." }
        streams.forEach { stream ->
            val route = routes.first { it.endpointId == stream.id }
            check(route.delayMillis == stream.route.delayMillis) { "Live delay changed; restart live input." }
            val queued = stream.endpoint.queuedFrames()
            check(queued in 0..stream.endpoint.capacityFrames && queued <= stream.submitted) { "Invalid live output queue." }
            check(!running || queued > 0) { "Live native output underrun. Restart live input." }
            stream.queued = queued
            if (running && controlNow - startedNanos >= 2_000_000_000L && elapsed > 0) {
                val observedNanos = monotonicNanos()
                val played = stream.history.position((stream.submitted - queued - stream.delayFrames).toDouble())
                    ?: error("Live output history is unavailable. Restart live input.")
                val desired = inputPositionAt(observedNanos) - sourceLagFrames - stream.delayFrames
                stream.rate.update((desired - played) / LIVE_SAMPLE_RATE, elapsed)
            }
            stream.eq.request(route.eq)
            val frames = stream.endpoint.capacityFrames - queued
            val step = 1 + stream.rate.correctionPpm / 1_000_000
            var offset = 0
            repeat(frames) {
                check(buffer.canRead(stream.cursor)) { "Live source underflow. Restart live input." }
                val left = buffer.sample(stream.cursor, 0); val right = buffer.sample(stream.cursor, 1)
                val input = when (route.channel) {
                    SpeakerChannel.Left -> left; SpeakerChannel.Right -> right; SpeakerChannel.Mono -> (left + right) * .5f
                }
                val gained = (input * (route.volumePercent / 100f)).coerceIn(-1f, 1f)
                val bits = stream.eq.process(gained).toRawBits()
                repeat(stream.endpoint.channels) {
                    stream.bytes[offset++] = bits.toByte(); stream.bytes[offset++] = (bits ushr 8).toByte()
                    stream.bytes[offset++] = (bits ushr 16).toByte(); stream.bytes[offset++] = (bits ushr 24).toByte()
                }
                stream.cursor += step
                stream.history.append(stream.cursor)
            }
            if (frames > 0) {
                stream.delay.process(stream.bytes, frames)
                stream.endpoint.submit(stream.bytes, frames)
                stream.submitted += frames
                stream.queued += frames
            }
        }
        buffer.retainFrom(maxOf(0, floor(streams.minOf { it.cursor }).toLong() - 1))
        if (!running) {
            streams.forEach { checkCurrent(); it.endpoint.start() }
            running = true; startedNanos = controlNow
        }
        lastControlNanos = controlNow
    }

    /** Stops forwarding after an idle capture gap; fresh packets establish a new timeline. */
    private fun resetForIdle() {
        streams.forEach { it.endpoint.stop(); it.endpoint.reset(); it.reset() }
        buffer.clear(); running = false; lastPacketNanos = null
    }

    /** @param now Monotonic observation in ns. @return Source frame correlated through the last native QPC observation. */
    private fun inputPositionAt(now: Long): Double = buffer.positionAt(packetObservationQpc + (now - packetObservationNanos) / 100)

    /** @return Copied bounded-source status; no PCM/native handles cross to presentation. */
    fun status(): LiveInputStatus = LiveInputStatus(inputId, when {
        running -> LiveInputPhase.Forwarding
        buffer.writtenFrames > 0 -> LiveInputPhase.Buffering
        else -> LiveInputPhase.Idle
    }, buffer.retainedFrames, buffer.writtenFrames, streams.associate { it.id to it.rate.correctionPpm },
        if (running) "Forwarding virtual input. Queue-based rate control is experimental; acoustic alignment is unverified. Input samples limited: ${buffer.limitedSamples}."
        else "Waiting for fresh audio on AllConnect Speakers. No paused recording is retained.")

    /** @return Independent stream diagnostics; clocks are deliberately Unknown rather than inferred. */
    fun timingSnapshot(): List<OutputTiming> = streams.map { stream ->
        OutputTiming(stream.id, LIVE_SAMPLE_RATE, stream.route.delayMillis, stream.delayFrames,
            stream.endpoint.capacityFrames, stream.submitted, stream.queued,
            stream.history.position((stream.submitted - stream.queued - stream.delayFrames).toDouble())?.toLong(),
            clockMessage = "Live mode uses queue/source timestamps; native clock diagnostics unavailable",
            eq = stream.eq.snapshot())
    }

    /** Discards PCM and releases all native resources exactly once, even after partial initialization. */
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) { try { action() } catch (problem: Throwable) { failure?.addSuppressed(problem) ?: run { failure = problem } } }
        streams.asReversed().forEach { stream -> cleanup { stream.endpoint.stop() }; cleanup { stream.endpoint.reset() }; cleanup { stream.endpoint.close() }; stream.bytes.fill(0) }
        cleanup { devices.close() }; cleanup { capture.close() }; buffer.clear()
        failure?.let { throw it }
    }
}
