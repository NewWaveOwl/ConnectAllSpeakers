package com.example.connectallspeakers.data.audio.windows

import com.example.connectallspeakers.audio.dsp.AudioFileDecoder
import com.example.connectallspeakers.audio.dsp.DecodedAudio
import com.example.connectallspeakers.audio.dsp.LocalAudioFileDecoder
import com.example.connectallspeakers.audio.dsp.speakerTestTone
import com.example.connectallspeakers.audio.dsp.calibrationSignal
import com.example.connectallspeakers.audio.wasapi.PlaybackDeviceSession
import com.example.connectallspeakers.audio.wasapi.PlaybackRenderSession
import com.example.connectallspeakers.audio.wasapi.WasapiDeviceSession
import com.example.connectallspeakers.audio.wasapi.LiveRenderSession
import com.example.connectallspeakers.audio.input.LiveCaptureSource
import com.example.connectallspeakers.audio.input.windows.WasapiLoopbackCapture
import com.example.connectallspeakers.audio.input.windows.ProcessAudioCapture
import com.example.connectallspeakers.audio.input.windows.ProcessAudioApps
import com.example.connectallspeakers.data.audio.AudioOutputRepository
import com.example.connectallspeakers.data.audio.AudioOutputStatus
import com.example.connectallspeakers.data.audio.AudioPlaybackController
import com.example.connectallspeakers.model.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Explicit local-file playback and owned-input forwarding on a dedicated COM worker; construction is silent.
 * Commands are conflated and versioned so Stop/close cancels stale decoding or stream preparation.
 * @param outputs Discovery state used to validate exact endpoint identities and detect removal.
 * @param decoder Bounded local-file decoder; tests inject generated PCM without file/device access.
 * @param deviceFactory Lazy native lifetime factory, called only for explicit playback requests.
 * @param dispatcher Serial, thread-confined worker; production owns its dedicated executor.
 * @param disposeDispatcher Releases the owned executor after native cleanup, or a no-op in tests.
 * @param monotonicNanos Monotonic local observation clock; tests can inject virtual time.
 * @param liveCaptureFactory Lazy exact-owned-endpoint capture factory; never invoked at construction.
 * @param processCaptureFactory Lazy selected-process capture factory; tests inject PCM without OS capture.
 */
