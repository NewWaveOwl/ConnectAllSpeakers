package com.example.connectallspeakers.ViewModels.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.connectallspeakers.model.RoomPosition
import com.example.connectallspeakers.model.RoomSetup
import com.example.connectallspeakers.model.RoomSpeaker
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.emptySetups
import com.example.connectallspeakers.model.sampleSetups
import com.example.connectallspeakers.model.audio.OutputAvailability
import com.example.connectallspeakers.data.audio.AudioOutputRepository
import com.example.connectallspeakers.data.audio.AudioOutputSnapshot
import com.example.connectallspeakers.data.audio.AudioOutputStatus
import com.example.connectallspeakers.data.audio.AudioPlaybackController
import com.example.connectallspeakers.model.audio.PlaybackRoute
import com.example.connectallspeakers.model.audio.MAX_PLAYBACK_OUTPUTS
import com.example.connectallspeakers.state.MainDialog
import com.example.connectallspeakers.state.MainUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.round

// Animation settings
private const val TEST_HIGHLIGHT_MILLIS = 1400L

/** Owns session edits and routes explicit playback requests to the platform controller.
 * @param initialState Optional deterministic session used by behavior tests.
 * @param outputRepository Platform discovery contract, or null on unsupported launchers.
 * @param collectionScope Optional test scope; production defaults to this ViewModel's lifetime.
 * @param playbackController Application-owned audio engine, absent on unsupported launchers.
 * @param chooseAudioFile Platform file-picker callback; cancellation leaves the source unchanged.
 */
