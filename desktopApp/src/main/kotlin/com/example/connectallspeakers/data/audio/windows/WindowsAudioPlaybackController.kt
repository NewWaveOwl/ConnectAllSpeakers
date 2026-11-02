package com.example.connectallspeakers.data.audio.windows

import com.example.connectallspeakers.audio.dsp.AudioFileDecoder
import com.example.connectallspeakers.audio.dsp.DecodedAudio
import com.example.connectallspeakers.audio.dsp.WavFileDecoder
import com.example.connectallspeakers.audio.dsp.speakerTestTone
import com.example.connectallspeakers.audio.wasapi.PlaybackDeviceSession
import com.example.connectallspeakers.audio.wasapi.PlaybackRenderSession
import com.example.connectallspeakers.audio.wasapi.WasapiDeviceSession
import com.example.connectallspeakers.data.audio.AudioOutputRepository
import com.example.connectallspeakers.data.audio.AudioOutputStatus
import com.example.connectallspeakers.data.audio.AudioPlaybackController
import com.example.connectallspeakers.model.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Explicit local-file playback on a dedicated COM worker; construction never opens an audio stream.
 * Commands are conflated and versioned so Stop/close cancels stale decoding or stream preparation.
 * @param outputs Discovery state used to validate exact endpoint identities and detect removal.
 * @param decoder Bounded local-file decoder; tests inject generated PCM without file/device access.
 * @param deviceFactory Lazy native lifetime factory, called only for explicit playback requests.
 * @param dispatcher Serial, thread-confined worker; production owns its dedicated executor.
 * @param disposeDispatcher Releases the owned executor after native cleanup, or a no-op in tests.
 */