class WindowsAudioPlaybackController internal constructor(
    private val outputs: AudioOutputRepository,
    private val decoder: AudioFileDecoder,
    private val deviceFactory: () -> PlaybackDeviceSession,
    private val dispatcher: CoroutineDispatcher,
    private val disposeDispatcher: () -> Unit,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val liveCaptureFactory: (String) -> LiveCaptureSource = { WasapiLoopbackCapture(it) },
    private val processCaptureFactory: (CaptureApp) -> LiveCaptureSource = { ProcessAudioCapture(it) },
) : AudioPlaybackController, AutoCloseable {
    /** Production assembly. Native APIs remain lazy until an explicit playback/test/calibration action.
     * @param outputs Application-owned Windows discovery repository, outliving this controller.
     */
    constructor(outputs: AudioOutputRepository) : this(outputs, LocalAudioFileDecoder(), { WasapiDeviceSession() }, newWorker())

    private constructor(outputs: AudioOutputRepository, decoder: AudioFileDecoder,
        deviceFactory: () -> PlaybackDeviceSession, worker: ExecutorCoroutineDispatcher) :
        this(outputs, decoder, deviceFactory, worker, { worker.close() })

    private data class Command(val epoch: Long, val kind: Kind, val path: String? = null,
        val routes: List<PlaybackRoute> = emptyList(), val referenceEndpointId: String? = null, val inputEndpointId: String? = null,
        val processApp: CaptureApp? = null)
    private enum class Kind { Load, Play, Pause, Stop, Test, Calibrate, Live }
    private val mutablePlayback = MutableStateFlow(PlaybackSnapshot(PlaybackPhase.Empty,
        message = "Choose a local WAV or MP3 file. Playback starts only when you press Play."))
    override val playback = mutablePlayback.asStateFlow()
    override val supportsVirtualInput: Boolean = true
    override val supportsAppCapture: Boolean = true
    override suspend fun captureApps(): List<CaptureApp> = withContext(Dispatchers.IO) { ProcessAudioApps.list() }
    override fun startAppCapture(app: CaptureApp, routes: List<PlaybackRoute>) {
        if (closed.get()) return
        val snapshot = routes.toList()
        liveRoutes.set(snapshot)
        val epoch = generation.incrementAndGet()
        commands.trySend(Command(epoch, Kind.Live, routes = snapshot, processApp = app))
    }
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val liveRoutes = AtomicReference<List<PlaybackRoute>>(emptyList())
    private val routeUpdateError = AtomicReference<String?>(null)
    private val commands = Channel<Command>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var source: DecodedAudio? = null
    private var session: PlaybackRenderSession? = null
    private var liveSession: LiveRenderSession? = null
    private var liveInputId: String? = null
    private var liveApp: CaptureApp? = null
    private var liveEpoch = 0L
    private var sessionIds: Set<String> = emptySet()
    private var sessionDriftReference: String? = null
    private var testing = false
    private var calibrating = false
    private var paused = false
    private var lastTimings: List<OutputTiming> = emptyList()
    private var timingSequence = 0L
    private var nextMeasurementNanos = Long.MIN_VALUE
    private val worker = scope.launch {
        try {
            while (!closed.get()) {
                val command = if (session != null || liveSession != null) withTimeoutOrNull(if (paused) 50 else 5) { commands.receiveCatching().getOrNull() }
                    else commands.receiveCatching().getOrNull()
                if (closed.get()) break
                if (command != null && command.epoch == generation.get()) handle(command)
                val active = session
                if (active != null) {
                    try {
                        checkConnected()
                        if (!paused) {
                            if (active.pump(liveRoutes.get())) {
                                val wasTesting = testing
                                val wasCalibrating = calibrating
                                val duration = if (testing || calibrating) 0 else mutablePlayback.value.source?.durationMillis ?: 0
                                releaseSession()
                                publish(PlaybackPhase.Completed, when {
                                    wasTesting -> "Test tone finished."
                                    wasCalibrating -> "Calibration cues finished. Compare the sound, adjust delay, then replay."
                                    else -> "Playback finished."
                                }, duration)
                            } else {
                                mutablePlayback.value = mutablePlayback.value.copy(positionMillis = active.positionMillis)
                            }
                        }
                        if (session != null && monotonicNanos() >= nextMeasurementNanos) {
                            val now = monotonicNanos()
                            val measured = active.measureTimings(now, paused)
                            val finished = monotonicNanos()
                            lastTimings = if (finished - now > TIMING_STALE_AFTER_MILLIS * 1_000_000) measured.map {
                                it.unavailable("Timing sample took too long; measurements Unknown")
                            } else measured
                            timingSequence++
                            nextMeasurementNanos = finished + 200_000_000L
                            val driftStatus = lastTimings.firstOrNull { it.drift?.isReference == false }?.drift
                            mutablePlayback.value = mutablePlayback.value.copy(outputTimings = lastTimings, timingSequence = timingSequence,
                                message = driftStatus?.let { "Experimental drift: ${it.phase} · ${it.message}" } ?: mutablePlayback.value.message)
                        }
                    } catch (failure: Exception) { failSession(failure) }
                }
                liveSession?.let { live ->
                    try {
                        checkConnected()
                        live.pump(liveRoutes.get()) { checkCurrent(liveEpoch) }
                        if (monotonicNanos() >= nextMeasurementNanos) {
                            lastTimings = live.timingSnapshot()
                            timingSequence++
                            nextMeasurementNanos = monotonicNanos() + 200_000_000L
                            publish(PlaybackPhase.Playing, live.status().message.orEmpty())
                        }
                    } catch (_: CancellationException) {
                        try { releaseSession() } catch (cleanup: Exception) { failSession(cleanup) }
                    } catch (failure: Exception) { failSession(failure) }
                }
            }
        } finally {
            try { releaseSession() } finally { scope.cancel(); disposeDispatcher() }
        }
    }

    /** @param path User-selected local WAV/MP3 path. Loading closes the current session; never auto-plays. */
    override fun loadFile(path: String) { enqueue(Kind.Load, path = path) }
    /** @param routes One or two unique explicitly selected, currently active render endpoints.
     * @param driftReferenceEndpointId Optional zero-delay reference for experimental two-output file correction.
     */
    override fun play(routes: List<PlaybackRoute>, driftReferenceEndpointId: String?) {
        if (closed.get()) return
        val snapshot = routes.toList()
        liveRoutes.set(snapshot)
        enqueue(Kind.Play, routes = snapshot, referenceEndpointId = driftReferenceEndpointId)
    }
    /** Starts forwarding only the explicitly selected owned input; no default-device capture exists.
     * @param endpointId Driver-marked virtual render endpoint. @param routes Physical destinations.
     */
    override fun startVirtualInput(endpointId: String, routes: List<PlaybackRoute>) {
        if (closed.get()) return
        val snapshot = routes.toList()
        liveRoutes.set(snapshot)
        enqueue(Kind.Live, routes = snapshot, inputEndpointId = endpointId)
    }
    /** Pauses the existing buffers; the next Play resumes them. */
    override fun pause() { enqueue(Kind.Pause) }
    /** Cancels pending work and stops every stream; the loaded file stays available at position zero. */
    override fun stop() { enqueue(Kind.Stop) }
    /** Updates gain/channel/EQ without a command per slider tick; delay changes stop for explicit restart.
     * Rejects incompatible source-rate EQ while retaining all previously safe routes.
     * @param routes Latest selected room routes. A different endpoint set requires an explicit new Play.
     */
    override fun updateRoutes(routes: List<PlaybackRoute>) {
        if (closed.get()) return
        val snapshot = routes.toList()
        try {
            mutablePlayback.value.source?.sampleRate?.let { rate -> snapshot.forEach { it.eq.validateForSampleRate(rate) } }
        } catch (invalid: IllegalArgumentException) {
            val message = invalid.message ?: "EQ update rejected; previous settings remain active."
            routeUpdateError.set(message)
            mutablePlayback.update { it.copy(routeUpdateError = message) }
            return
        }
        routeUpdateError.set(null)
        mutablePlayback.update { it.copy(routeUpdateError = null) }
        val previous = liveRoutes.getAndSet(snapshot)
        if (snapshot.any { next -> previous.any { it.endpointId == next.endpointId && (it.delayMillis != next.delayMillis || it.mix != next.mix) } }) stop()
    }

    /** @param routes Two real routes. @param referenceEndpointId Zero-delay reference from those routes. */
    override fun calibrate(routes: List<PlaybackRoute>, referenceEndpointId: String) {
        if (closed.get()) return
        val snapshot = routes.toList()
        liveRoutes.set(snapshot)
        enqueue(Kind.Calibrate, routes = snapshot, referenceEndpointId = referenceEndpointId)
    }
    /** Stops any file and plays a bounded ten-second -20 dBFS tone only on the explicit route.
     * @param route Exact endpoint, channel and gain. No default-output fallback exists.
     */
    override fun testSpeaker(route: PlaybackRoute) {
        if (closed.get()) return
        val snapshot = listOf(route)
        liveRoutes.set(snapshot)
        enqueue(Kind.Test, routes = snapshot)
    }

    /** Invalidates stale work immediately; all native cleanup runs on the owner worker. */
    override fun close() {
        if (closed.compareAndSet(false, true)) { generation.incrementAndGet(); commands.close() }
    }
    /** Suspends until all native objects and COM initialization have been released. */
    suspend fun awaitClosed() { worker.join() }

    /** @param kind Latest user command. @param path Optional local source.
     * @param routes Explicit routing snapshot. @param referenceEndpointId Optional zero-delay calibration reference.
     * @param inputEndpointId Explicit owned live-source identity, separate from a local file path.
     */
    private fun enqueue(kind: Kind, path: String? = null, routes: List<PlaybackRoute> = emptyList(), referenceEndpointId: String? = null,
        inputEndpointId: String? = null) {
        if (closed.get()) return
        commands.trySend(Command(generation.incrementAndGet(), kind, path, routes, referenceEndpointId, inputEndpointId))
    }

    /** Executes a current command, converting failures to durable UI state without swallowing cancellation.
     * @param command Versioned user request.
     */
    private fun handle(command: Command) {
        try {
            when (command.kind) {
                Kind.Load -> {
                    releaseSession()
                    routeUpdateError.set(null)
                    lastTimings = emptyList()
                    publish(PlaybackPhase.Loading, "Reading audio file…")
                    val decoded = decoder.decode(requireNotNull(command.path)) { stale(command.epoch) }
                    checkCurrent(command.epoch)
                    source = decoded
                    publish(PlaybackPhase.Ready, "Ready. Select outputs and press Play.")
                }
                Kind.Play -> {
                    validate(command.routes)
                    val rate = source?.sampleRate
                    rate?.let { processingRate -> command.routes.forEach { it.eq.validateForSampleRate(processingRate) } }
                    require(command.referenceEndpointId == null || command.routes.size == 2 && command.routes.any {
                        it.endpointId == command.referenceEndpointId && it.delayMillis == 0
                    }) { "Drift correction needs two outputs and an explicit zero-delay reference." }
                    if (session != null && paused && !testing && !calibrating && command.routes.map { it.endpointId }.toSet() == sessionIds &&
                        command.referenceEndpointId == sessionDriftReference &&
                        command.routes.all { route -> lastTimings.any { it.endpointId == route.endpointId && it.delayMillis == route.delayMillis } }) {
                        session!!.resume { checkCurrent(command.epoch) }
                        paused = false
                        publish(PlaybackPhase.Playing, playingMessage(), session!!.positionMillis)
                    } else start(command, source ?: error("Choose a WAV or MP3 file first."), false)
                }
                Kind.Test -> start(command, speakerTestTone(), true)
                Kind.Live -> startLive(command)
                Kind.Calibrate -> {
                    require(command.routes.size == 2 && command.routes.any { it.endpointId == command.referenceEndpointId && it.delayMillis == 0 }) {
                        "Calibration needs two outputs and a zero-delay reference."
                    }
                    start(command, calibrationSignal(), false, true)
                }
                Kind.Pause -> if (session != null) {
                    session!!.pause()
                    paused = true
                    nextMeasurementNanos = Long.MIN_VALUE
                    publish(PlaybackPhase.Paused, "Paused.", session!!.positionMillis)
                } else {
                    releaseSession()
                    publish(if (source == null) PlaybackPhase.Empty else PlaybackPhase.Stopped, "Stopped. Live input cannot be paused or recorded.")
                }
                Kind.Stop -> { releaseSession(); publish(if (source == null) PlaybackPhase.Empty else PlaybackPhase.Stopped, "Stopped.") }
            }
        } catch (_: CancellationException) {
            try { releaseSession() } catch (cleanup: Exception) { failSession(cleanup) }
        } catch (failure: Exception) {
            if (!stale(command.epoch)) failSession(failure) else releaseSession()
        }
    }

    /** Prepares every endpoint before starting any; stale preparation is closed before Start.
     * @param command Explicit routes and cancellation version.
     * @param audio Owned loaded file or temporary generated tone.
     * @param test Whether this is an explicit tone, leaving the loaded file unchanged.
     * @param calibration Whether generated manual-calibration cues replace the file temporarily.
     */
    private fun start(command: Command, audio: DecodedAudio, test: Boolean, calibration: Boolean = false) {
        releaseSession()
        validate(command.routes)
        command.routes.forEach { it.eq.validateForSampleRate(audio.sampleRate) }
        routeUpdateError.set(null)
        checkCurrent(command.epoch)
        testing = test
        calibrating = calibration
        lastTimings = emptyList()
        nextMeasurementNanos = Long.MIN_VALUE
        sessionIds = command.routes.map { it.endpointId }.toSet()
        sessionDriftReference = command.referenceEndpointId.takeIf { command.kind == Kind.Play }
        mutablePlayback.value = PlaybackSnapshot(PlaybackPhase.Starting, audio.description,
            activeEndpointIds = sessionIds, isTestTone = test, isCalibration = calibration, message = "Opening selected outputs…")
        val prepared = PlaybackRenderSession(audio, deviceFactory(), sessionDriftReference, monotonicNanos)
        session = prepared
        prepared.prepare(liveRoutes.get()) {
            checkCurrent(command.epoch)
            check(liveRoutes.get().map { it.endpointId }.toSet() == sessionIds) { "Room outputs changed before playback started." }
            validate(command.routes)
        }
        paused = false
        publish(PlaybackPhase.Playing, when {
            test -> "Test tone · ${SPEAKER_TEST_DURATION_MILLIS / 1000} seconds. Press Stop to end early."
            calibration -> "Calibration: 3-second warmup, then one pulse per second. Engine timing is not acoustic latency."
            else -> playingMessage()
        })
    }

    /** Builds a new bounded live lifetime; cancellation/partial failure closes both capture and targets.
     * @param command Exact input identity and physical routes from one explicit Start action.
     */
    private fun startLive(command: Command) {
        releaseSession()
        validate(command.routes)
        val app = command.processApp
        val input = app?.let { "process:${it.processId}:${it.startedAtMillis}" } ?: requireNotNull(command.inputEndpointId)
        if (app == null) validateInput(input)
        command.routes.forEach { it.eq.validateForSampleRate(LIVE_SAMPLE_RATE) }
        checkCurrent(command.epoch)
        routeUpdateError.set(null)
        lastTimings = emptyList()
        liveInputId = input.takeIf { app == null }
        liveApp = app
        liveEpoch = command.epoch
        sessionIds = command.routes.map { it.endpointId }.toSet()
        nextMeasurementNanos = Long.MIN_VALUE
        publish(PlaybackPhase.Starting, if (app == null) "Opening AllConnect virtual input and selected outputs…" else "Opening selected app audio and outputs…")
        val capture = if (app == null) liveCaptureFactory(input) else processCaptureFactory(app)
        val devices = try { deviceFactory() } catch (failure: Throwable) {
            try { capture.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }; throw failure
        }
        val prepared = LiveRenderSession(input, capture, devices, monotonicNanos)
        liveSession = prepared
        prepared.prepare(command.routes) { checkCurrent(command.epoch); checkConnected(); if (app == null) validateInput(input) }
        publish(PlaybackPhase.Playing, if (app == null) "Waiting for audio on AllConnect Speakers." else "Waiting for selected app audio.")
    }

    /** @param id Exact driver-owned render endpoint; names alone never establish ownership. */
    private fun validateInput(id: String) {
        val discovery = outputs.outputs.value
        check(discovery.status == AudioOutputStatus.Ready && discovery.devices.any {
            it.endpointId == id && it.role == AudioOutputRole.AllConnectInput && it.availability == OutputAvailability.Active
        }) { "AllConnect virtual input is unavailable. Install the reviewed driver, refresh, then explicitly Start again." }
    }

    /** Rejects invalid routes and stale discovery rather than sending audio elsewhere.
     * @param routes Exact requested endpoint snapshots.
     */
    private fun validate(routes: List<PlaybackRoute>) {
        require(routes.size in 1..MAX_PLAYBACK_OUTPUTS) { "Choose one or two real outputs for playback." }
        require(routes.map { it.endpointId }.distinct().size == routes.size) { "Each playback output must be unique." }
        val discovery = outputs.outputs.value
        check(discovery.status == AudioOutputStatus.Ready) { "Refresh outputs successfully before starting playback." }
        check(routes.all { route -> discovery.devices.singleOrNull { it.endpointId == route.endpointId }?.let { it.availability == OutputAvailability.Active && it.role == AudioOutputRole.PlaybackTarget } == true }) {
            "A selected output is unavailable. Reconnect it, refresh, then press Play."
        }
    }

    /** Successful discovery may prove removal; loading/errors do not imply a disconnect. */
    private fun checkConnected() {
        val discovery = outputs.outputs.value
        if (discovery.status == AudioOutputStatus.Ready) {
            check(sessionIds.all { id -> discovery.devices.singleOrNull { it.endpointId == id }?.let { it.availability == OutputAvailability.Active && it.role == AudioOutputRole.PlaybackTarget } == true }) {
                "A selected output disconnected. Playback stopped; reconnect it and press Play."
            }
            liveInputId?.let { validateInput(it) }
        }
        check(liveRoutes.get().map { it.endpointId }.toSet() == sessionIds) { "Room outputs changed. Press Play to start the new routing." }
    }

    /** @param epoch Command version. @return True if a newer command/shutdown superseded it. */
    private fun stale(epoch: Long): Boolean = closed.get() || generation.get() != epoch
    /** @param epoch Command version. @throws CancellationException Before stale work can start audio. */
    private fun checkCurrent(epoch: Long) { if (stale(epoch)) throw CancellationException("Playback command superseded") }

    /** Closes even a paused or partly prepared session and clears routing identity. */
    private fun releaseSession() {
        val previous = session
        val previousLive = liveSession
        if (previous != null) lastTimings = previous.timingSnapshot().map { it.unavailable("Session ended; live timing unavailable") }
        if (previousLive != null) lastTimings = previousLive.timingSnapshot().map { it.unavailable("Session ended; live timing unavailable") }
        session = null
        liveSession = null
        liveInputId = null
        liveApp = null
        sessionIds = emptySet()
        sessionDriftReference = null
        paused = false
        testing = false
        calibrating = false
        try { previous?.close() } finally { previousLive?.close() }
    }

    /** @return Explicit experimental/uncalibrated status; clocks never establish acoustic alignment. */
    private fun playingMessage(): String = if (sessionDriftReference != null)
        "Playing · experimental clock drift correction enabled; inspect engine timing. Acoustic alignment is unverified."
        else "Playing · manual delay applied; drift correction off; acoustic timing is unverified."

    /** @param failure Sanitized backend/source failure; native errors contain no endpoint identifiers. */
    private fun failSession(failure: Exception) {
        var message = failure.message ?: "Playback failed. Stop and try again."
        try { releaseSession() } catch (_: Exception) { message += " Cleanup also reported an error." }
        publish(PlaybackPhase.Error, message)
    }

    /** Publishes durable state; source paths and native pointers never escape.
     * @param phase New lifecycle state. @param message Readable feedback. @param position Source milliseconds.
     */
    private fun publish(phase: PlaybackPhase, message: String, position: Long = 0) {
        mutablePlayback.value = PlaybackSnapshot(phase,
            if (liveApp != null) PlaybackSource("${liveApp!!.name} · app audio", 0, LIVE_SAMPLE_RATE, LIVE_CHANNELS)
            else if (liveInputId != null) PlaybackSource("AllConnect Speakers · live", 0, LIVE_SAMPLE_RATE, LIVE_CHANNELS)
            else if (testing || calibrating) mutablePlayback.value.source else source?.description, position, sessionIds.toSet(), testing, message,
            outputTimings = lastTimings, timingSequence = timingSequence, isCalibration = calibrating,
            routeUpdateError = routeUpdateError.get(), liveInput = liveSession?.status())
    }

    companion object {
        /** @return A daemon worker; closing the controller shuts down its executor. */
        private fun newWorker(): ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "allconnect-wasapi").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
