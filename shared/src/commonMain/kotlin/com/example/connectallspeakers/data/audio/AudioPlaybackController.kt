package com.example.connectallspeakers.data.audio

import com.example.connectallspeakers.model.audio.CaptureApp

import com.example.connectallspeakers.model.audio.PlaybackRoute
import com.example.connectallspeakers.model.audio.PlaybackSnapshot
import kotlinx.coroutines.flow.StateFlow

/** Platform-neutral file/live playback operations. The platform app owns native lifetime. */
interface AudioPlaybackController {
    /** Whether this platform implements selected-process capture; unsupported Windows versions fail explicitly. */
    val supportsAppCapture: Boolean get() = false
    /** Lists session-only app identities off the UI thread; does not open a capture stream. */
    suspend fun captureApps(): List<CaptureApp> = emptyList()
    /** Explicitly captures only the selected process tree; never changes the app's original output. */
    fun startAppCapture(app: CaptureApp, routes: List<PlaybackRoute>) {
        throw UnsupportedOperationException("Selected app capture is unavailable on this platform.")
    }
    /** True only for a platform assembly implementing actual owned-endpoint live capture. */
    val supportsVirtualInput: Boolean get() = false
    /** Current immutable lifecycle, source and failure state. */
    val playback: StateFlow<PlaybackSnapshot>

    /** Explicitly captures our virtual render endpoint and forwards to exact physical targets.
     * @param endpointId Driver-identified AllConnect source; never chosen by friendly name/default.
     * @param routes One or two independent physical outputs with user settings.
     * @throws UnsupportedOperationException On launchers without this capability; no sample fallback.
     */
    fun startVirtualInput(endpointId: String, routes: List<PlaybackRoute>) {
        throw UnsupportedOperationException("Virtual input is unavailable on this platform.")
    }

    /** Loads a user-selected local WAV or MP3 off the UI thread; never starts playback automatically.
     * @param path Native file path returned by the platform file picker.
     */
    fun loadFile(path: String)

    /** Starts at the beginning, or resumes a paused session with the same explicit endpoints.
     * @param routes One or two unique, available render endpoints with deliberate source mapping.
     * @param driftReferenceEndpointId Opt-in experimental two-output clock correction; null disables it.
     * The selected reference must have zero manual delay. Calibration cues/tests are unaffected.
     */
    fun play(routes: List<PlaybackRoute>, driftReferenceEndpointId: String? = null)

    /** Pauses file outputs for explicit Resume. Live input stops and discards its queues instead. */
    fun pause()

    /** Stops and releases all outputs; the loaded file remains available for replay from zero. */
    fun stop()

    /** Applies channel/gain edits and smoothed per-output EQ to subsequent buffers.
     * Unsupported EQ updates retain safe routes and report routeUpdateError. Delay edits stop for an explicit restart.
     * @param routes Current room routes, with no implicit endpoint replacement.
     */
    fun updateRoutes(routes: List<PlaybackRoute>)

    /** Requests generated manual-calibration cues on exactly two real outputs; preserves the loaded file.
     * @param routes Explicit endpoints with prepared delays and app gains.
     * @param referenceEndpointId One of these endpoints, with zero manual delay.
     */
    fun calibrate(routes: List<PlaybackRoute>, referenceEndpointId: String)

    /** Stops existing playback and plays a ten-second, -20 dBFS tone through only this output.
     * @param route Explicit real endpoint and app gain. No microphone or default output is used.
     */
    fun testSpeaker(route: PlaybackRoute)
}
