package com.example.connectallspeakers.ViewModels.main

import com.example.connectallspeakers.model.RoomPosition
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.state.MainDialog

/** User intents emitted by stateless rendering; platform audio executes behind a shared contract. */
sealed interface MainAction {
    /** @property id Existing room to activate. */
    data class SelectSetup(val id: String) : MainAction
    /** @property id Existing card in the active room. */
    data class SelectSpeaker(val id: String) : MainAction
    /** @property name Immediate speaker-name draft, including temporarily blank text. */
    data class RenameSpeaker(val name: String) : MainAction
    /** @property channel Explicit assignment for the selected speaker. */
    data class AssignChannel(val channel: SpeakerChannel) : MainAction
    /** @property percent Requested UI volume, clamped to 0..100. */
    data class SetVolume(val percent: Int) : MainAction
    /** Move in normalized card travel; invalid non-finite coordinates are ignored.
     * @property id Sample identity in the active room.
     * @property position Requested bounded position.
     */
    data class MoveSpeaker(val id: String, val position: RoomPosition) : MainAction
    /** Complete a move by snapping against the currently rendered grid.
     * @property id Sample identity.
     * @property stepX One visible grid step as a fraction of horizontal card travel.
     * @property stepY One visible grid step as a fraction of vertical card travel.
     */
    data class FinishMove(val id: String, val stepX: Float, val stepY: Float) : MainAction
    data object ToggleSnap : MainAction
    /** @property delta Zoom adjustment; bounded by the state owner. */
    data class Zoom(val delta: Float) : MainAction
    data object ResetPositions : MainAction
    data object SaveSetup : MainAction
    data object TestSpeaker : MainAction
    /** Opens the platform chooser without automatically playing its selection. */
    data object ChooseAudioFile : MainAction
    /** Starts/restarts the loaded file or resumes its paused native queues. */
    data object Play : MainAction
    /** Retains the current native buffers without advancing playback. */
    data object Pause : MainAction
    /** Releases native streams and resets the retained file to its beginning. */
    data object Stop : MainAction
    /** @property dialog Modal to open. */
    data class OpenDialog(val dialog: MainDialog) : MainAction
    /** @property name Current setup-name input. */
    data class DialogName(val name: String) : MainAction
    data object ConfirmDialog : MainAction
    data object DismissDialog : MainAction
    /** @property sampleKey One of the explicit sample picker keys. */
    data class AddSpeaker(val sampleKey: String) : MainAction
    /** @property endpointId Opaque Windows endpoint ID selected from the current result. */
    data class AddOutput(val endpointId: String) : MainAction
    /** @property enabled Explicit request to switch to/from the isolated sample workspace. */
    data class SetDemoMode(val enabled: Boolean) : MainAction
    data object RefreshOutputs : MainAction
    data object DismissFeedback : MainAction
}