class WindowsAudioPlaybackController internal constructor(
    private val outputs: AudioOutputRepository,
    private val decoder: AudioFileDecoder,
    private val deviceFactory: () -> PlaybackDeviceSession,
    private val dispatcher: CoroutineDispatcher,
    private val disposeDispatcher: () -> Unit,
) : AudioPlaybackController, AutoCloseable {
    /** Production assembly. Native APIs remain lazy until explicit Play/Test.
     * @param outputs Application-owned Windows discovery repository, outliving this controller.
     */
    constructor(outputs: AudioOutputRepository) : this(outputs, WavFileDecoder(), { WasapiDeviceSession() }, newWorker())

    private constructor(outputs: AudioOutputRepository, decoder: AudioFileDecoder,
        deviceFactory: () -> PlaybackDeviceSession, worker: ExecutorCoroutineDispatcher) :
        this(outputs, decoder, deviceFactory, worker, { worker.close() })

    private data class Command(val epoch: Long, val kind: Kind, val path: String? = null,
        val routes: List<PlaybackRoute> = emptyList())
    private enum class Kind { Load, Play, Pause, Stop, Test }
    private val mutablePlayback = MutableStateFlow(PlaybackSnapshot(PlaybackPhase.Empty,
        message = "Choose a local WAV file. Playback starts only when you press Play."))
    override val playback = mutablePlayback.asStateFlow()
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val liveRoutes = AtomicReference<List<PlaybackRoute>>(emptyList())
    private val commands = Channel<Command>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var source: DecodedAudio? = null
    private var session: PlaybackRenderSession? = null
    private var sessionIds: Set<String> = emptySet()
    private var testing = false
    private var paused = false
    private val worker = scope.launch {
        try {
            while (!closed.get()) {
                val command = if (session != null) withTimeoutOrNull(if (paused) 50 else 5) { commands.receiveCatching().getOrNull() }
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
                                val duration = if (testing) 0 else mutablePlayback.value.source?.durationMillis ?: 0
                                releaseSession()
                                publish(PlaybackPhase.Completed, if (wasTesting) "Test tone finished." else "Playback finished.", duration)
                            } else {
                                mutablePlayback.value = mutablePlayback.value.copy(positionMillis = active.positionMillis)
                            }
                        }
                    } catch (failure: Exception) { failSession(failure) }
                }
            }
        } finally {
            try { releaseSession() } finally { scope.cancel(); disposeDispatcher() }
        }
    }

    /** @param path User-selected local WAV path. Loading closes the current session; never auto-plays. */
    override fun loadFile(path: String) { enqueue(Kind.Load, path = path) }
    /** @param routes One or two unique explicitly selected, currently active render endpoints. */
    override fun play(routes: List<PlaybackRoute>) {
        if (closed.get()) return
        val snapshot = routes.toList()
        liveRoutes.set(snapshot)
        enqueue(Kind.Play, routes = snapshot)
    }
    /** Pauses the existing buffers; the next Play resumes them. */
    override fun pause() { enqueue(Kind.Pause) }
    /** Cancels pending work and stops every stream; the loaded file stays available at position zero. */
    override fun stop() { enqueue(Kind.Stop) }
    /** Updates gain/channel without a command per slider tick; membership changes stop the session.
     * @param routes Latest selected room routes. A different endpoint set requires an explicit new Play.
     */
    override fun updateRoutes(routes: List<PlaybackRoute>) { liveRoutes.set(routes.toList()) }
    /** Stops any file and plays a bounded one-second -20 dBFS tone only on the explicit route.
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

    /** @param kind Latest user command. @param path Optional local source. @param routes Explicit routing snapshot. */
    private fun enqueue(kind: Kind, path: String? = null, routes: List<PlaybackRoute> = emptyList()) {
        if (closed.get()) return
        commands.trySend(Command(generation.incrementAndGet(), kind, path, routes))
    }

    /** Executes a current command, converting failures to durable UI state without swallowing cancellation.
     * @param command Versioned user request.
     */
    private fun handle(command: Command) {
        try {
            when (command.kind) {
                Kind.Load -> {
                    releaseSession()
                    publish(PlaybackPhase.Loading, "Reading WAV file…")
                    val decoded = decoder.decode(requireNotNull(command.path)) { stale(command.epoch) }
                    checkCurrent(command.epoch)
                    source = decoded
                    publish(PlaybackPhase.Ready, "Ready. Select outputs and press Play.")
                }
                Kind.Play -> {
                    validate(command.routes)
                    if (session != null && paused && !testing && command.routes.map { it.endpointId }.toSet() == sessionIds) {
                        session!!.resume { checkCurrent(command.epoch) }
                        paused = false
                        publish(PlaybackPhase.Playing, "Playing · timing is not calibrated.", session!!.positionMillis)
                    } else start(command, source ?: error("Choose a WAV file first."), false)
                }
                Kind.Test -> start(command, speakerTestTone(), true)
                Kind.Pause -> if (session != null) {
                    session!!.pause()
                    paused = true
                    publish(PlaybackPhase.Paused, "Paused.", session!!.positionMillis)
                } else publish(if (source == null) PlaybackPhase.Empty else PlaybackPhase.Stopped, "No active playback to pause.")
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
     */
    private fun start(command: Command, audio: DecodedAudio, test: Boolean) {
        releaseSession()
        validate(command.routes)
        checkCurrent(command.epoch)
        testing = test
        sessionIds = command.routes.map { it.endpointId }.toSet()
        mutablePlayback.value = PlaybackSnapshot(PlaybackPhase.Starting, audio.description,
            activeEndpointIds = sessionIds, isTestTone = test, message = "Opening selected outputs…")
        val prepared = PlaybackRenderSession(audio, deviceFactory())
        session = prepared
        prepared.prepare(liveRoutes.get()) {
            checkCurrent(command.epoch)
            check(liveRoutes.get().map { it.endpointId }.toSet() == sessionIds) { "Room outputs changed before playback started." }
            validate(command.routes)
        }
        paused = false
        publish(PlaybackPhase.Playing, if (test) "Test tone · one second." else "Playing · timing is not calibrated.")
    }

    /** Rejects invalid routes and stale discovery rather than sending audio elsewhere.
     * @param routes Exact requested endpoint snapshots.
     */
    private fun validate(routes: List<PlaybackRoute>) {
        require(routes.size in 1..MAX_PLAYBACK_OUTPUTS) { "Choose one or two real outputs for playback." }
        require(routes.map { it.endpointId }.distinct().size == routes.size) { "Each playback output must be unique." }
        val discovery = outputs.outputs.value
        check(discovery.status == AudioOutputStatus.Ready) { "Refresh outputs successfully before starting playback." }
        check(routes.all { route -> discovery.devices.any { it.endpointId == route.endpointId && it.availability == OutputAvailability.Active } }) {
            "A selected output is unavailable. Reconnect it, refresh, then press Play."
        }
    }

    /** Successful discovery may prove removal; loading/errors do not imply a disconnect. */
    private fun checkConnected() {
        val discovery = outputs.outputs.value
        if (discovery.status == AudioOutputStatus.Ready) {
            check(sessionIds.all { id -> discovery.devices.any { it.endpointId == id && it.availability == OutputAvailability.Active } }) {
                "A selected output disconnected. Playback stopped; reconnect it and press Play."
            }
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
        session = null
        sessionIds = emptySet()
        paused = false
        testing = false
        previous?.close()
    }

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
            if (testing) mutablePlayback.value.source else source?.description, position, sessionIds.toSet(), testing, message)
    }

    companion object {
        /** @return A daemon worker; closing the controller shuts down its executor. */
        private fun newWorker(): ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "allconnect-wasapi").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }
}