class MainViewModel(initialState: MainUiState = MainUiState(),
    private val outputRepository: AudioOutputRepository? = null,
    collectionScope: CoroutineScope? = null,
    private val playbackController: AudioPlaybackController? = null,
    private val chooseAudioFile: () -> Unit = {},
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState.copy(outputs = outputRepository?.outputs?.value ?: initialState.outputs,
        playback = playbackController?.playback?.value ?: initialState.playback))
    /** Read-only session state; immutable copies are emitted for every edit. */
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()
    private var nextIdentity = 1L
    private var testJob: Job? = null
    private var requestedTestEndpointId: String? = null
    private var inactiveSetups = if (initialState.demoMode) emptySetups() else sampleSetups()
    private var inactiveSelection: String? = "living"
    private var inactiveSaves: Map<String, RoomSetup> = emptyMap()
    private val ownerScope = collectionScope ?: viewModelScope

    init {
        if (playbackController != null) ownerScope.launch {
            playbackController.playback.collect { playback ->
                mutableState.update { it.copy(playback = playback) }
            }
        }
        if (outputRepository != null) ownerScope.launch {
            outputRepository.outputs.collect { snapshot ->
                if (state.value.demoMode) inactiveSetups = reconcileRooms(inactiveSetups, snapshot)
                mutableState.update { current -> current.copy(outputs = snapshot,
                    setups = if (current.demoMode) current.setups else reconcileRooms(current.setups, snapshot)) }
            }
        }
    }

    /** Applies edits synchronously and sends file/audio requests to their asynchronous platform owner.
     * @param action Intent from the view. Unknown IDs and non-finite moves are ignored.
     */
    fun onAction(action: MainAction) {
        val before = state.value
        when (action) {
            is MainAction.SelectSetup -> if (state.value.setups.any { it.id == action.id }) {
                mutableState.update { it.copy(selectedSetupId = action.id, dialog = null) }
            }
            is MainAction.SelectSpeaker -> editRoom { room ->
                if (room.speakers.any { it.id == action.id }) room.copy(selectedSpeakerId = action.id) else room
            }
            is MainAction.RenameSpeaker -> editSelected { it.copy(name = action.name.take(80)) }
            is MainAction.AssignChannel -> editSelected { it.copy(channel = action.channel) }
            is MainAction.SetVolume -> editSelected { it.copy(volume = action.percent.coerceIn(0, 100)) }
            is MainAction.MoveSpeaker -> if (action.position.x.isFinite() && action.position.y.isFinite()) {
                editRoom { room -> room.copy(speakers = room.speakers.map {
                    if (it.id == action.id) it.copy(position = RoomPosition(
                        action.position.x.coerceIn(0f, 1f), action.position.y.coerceIn(0f, 1f),
                    )) else it
                }) }
            }
            is MainAction.FinishMove -> finishMove(action)
            MainAction.ToggleSnap -> editRoom { it.copy(snapToGrid = !it.snapToGrid) }
            is MainAction.Zoom -> if (action.delta.isFinite()) editRoom { it.copy(zoom = (it.zoom + action.delta).coerceIn(.75f, 1.5f)) }
            MainAction.ResetPositions -> editRoom { room -> room.copy(
                speakers = room.speakers.map { it.copy(position = it.homePosition) }, zoom = 1f,
            ) }
            MainAction.SaveSetup -> state.value.selectedSetup?.let { room -> mutableState.update {
                it.copy(savedSetups = it.savedSetups + (room.id to room.copy(speakers = room.speakers.toList())),
                    feedback = "${room.name} saved for this session.")
            } }
            MainAction.TestSpeaker -> testSpeaker()
            MainAction.ChooseAudioFile -> if (!state.value.demoMode && playbackController != null) chooseAudioFile()
            MainAction.Play -> play()
            MainAction.Pause -> playbackController?.pause()
            MainAction.Stop -> { requestedTestEndpointId = null; playbackController?.stop() }
            is MainAction.OpenDialog -> mutableState.update { it.copy(
                dialog = action.dialog, dialogName = if (action.dialog == MainDialog.RenameSetup) it.selectedSetup?.name.orEmpty() else "",
                dialogError = null,
            ) }
            is MainAction.DialogName -> mutableState.update { it.copy(dialogName = action.name.take(60), dialogError = null) }
            MainAction.ConfirmDialog -> confirmDialog()
            MainAction.DismissDialog -> mutableState.update { it.copy(dialog = null, dialogError = null) }
            is MainAction.AddSpeaker -> addSpeaker(action.sampleKey)
            is MainAction.AddOutput -> addOutput(action.endpointId)
            is MainAction.SetDemoMode -> switchMode(action.enabled)
            MainAction.RefreshOutputs -> outputRepository?.refresh()
            MainAction.DismissFeedback -> mutableState.update { it.copy(feedback = null) }
        }
        val after = state.value
        if (before.selectedSetupId != after.selectedSetupId || before.demoMode != after.demoMode ||
            routes(before).map { it.endpointId }.toSet() != routes(after).map { it.endpointId }.toSet()) {
            requestedTestEndpointId = null
            playbackController?.stop()
        } else if (action is MainAction.SetVolume || action is MainAction.AssignChannel) {
            val latest = routes(after)
            val testEndpoint = requestedTestEndpointId ?: after.playback.takeIf { it.isTestTone }?.activeEndpointIds?.singleOrNull()
            playbackController?.updateRoutes(if (testEndpoint != null) latest.filter { it.endpointId == testEndpoint } else latest)
        }
    }

    /** Updates only the active room atomically.
     * @param transform Pure transformation; other rooms and saved snapshots stay independent.
     */
    private fun editRoom(transform: (RoomSetup) -> RoomSetup) {
        mutableState.update { current -> current.copy(setups = current.setups.map {
            if (it.id == current.selectedSetupId) transform(it) else it
        }) }
    }

    /** Updates only the selected card in the active room.
     * @param transform Pure speaker transformation.
     */
    private fun editSelected(transform: (RoomSpeaker) -> RoomSpeaker) = editRoom { room ->
        room.copy(speakers = room.speakers.map { if (it.id == room.selectedSpeakerId) transform(it) else it })
    }

    /** Snaps completed gestures; retaining continuous intermediate moves avoids drag stalling.
     * @param action Identity and grid steps from the rendered canvas, accounting for zoom/size.
     */
    private fun finishMove(action: MainAction.FinishMove) {
        if (!action.stepX.isFinite() || !action.stepY.isFinite() || action.stepX <= 0 || action.stepY <= 0) return
        editRoom { room -> if (!room.snapToGrid) room else room.copy(speakers = room.speakers.map {
            if (it.id != action.id) it else it.copy(position = RoomPosition(
                (round(it.position.x / action.stepX) * action.stepX).coerceIn(0f, 1f),
                (round(it.position.y / action.stepY) * action.stepY).coerceIn(0f, 1f),
            ))
        }) }
    }

    /** Validates setup names or deletes the active room, including the last remaining room. */
    private fun confirmDialog() {
        val current = state.value
        if (current.dialog == MainDialog.DeleteSetup) {
            val remaining = current.setups.filterNot { it.id == current.selectedSetupId }
            mutableState.update { it.copy(setups = remaining, selectedSetupId = remaining.firstOrNull()?.id,
                savedSetups = it.savedSetups - current.selectedSetupId.orEmpty(), dialog = null) }
            return
        }
        if (current.dialog != MainDialog.AddSetup && current.dialog != MainDialog.RenameSetup) return
        val name = current.dialogName.trim()
        val error = when {
            name.isBlank() -> "Enter a setup name."
            current.setups.any { it.name.equals(name, ignoreCase = true) &&
                (current.dialog == MainDialog.AddSetup || it.id != current.selectedSetupId) } -> "A setup with this name already exists."
            else -> null
        }
        if (error != null) { mutableState.update { it.copy(dialogError = error) }; return }
        if (current.dialog == MainDialog.AddSetup) {
            val id = uniqueId("setup")
            mutableState.update { it.copy(setups = it.setups + RoomSetup(id, name), selectedSetupId = id, dialog = null) }
        } else {
            editRoom { it.copy(name = name) }
            mutableState.update { it.copy(dialog = null) }
        }
    }

    /** Adds one explicit sample instance and selects it; repeated picks still get unique IDs.
     * @param sampleKey Picker choice; unrecognized choices are ignored.
     */
    private fun addSpeaker(sampleKey: String) {
        if (!state.value.demoMode) return
        val name = when (sampleKey) { "studio" -> "Studio Speaker"; "portable" -> "Portable Mini"; "bookshelf" -> "Bookshelf One"; else -> return }
        if (state.value.selectedSetup == null) return
        val id = uniqueId("sample")
        editRoom { room ->
            val offset = room.speakers.size % 5 * .08f
            room.copy(speakers = room.speakers + RoomSpeaker(id, name, SpeakerChannel.Mono, 80, RoomPosition(.35f + offset, .45f)), selectedSpeakerId = id)
        }
        mutableState.update { it.copy(dialog = null) }
    }

    /** Adds only a current active output; identity is independent of names and card instance IDs.
     * @param endpointId Unparsed ID selected from the latest successful enumeration.
     */
    private fun addOutput(endpointId: String) {
        val current = state.value
        if (current.demoMode || current.outputs.status != AudioOutputStatus.Ready) return
        val output = current.outputs.devices.find { it.endpointId == endpointId && it.availability == OutputAvailability.Active } ?: return
        val room = current.selectedSetup ?: return
        val existing = room.speakers.find { it.output?.endpointId == endpointId }
        if (existing != null) {
            editRoom { it.copy(selectedSpeakerId = existing.id) }
        } else {
            val id = uniqueId("output")
            val position = RoomPosition(.15f + room.speakers.size % 5 * .15f, .25f)
            editRoom { it.copy(speakers = it.speakers + RoomSpeaker(id, output.displayName,
                SpeakerChannel.Mono, 80, position, output = output), selectedSpeakerId = id) }
        }
        mutableState.update { it.copy(dialog = null) }
    }

    /** Switches isolated room banks only on explicit user request, preserving both sets of edits.
     * @param enabled Whether to enter the clearly labeled sample workspace.
     */
    private fun switchMode(enabled: Boolean) {
        val current = state.value
        if (current.demoMode == enabled) return
        val rooms = if (enabled) inactiveSetups else reconcileRooms(inactiveSetups, current.outputs)
        val selection = inactiveSelection
        val saves = inactiveSaves
        inactiveSetups = current.setups
        inactiveSelection = current.selectedSetupId
        inactiveSaves = current.savedSetups
        mutableState.value = current.copy(demoMode = enabled, setups = rooms, selectedSetupId = selection,
            savedSetups = saves, dialog = null, dialogError = null, testingSpeakerId = null, feedback = null)
    }

    /** Reconciles OS metadata only; never changes names, channels, positions, volumes or selection.
     * @param rooms Real-device rooms, including currently inactive rooms.
     * @param snapshot Latest discovery result. Errors/refreshes mean Unknown, never a disconnection.
     * @return Updated immutable room copies retaining missing endpoint cards.
     */
    private fun reconcileRooms(rooms: List<RoomSetup>, snapshot: AudioOutputSnapshot): List<RoomSetup> {
        val outputs = snapshot.devices.associateBy { it.endpointId }
        return rooms.map { room -> room.copy(speakers = room.speakers.map { speaker ->
            val old = speaker.output
            if (old == null) speaker else speaker.copy(output = when (snapshot.status) {
                AudioOutputStatus.Ready -> outputs[old.endpointId] ?: old.copy(availability = OutputAvailability.Unavailable, isDefault = false)
                else -> old.copy(availability = OutputAvailability.Unknown, isDefault = false)
            })
        }) }
    }

    /** Allocates an ID distinct from every current and saved room/sample.
     * @param prefix Human-readable demo identity kind.
     * @return A session-unique identity, even with injected test state.
     */
    private fun uniqueId(prefix: String): String {
        val rooms = state.value.setups + state.value.savedSetups.values + inactiveSetups + inactiveSaves.values
        val used = rooms.flatMap { listOf(it.id) + it.speakers.map { speaker -> speaker.id } }.toSet()
        var candidate: String
        do { candidate = "$prefix-${nextIdentity++}" } while (candidate in used)
        return candidate
    }

    /** @param snapshot Session to inspect. @return Real routes, preserving names/positions separately. */
    private fun routes(snapshot: MainUiState): List<PlaybackRoute> = snapshot.selectedSetup?.speakers.orEmpty().mapNotNull { speaker ->
        speaker.output?.let { PlaybackRoute(it.endpointId, speaker.channel, speaker.volume) }
    }

    /** Validates the entire room rather than silently omitting unavailable/demo/extra outputs. */
    private fun play() {
        val current = state.value
        val speakers = current.selectedSetup?.speakers.orEmpty()
        val error = when {
            playbackController == null -> "Playback is available in the Windows desktop app only."
            current.demoMode -> "Demo speakers cannot play audio. Switch Demo mode off and select real outputs."
            speakers.size !in 1..MAX_PLAYBACK_OUTPUTS -> "Add one or two real outputs to this room for playback."
            speakers.any { it.isSample || it.availability != OutputAvailability.Active } -> "Every room output must be available before playback."
            current.outputs.status != AudioOutputStatus.Ready -> "Refresh outputs successfully before playback."
            else -> null
        }
        if (error != null) mutableState.update { it.copy(feedback = error) }
        else {
            requestedTestEndpointId = null
            playbackController?.play(routes(current))
        }
    }

    /** Sends an explicit real-device test request and shows a cancellable visual pulse. */
    private fun testSpeaker() {
        val selected = state.value.selectedSpeaker ?: return
        val output = selected.output
        if (state.value.demoMode || output == null || playbackController == null) {
            mutableState.update { it.copy(feedback = "Demo speakers cannot play audio. Select a real Windows output to test.") }
            return
        }
        if (output.availability != OutputAvailability.Active || state.value.outputs.status != AudioOutputStatus.Ready) {
            mutableState.update { it.copy(feedback = "This output is unavailable. Reconnect it and refresh before testing.") }
            return
        }
        val id = selected.id
        testJob?.cancel()
        requestedTestEndpointId = output.endpointId
        playbackController.testSpeaker(PlaybackRoute(output.endpointId, selected.channel, selected.volume))
        mutableState.update { it.copy(testingSpeakerId = id, feedback = "Test tone requested for ${selected.name}. App volume: ${selected.volume}%.") }
        testJob = ownerScope.launch {
            delay(TEST_HIGHLIGHT_MILLIS)
            mutableState.update { it.copy(testingSpeakerId = null) }
        }
    }
}
