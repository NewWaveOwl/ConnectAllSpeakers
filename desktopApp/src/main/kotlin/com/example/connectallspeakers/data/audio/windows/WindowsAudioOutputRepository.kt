package com.example.connectallspeakers.data.audio.windows

import com.example.connectallspeakers.audio.windows.CoreAudioDeviceBackend
import com.example.connectallspeakers.audio.windows.WindowsAudioBackend
import com.example.connectallspeakers.data.audio.AudioOutputRepository
import com.example.connectallspeakers.data.audio.AudioOutputSnapshot
import com.example.connectallspeakers.data.audio.AudioOutputStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Windows discovery owner with one COM thread and one bounded, coalescing refresh queue.
 * The application must call close then awaitClosed before exiting. No native work occurs on UI threads.
 */
class WindowsAudioOutputRepository internal constructor(
    private val backendFactory: () -> WindowsAudioBackend,
) : AudioOutputRepository, AutoCloseable {
    /** Creates the production Core Audio repository; JNA is loaded on its worker thread. */
    constructor() : this({ CoreAudioDeviceBackend() })

    private val mutableOutputs = MutableStateFlow(AudioOutputSnapshot())
    override val outputs: StateFlow<AudioOutputSnapshot> = mutableOutputs.asStateFlow()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val closed = AtomicBoolean(false)
    private val dispatcher = Executors.newSingleThreadExecutor { work -> Thread(work, "Windows-CoreAudio-discovery") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val worker = scope.launch {
        var backend: WindowsAudioBackend? = null
        try {
            requests.trySend(Unit)
            for (request in requests) {
                if (closed.get()) break
                mutableOutputs.value = mutableOutputs.value.copy(status = AudioOutputStatus.Loading, message = null)
                try {
                    if (backend == null) {
                        val candidate = backendFactory()
                        try { candidate.open { requests.trySend(Unit) } } catch (failure: Throwable) {
                            try { candidate.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                            throw failure
                        }
                        backend = candidate
                    }
                    val devices = backend.enumerate().distinctBy { it.endpointId }
                        .sortedWith(compareByDescending<com.example.connectallspeakers.model.audio.AudioOutputDevice> { it.isDefault }
                            .thenBy { it.displayName.lowercase() }.thenBy { it.endpointId })
                    mutableOutputs.value = AudioOutputSnapshot(AudioOutputStatus.Ready, devices,
                        revision = mutableOutputs.value.revision + 1)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    mutableOutputs.value = mutableOutputs.value.copy(status = AudioOutputStatus.Error,
                        message = "Could not read Windows playback outputs. ${failure.message.orEmpty()}")
                } catch (_: LinkageError) {
                    mutableOutputs.value = mutableOutputs.value.copy(status = AudioOutputStatus.Error,
                        message = "Windows audio integration could not be loaded. Refresh to retry.")
                }
            }
        } finally {
            try { backend?.close() } catch (failure: Exception) {
                mutableOutputs.value = mutableOutputs.value.copy(status = AudioOutputStatus.Error,
                    message = "Windows audio discovery cleanup failed. ${failure.message.orEmpty()}")
            }
        }
    }.also { job -> job.invokeOnCompletion { dispatcher.close(); scope.cancel() } }

    /** Queues manual refresh without waiting for COM or native enumeration. */
    override fun refresh() { if (!closed.get()) requests.trySend(Unit) }

    /** Begins idempotent cleanup; never blocks the caller or runs COM cleanup on the UI thread. */
    override fun close() { if (closed.compareAndSet(false, true)) requests.close() }

    /** Waits for notification unregistration and COM cleanup without blocking a UI thread. */
    suspend fun awaitClosed() { worker.join() }
}
