package com.example.connectallspeakers.data.audio

import com.example.connectallspeakers.model.audio.PlaybackRoute
import com.example.connectallspeakers.model.audio.PlaybackSnapshot
import kotlinx.coroutines.flow.StateFlow

/** Platform-neutral local-file playback operations. The platform app owns native lifetime. */
interface AudioPlaybackController {
    /** Current immutable lifecycle, source and failure state. */
    val playback: StateFlow<PlaybackSnapshot>

    /** Loads a user-selected local WAV or MP3 off the UI thread; never starts playback automatically.
     * @param path Native file path returned by the platform file picker.
     */
    fun loadFile(path: String)

    /** Starts at the beginning, or resumes a paused session with the same explicit endpoints.
     * @param routes One or two unique, available render endpoints with deliberate source mapping.
     */
    fun play(routes: List<PlaybackRoute>)

    /** Pauses all outputs, retaining their queued frames for an explicit Resume action. */
    fun pause()

    /** Stops and releases all outputs; the loaded file remains available for replay from zero. */
    fun stop()

    /** Applies channel/gain edits to subsequent buffers. Changing endpoint membership stops playback.
     * @param routes Current room routes, with no implicit endpoint replacement.
     */
    fun updateRoutes(routes: List<PlaybackRoute>)

    /** Stops existing playback and plays a one-second, -20 dBFS tone through only this output.
     * @param route Explicit real endpoint and app gain. No microphone or default output is used.
     */
    fun testSpeaker(route: PlaybackRoute)
}
