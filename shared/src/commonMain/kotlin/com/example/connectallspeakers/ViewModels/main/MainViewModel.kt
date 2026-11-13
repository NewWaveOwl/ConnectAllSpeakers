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
import com.example.connectallspeakers.model.audio.PlaybackPhase
import com.example.connectallspeakers.model.audio.SPEAKER_TEST_DURATION_MILLIS
import com.example.connectallspeakers.model.audio.MAX_PLAYBACK_OUTPUTS
import com.example.connectallspeakers.model.audio.MAX_OUTPUT_DELAY_MILLIS
import com.example.connectallspeakers.model.audio.TIMING_STALE_AFTER_MILLIS
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.example.connectallspeakers.state.MainDialog
import com.example.connectallspeakers.state.MainUiState
import com.example.connectallspeakers.state.WorkspaceMode
import com.example.connectallspeakers.state.GroupDevice
import com.example.connectallspeakers.state.MAX_GROUP_DEVICES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.round
import com.example.connectallspeakers.data.profiles.SetupProfileRepository
import com.example.connectallspeakers.data.profiles.toProfile
import com.example.connectallspeakers.data.profiles.toRoom
import com.example.connectallspeakers.model.profiles.ProfileBank
import com.example.connectallspeakers.model.profiles.ProfileMode
import com.example.connectallspeakers.model.profiles.SetupProfile
import com.example.connectallspeakers.state.ProfileStorageStatus
import kotlinx.coroutines.CancellationException
import com.example.connectallspeakers.model.audio.EqBand
import com.example.connectallspeakers.model.audio.SpeakerEq
import com.example.connectallspeakers.model.audio.AudioOutputRole
import com.example.connectallspeakers.model.audio.LIVE_SAMPLE_RATE

/** Owns session edits and routes explicit playback requests to the platform controller.
 * @param initialState Optional deterministic session used by behavior tests.
 * @param outputRepository Platform discovery contract, or null on unsupported launchers.
 * @param collectionScope Optional test scope; production defaults to this ViewModel's lifetime.
 * @param playbackController Application-owned audio engine, absent on unsupported launchers.
 * @param chooseAudioFile Platform file-picker callback; cancellation leaves the source unchanged.
 * @param profileRepository Durable explicit-save storage, or null for session-only launchers/tests.
 */
class MainViewModel(initialState: MainUiState = MainUiState(),
    private val outputRepository: AudioOutputRepository? = null,
    collectionScope: CoroutineScope? = null,
    private val playbackController: AudioPlaybackController? = null,
    private val chooseAudioFile: () -> Unit = {},
    private val profileRepository: SetupProfileRepository? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(initialState.copy(outputs = outputRepository?.outputs?.value ?: initialState.outputs,
        playback = playbackController?.playback?.value ?: initialState.playback,
        virtualInputSupported = playbackController?.supportsVirtualInput == true,
        appCaptureSupported = playbackController?.supportsAppCapture == true,
        profileStorage = if (profileRepository == null) ProfileStorageStatus.SessionOnly else ProfileStorageStatus.Loading))
    /** Read-only session state; immutable copies are emitted for every edit. */
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()
    private var nextIdentity = 1L
    private var requestedTestEndpointId: String? = null
    private var inactiveSetups = if (initialState.demoMode) emptySetups() else sampleSetups()
    private var inactiveSelection: String? = "living"
    private var inactiveSaves: Map<String, RoomSetup> = emptyMap()
    private val ownerScope = collectionScope ?: viewModelScope
    private var appRefresh: Job? = null
    private var timingExpiry: Job? = null
    private var receivedTimingSequence: Long? = null
    private var expiredTimingSequence: Long? = null
    private var durableProfile = SetupProfile()
    private val initialWorkspace = initialState

    init {
        if (profileRepository != null) loadProfiles()
        if (playbackController != null) ownerScope.launch {
            playbackController.playback.collect { playback ->
                if (playback.outputTimings.none { it.isLive }) {
                    timingExpiry?.cancel()
                    receivedTimingSequence = null
                    expiredTimingSequence = null
                } else if (receivedTimingSequence != playback.timingSequence) {
                    timingExpiry?.cancel()
                    val sequence = playback.timingSequence
                    receivedTimingSequence = sequence
                    expiredTimingSequence = null
                    timingExpiry = ownerScope.launch {
                        delay(TIMING_STALE_AFTER_MILLIS)
                        expiredTimingSequence = sequence
                        mutableState.update { current ->
                            if (current.playback.timingSequence != sequence) current else current.copy(
                                playback = current.playback.copy(outputTimings = current.playback.outputTimings.map {
                                    it.unavailable("Timing update is stale; measurements Unknown")
                                }))
                        }
                    }
                }
                val presentation = if (expiredTimingSequence == playback.timingSequence) playback.copy(
                    outputTimings = playback.outputTimings.map { it.unavailable("Timing update is stale; measurements Unknown") }) else playback
                mutableState.update { current ->
                    val activeTest = when (playback.phase) {
                        PlaybackPhase.Starting, PlaybackPhase.Playing, PlaybackPhase.Paused -> playback.isTestTone
                        else -> false
                    }
                    current.copy(playback = presentation, testingSpeakerId = if (activeTest && current.workspace == WorkspaceMode.GroupSound) {
                        current.group.devices.firstOrNull { it.output.endpointId in playback.activeEndpointIds }?.output?.endpointId
                    } else if (activeTest) {
                        current.selectedSetup?.speakers?.firstOrNull { it.output?.endpointId in playback.activeEndpointIds }?.id
                    } else null)
                }
            }
        }
        if (outputRepository != null) ownerScope.launch {
            outputRepository.outputs.collect { snapshot ->
                if (state.value.demoMode) inactiveSetups = reconcileRooms(inactiveSetups, snapshot)
                mutableState.update { current -> current.copy(outputs = snapshot,
                    group = current.group.copy(devices = current.group.devices.map { selected ->
                        val output = snapshot.devices.singleOrNull { it.endpointId == selected.output.endpointId }
                        selected.copy(output = output ?: selected.output.copy(availability =
                            if (snapshot.status == AudioOutputStatus.Ready) OutputAvailability.Unavailable else OutputAvailability.Unknown))
                    }),
                    setups = if (current.demoMode) current.setups else reconcileRooms(current.setups, snapshot)) }
            }
        }
    }

    /** Applies edits synchronously and sends file/audio requests to their asynchronous platform owner.
     * @param action Intent from the view. Unknown IDs and non-finite moves are ignored.
     */
    fun onAction(action: MainAction) {
        if (state.value.profileStorage == ProfileStorageStatus.Loading) return
        val before = state.value
        when (action) {
            is MainAction.SelectWorkspace -> if (state.value.workspace != action.mode) {
                stopPlayback()
                if (action.mode == WorkspaceMode.GroupSound && state.value.demoMode) switchMode(false)
                mutableState.update { it.copy(workspace = action.mode, dialog = null, feedback = null) }
            }
            is MainAction.Group -> groupAction(action.action)
            is MainAction.SelectSetup -> if (state.value.setups.any { it.id == action.id }) {
                mutableState.update { it.copy(selectedSetupId = action.id, dialog = null) }
            }
            is MainAction.SelectSpeaker -> editRoom { room ->
                if (room.speakers.any { it.id == action.id }) room.copy(selectedSpeakerId = action.id) else room
            }
            is MainAction.RenameSpeaker -> editSelected { it.copy(name = action.name.take(80)) }
            is MainAction.AssignChannel -> editSelected { it.copy(channel = action.channel) }
            is MainAction.SetVolume -> editSelected { it.copy(volume = action.percent.coerceIn(0, 100)) }
            is MainAction.SetEqEnabled -> editEq { it.copy(enabled = action.enabled) }
            is MainAction.SetEqBand -> editEq { it.withBand(action.index, EqBand(action.frequencyHz, action.gainDb, action.q)) }
            MainAction.ResetEq -> editEq { SpeakerEq() }
            is MainAction.SetDelay -> {
                val current = state.value
                when {
                    action.millis !in 0..MAX_OUTPUT_DELAY_MILLIS -> mutableState.update { it.copy(feedback = "Delay must be between 0 and $MAX_OUTPUT_DELAY_MILLIS ms.") }
                    current.demoMode || current.selectedSpeaker?.output == null -> mutableState.update { it.copy(feedback = "Delay calibration uses real Windows outputs.") }
                    current.selectedSpeaker?.id == current.selectedSetup?.calibrationReference?.id && action.millis != 0 ->
                        mutableState.update { it.copy(feedback = "The reference stays at 0 ms. Choose the slower speaker as reference, then delay the faster speaker.") }
                    else -> editSelected { it.copy(delayMillis = action.millis) }
                }
            }
            is MainAction.SelectCalibrationReference -> if (!state.value.demoMode) editRoom { room ->
                if (room.speakers.none { it.id == action.id && it.output != null }) room else room.copy(
                    calibrationReferenceSpeakerId = action.id,
                    speakers = room.speakers.map { if (it.id == action.id) it.copy(delayMillis = 0) else it })
            }
            is MainAction.SetDriftCorrection -> if (!state.value.demoMode) editRoom { it.copy(driftCorrectionEnabled = action.enabled) }
            MainAction.ResetDelays -> editRoom { room -> room.copy(speakers = room.speakers.map { it.copy(delayMillis = 0) }) }
            MainAction.PlayCalibration -> if (state.value.workspace == WorkspaceMode.Room) play(calibration = true)
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
            MainAction.SaveSetup -> saveSetup()
            MainAction.TestSpeaker -> testSpeaker()
            MainAction.ChooseAudioFile -> if (!state.value.demoMode && !state.value.liveInputMode && playbackController != null) chooseAudioFile()
            MainAction.Play -> if (!state.value.liveInputMode) play()
            MainAction.Pause -> playbackController?.pause()
            MainAction.Stop -> stopPlayback()
            is MainAction.SetLiveInputMode -> if (state.value.liveInputMode != action.enabled || state.value.appCaptureMode) {
                stopPlayback()
                mutableState.update { it.copy(liveInputMode = action.enabled, appCaptureMode = false, feedback = null) }
            }
            MainAction.SelectAppCaptureMode -> {
                stopPlayback()
                mutableState.update { it.copy(liveInputMode = true, appCaptureMode = true, feedback = null) }
                refreshCaptureApps()
            }
            MainAction.RefreshCaptureApps -> refreshCaptureApps()
            is MainAction.SelectCaptureApp -> if (action.app in state.value.captureApps) {
                stopPlayback()
                mutableState.update { it.copy(selectedCaptureApp = action.app) }
            }
            MainAction.StartAppCapture -> startAppCapture()
            is MainAction.SelectVirtualInput -> if (state.value.outputs.devices.any {
                it.endpointId == action.endpointId && it.role == AudioOutputRole.AllConnectInput
            }) {
                stopPlayback()
                mutableState.update { it.copy(virtualInputId = action.endpointId) }
            }
            MainAction.StartVirtualInput -> startVirtualInput()
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
            routes(before).map { it.endpointId }.toSet() != routes(after).map { it.endpointId }.toSet() ||
            before.selectedSetup?.calibrationReference?.id != after.selectedSetup?.calibrationReference?.id ||
            before.selectedSetup?.driftCorrectionEnabled != after.selectedSetup?.driftCorrectionEnabled ||
            routes(before).map { it.endpointId to it.delayMillis } != routes(after).map { it.endpointId to it.delayMillis }) {
            stopPlayback()
            if (action is MainAction.SetDelay || action is MainAction.SelectCalibrationReference || action is MainAction.SetDriftCorrection || action == MainAction.ResetDelays) {
                mutableState.update { it.copy(feedback = if (it.liveInputMode) "Timing settings changed. Press Start forwarding to restart. The file-only drift switch does not control live rate correction."
                    else "Timing settings changed. Press Play to restart; calibration cues use gain, EQ and manual delay without drift correction.") }
            }
        } else if (action is MainAction.SetVolume || action is MainAction.AssignChannel || action is MainAction.SetEqEnabled ||
            action is MainAction.SetEqBand || action == MainAction.ResetEq) {
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

    /** Validates the selected card's parameters and known actual source rate before changing any room/route.
     * @param transform Requested EQ edit; invalid floats/frequencies retain the previous safe settings.
     */
    private fun editEq(transform: (SpeakerEq) -> SpeakerEq) {
        val selected = state.value.selectedSpeaker ?: return
        try {
            val next = transform(selected.eq)
            if (!state.value.demoMode) (if (state.value.liveInputMode) LIVE_SAMPLE_RATE else state.value.playback.source?.sampleRate)
                ?.let { next.validateForSampleRate(it) }
            editSelected { it.copy(eq = next) }
            mutableState.update { it.copy(dialogError = null, feedback = null) }
        } catch (invalid: IllegalArgumentException) {
            mutableState.update { it.copy(dialogError = invalid.message, feedback = invalid.message) }
        }
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
        if (current.dialog == MainDialog.ReloadSetups) {
            if (!current.savingProfiles) { stopPlayback(); loadProfiles() }
            return
        }
        if (current.dialog == MainDialog.DeleteSetup) {
            deleteSetup()
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
        val output = current.outputs.devices.find { it.endpointId == endpointId && it.availability == OutputAvailability.Active && it.role == AudioOutputRole.PlaybackTarget } ?: return
        val room = current.selectedSetup ?: return
        val existing = room.speakers.find { it.output?.endpointId == endpointId }
        if (existing != null) {
            editRoom { it.copy(selectedSpeakerId = existing.id) }
        } else {
            val id = uniqueId("output")
            val position = RoomPosition(.15f + room.speakers.size % 5 * .15f, .25f)
            editRoom { it.copy(speakers = it.speakers + RoomSpeaker(id, output.displayName.take(80),
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

    /** Restores saved banks without dispatching any play/test/load-file action. Failed loads preserve current edits. */
    private fun loadProfiles() {
        val repository = profileRepository ?: return
        mutableState.update { it.copy(profileStorage = ProfileStorageStatus.Loading, dialog = null) }
        ownerScope.launch {
            try {
                val loaded = repository.load()
                loaded?.validate()
                durableProfile = loaded ?: SetupProfile()
                val demo = loaded?.mode == ProfileMode.Demo || (loaded == null && initialWorkspace.demoMode)
                val realRooms = loaded?.real?.rooms?.map { it.toRoom() } ?: if (initialWorkspace.demoMode) emptySetups() else initialWorkspace.setups
                val demoRooms = loaded?.demo?.rooms?.map { it.toRoom() } ?: if (initialWorkspace.demoMode) initialWorkspace.setups else sampleSetups()
                val realSelection = if (loaded != null) loaded.real.selectedRoomId else if (initialWorkspace.demoMode) "living" else initialWorkspace.selectedSetupId
                val demoSelection = if (loaded != null) loaded.demo.selectedRoomId else if (initialWorkspace.demoMode) initialWorkspace.selectedSetupId else "living"
                inactiveSetups = if (demo) reconcileRooms(realRooms, state.value.outputs) else demoRooms
                inactiveSelection = if (demo) realSelection else demoSelection
                inactiveSaves = (if (demo) durableProfile.real else durableProfile.demo).rooms.associate { it.id to it.toRoom() }
                mutableState.update { current -> current.copy(
                    setups = if (demo) demoRooms else reconcileRooms(realRooms, current.outputs),
                    selectedSetupId = if (demo) demoSelection else realSelection, demoMode = demo,
                    savedSetups = (if (demo) durableProfile.demo else durableProfile.real).rooms.associate { it.id to it.toRoom() },
                    profileStorage = ProfileStorageStatus.Ready, profileError = null, testingSpeakerId = null,
                    feedback = if (loaded == null) null else "Saved setups restored.") }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(profileStorage = ProfileStorageStatus.LoadFailed,
                    profileError = "Could not load saved setups. ${error.message.orEmpty().take(350)}", feedback = null) }
            }
        }
    }

    /** Captures one room now; later edits and selection/mode switches cannot alter the pending write. */
    private fun saveSetup() {
        val current = state.value
        val room = current.selectedSetup ?: return
        if (current.savingProfiles) return
        if (profileRepository == null) {
            mutableState.update { it.copy(savedSetups = it.savedSetups + (room.id to room.copy(speakers = room.speakers.toList())),
                feedback = "${room.name} saved for this session; durable storage is unavailable on this platform.") }
            return
        }
        if (current.profileStorage != ProfileStorageStatus.Ready) return
        val old = if (current.demoMode) durableProfile.demo else durableProfile.real
        val captured = room.toProfile(old.rooms.find { it.id == room.id })
        val snapshots = old.rooms.associateBy { it.id } + (room.id to captured)
        val bank = ProfileBank(current.setups.mapNotNull { snapshots[it.id] }, room.id)
        val candidate = if (current.demoMode) durableProfile.copy(demo = bank, mode = ProfileMode.Demo)
            else durableProfile.copy(real = bank, mode = ProfileMode.Real)
        writeProfile(candidate, "${room.name} saved on this PC.")
    }

    /** Explicit deletion persists before removing a saved room; failed writes leave its card/settings intact. */
    private fun deleteSetup() {
        val current = state.value
        val id = current.selectedSetupId ?: return
        if (current.savingProfiles) return
        val mode = current.demoMode
        val old = if (mode) durableProfile.demo else durableProfile.real
        if (profileRepository != null && old.rooms.any { it.id == id }) {
            val rooms = old.rooms.filterNot { it.id == id }
            val bank = ProfileBank(rooms, rooms.firstOrNull()?.id)
            val candidate = if (mode) durableProfile.copy(demo = bank, mode = ProfileMode.Demo)
                else durableProfile.copy(real = bank, mode = ProfileMode.Real)
            mutableState.update { it.copy(dialog = null) }
            writeProfile(candidate, "${current.selectedSetup?.name.orEmpty()} deleted from saved setups.") { removeRoom(id, mode, stopSelectedPlayback = true) }
        } else removeRoom(id, mode)
    }

    /** Removes the captured identity in its own bank, even if the user switched modes during a write.
     * @param id Room to remove, never inferred from the current selection on completion.
     * @param demo Bank owning the captured room.
     * @param stopSelectedPlayback True for asynchronous completion; synchronous actions use onAction's route-change check.
     */
    private fun removeRoom(id: String, demo: Boolean, stopSelectedPlayback: Boolean = false) {
        if (state.value.demoMode == demo) {
            if (stopSelectedPlayback && state.value.selectedSetupId == id) stopPlayback()
            mutableState.update { current ->
                val remaining = current.setups.filterNot { it.id == id }
                current.copy(setups = remaining,
                    selectedSetupId = if (current.selectedSetupId == id) remaining.firstOrNull()?.id else current.selectedSetupId,
                    savedSetups = current.savedSetups - id, dialog = null)
            }
        } else {
            inactiveSetups = inactiveSetups.filterNot { it.id == id }
            if (inactiveSelection == id) inactiveSelection = inactiveSetups.firstOrNull()?.id
            inactiveSaves = inactiveSaves - id
        }
    }

    /** Serializes explicit write actions, publishing durable snapshots only after storage completes.
     * @param candidate Immutable settings captured by the initiating action.
     * @param success User-readable feedback after successful durable storage.
     * @param afterSave Optional deletion completion, applied to the captured identity only.
     */
    private fun writeProfile(candidate: SetupProfile, success: String, afterSave: () -> Unit = {}) {
        mutableState.update { it.copy(savingProfiles = true) }
        ownerScope.launch {
            try {
                candidate.validate()
                profileRepository!!.save(candidate)
                durableProfile = candidate
                inactiveSaves = (if (state.value.demoMode) candidate.real else candidate.demo).rooms.associate { it.id to it.toRoom() }
                mutableState.update { current -> current.copy(
                    savedSetups = (if (current.demoMode) candidate.demo else candidate.real).rooms.associate { it.id to it.toRoom() },
                    savingProfiles = false, profileError = null, feedback = success) }
                afterSave()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(savingProfiles = false,
                    profileError = "Setups were not saved. ${error.message.orEmpty().take(350)}", feedback = null) }
            } finally {
                mutableState.update { it.copy(savingProfiles = false) }
            }
        }
    }

    /** @param snapshot Session to inspect. @return Real routes, preserving names/positions separately. */
    private fun routes(snapshot: MainUiState): List<PlaybackRoute> = if (snapshot.workspace == WorkspaceMode.GroupSound)
        snapshot.group.devices.map { it.route() } else snapshot.selectedSetup?.speakers.orEmpty().mapNotNull { speaker ->
        speaker.output?.let { PlaybackRoute(it.endpointId, speaker.channel, speaker.volume, speaker.delayMillis, speaker.eq) }
    }

    /** Validates the entire room rather than silently omitting unavailable/demo/extra outputs.
     * @param calibration Whether to play generated matching cues instead of the retained local file.
     */
    private fun play(calibration: Boolean = false) {
        val current = state.value
        val speakers = playbackSpeakers(current)
        val error = when {
            playbackController == null -> "Playback is available in the Windows desktop app only."
            current.workspace == WorkspaceMode.GroupSound && current.group.routeError(current.outputs) != null -> current.group.routeError(current.outputs)
            current.demoMode -> "Demo speakers cannot play audio. Switch Demo mode off and select real outputs."
            speakers.size !in 1..MAX_PLAYBACK_OUTPUTS -> "Add one or two real outputs to this room for playback."
            calibration && speakers.size != 2 -> "Add two real outputs to compare calibration cues."
            !calibration && current.workspace == WorkspaceMode.Room && current.selectedSetup?.driftCorrectionEnabled == true && speakers.size != 2 -> "Experimental drift correction needs two real outputs. Add another output or disable correction."
            speakers.any { it.isSample || it.availability != OutputAvailability.Active } -> "Every room output must be available before playback."
            speakers.any { it.output?.role != AudioOutputRole.PlaybackTarget } -> "The AllConnect virtual output and unidentified devices cannot be physical playback destinations."
            current.outputs.status != AudioOutputStatus.Ready -> "Refresh outputs successfully before playback."
            (calibration || (current.workspace == WorkspaceMode.Room && current.selectedSetup?.driftCorrectionEnabled == true)) && current.selectedSetup?.calibrationReference?.delayMillis != 0 -> "The calibration reference must have zero delay."
            else -> null
        }
        if (error != null) mutableState.update { it.copy(feedback = error) }
        else {
            requestedTestEndpointId = null
            if (calibration) playbackController?.calibrate(routes(current), current.selectedSetup!!.calibrationReference!!.output!!.endpointId)
            else playbackController?.play(routes(current), if (current.workspace == WorkspaceMode.Room && current.selectedSetup?.driftCorrectionEnabled == true)
                current.selectedSetup!!.calibrationReference!!.output!!.endpointId else null)
        }
    }

    /** Reuses room transport validation without storing any group device in room profiles.
     * @param snapshot Current session. @return Active view's exact endpoint/settings snapshots.
     */
    private fun playbackSpeakers(snapshot: MainUiState): List<RoomSpeaker> =
        if (snapshot.workspace == WorkspaceMode.Room) snapshot.selectedSetup?.speakers.orEmpty()
        else snapshot.group.devices.map { RoomSpeaker(it.output.endpointId, it.output.displayName,
            SpeakerChannel.Mono, it.volumePercent, RoomPosition(0f, 0f), output = it.output,
            delayMillis = it.delayMillis, eq = it.eq) }

    /** Handles transient membership and independent levels through the existing single engine.
     * @param action Group intent. Membership/timing changes stop; gain/mute updates retain the source lifetime.
     */
    private fun groupAction(action: GroupSoundAction) {
        if (state.value.workspace != WorkspaceMode.GroupSound) return
        val current = state.value
        val before = current.group.devices
        fun edit(id: String, transform: (GroupDevice) -> GroupDevice) {
            mutableState.update { it.copy(group = it.group.copy(devices = it.group.devices.map { device ->
                if (device.output.endpointId == id) transform(device) else device })) }
        }
        when (action) {
            is GroupSoundAction.SetView -> mutableState.update { it.copy(group = it.group.copy(view = action.view)) }
            GroupSoundAction.ManageDevices -> mutableState.update { it.copy(group = it.group.copy(managingDevices = !it.group.managingDevices)) }
            GroupSoundAction.ChooseSource -> mutableState.update { it.copy(group = it.group.copy(choosingSource = !it.group.choosingSource)) }
            GroupSoundAction.AdjustTiming -> mutableState.update { it.copy(group = it.group.copy(adjustingTiming = !it.group.adjustingTiming)) }
            is GroupSoundAction.SelectDevice -> if (before.any { it.output.endpointId == action.endpointId }) {
                mutableState.update { it.copy(group = it.group.copy(selectedDeviceId = action.endpointId)) }
            }
            is GroupSoundAction.SearchDevices -> mutableState.update { it.copy(group = it.group.copy(deviceSearch = action.query)) }
            is GroupSoundAction.AddDevice -> {
                val output = current.outputs.devices.singleOrNull { it.endpointId == action.endpointId }
                when {
                    current.outputs.status != AudioOutputStatus.Ready || output == null || output.role != AudioOutputRole.PlaybackTarget || output.availability != OutputAvailability.Active ->
                        mutableState.update { it.copy(feedback = "Choose an available, identified physical endpoint after refreshing outputs.") }
                    before.any { it.output.endpointId == action.endpointId } -> mutableState.update { it.copy(feedback = "This listening device is already selected.") }
                    before.size >= MAX_GROUP_DEVICES -> mutableState.update { it.copy(feedback = "The Shared Circle can display up to $MAX_GROUP_DEVICES devices.") }
                    else -> mutableState.update { it.copy(group = it.group.copy(devices = before + GroupDevice(output),
                        selectedDeviceId = it.group.selectedDevice?.output?.endpointId ?: action.endpointId)) }
                }
            }
            is GroupSoundAction.RemoveDevice -> mutableState.update {
                val remaining = before.filterNot { device -> device.output.endpointId == action.endpointId }
                it.copy(group = it.group.copy(devices = remaining, selectedDeviceId =
                    it.group.selectedDeviceId?.takeIf { id -> remaining.any { device -> device.output.endpointId == id } }
                        ?: remaining.firstOrNull()?.output?.endpointId))
            }
            is GroupSoundAction.SetVolume -> edit(action.endpointId) { it.copy(volumePercent = action.percent.coerceIn(0, 100)) }
            is GroupSoundAction.ToggleMute -> edit(action.endpointId) { it.copy(muted = !it.muted) }
            is GroupSoundAction.SetDelay -> if (action.millis in 0..MAX_OUTPUT_DELAY_MILLIS) edit(action.endpointId) { it.copy(delayMillis = action.millis) }
                else mutableState.update { it.copy(feedback = "Delay must be between 0 and $MAX_OUTPUT_DELAY_MILLIS ms.") }
            is GroupSoundAction.TestDevice -> {
                val device = before.singleOrNull { it.output.endpointId == action.endpointId } ?: return
                if (current.testingSpeakerId == action.endpointId) { stopPlayback(); return }
                val error = current.group.copy(devices = listOf(device)).routeError(current.outputs)
                if (error != null || playbackController == null) {
                    mutableState.update { it.copy(feedback = error ?: "Playback requires Windows.") }; return
                }
                run {
                    requestedTestEndpointId = action.endpointId
                    playbackController?.testSpeaker(device.route())
                    mutableState.update { it.copy(testingSpeakerId = action.endpointId) }
                }
            }
        }
        val after = state.value.group.devices
        if (before.map { it.output.endpointId to it.delayMillis } != after.map { it.output.endpointId to it.delayMillis }) {
            stopPlayback()
        } else if (before != after) {
            val latest = routes(state.value)
            val updated = requestedTestEndpointId?.let { test -> latest.filter { it.endpointId == test } } ?: latest
            if (updated.size in 1..MAX_PLAYBACK_OUTPUTS) playbackController?.updateRoutes(updated)
        }
    }

    /** Clears any pending test highlight and requests whole-session native cleanup. */
    private fun stopPlayback() {
        requestedTestEndpointId = null
        mutableState.update { it.copy(testingSpeakerId = null) }
        playbackController?.stop()
    }

    /** Starts live input only after exact owned-source/physical-target checks; never substitutes a device. */
    private fun startVirtualInput() {
        val current = state.value
        val speakers = playbackSpeakers(current)
        val error = when {
            current.workspace == WorkspaceMode.GroupSound && current.group.routeError(current.outputs) != null -> current.group.routeError(current.outputs)
            !current.liveInputMode || current.appCaptureMode || current.demoMode -> "Select AllConnect virtual output with Demo mode off."
            playbackController?.supportsVirtualInput != true -> "AllConnect virtual output is available in the Windows desktop app only."
            current.outputs.status != AudioOutputStatus.Ready -> "Refresh outputs successfully before starting live input."
            current.outputs.devices.none { it.endpointId == current.virtualInputId && it.role == AudioOutputRole.AllConnectInput && it.availability == OutputAvailability.Active } -> "Select the available AllConnect Speakers virtual output. Its driver must be installed separately."
            speakers.size !in 1..MAX_PLAYBACK_OUTPUTS -> "Add one or two physical outputs to this room."
            speakers.any { it.output?.role != AudioOutputRole.PlaybackTarget || it.availability != OutputAvailability.Active } -> "Every target must be an available physical playback output."
            else -> null
        }
        if (error != null) { mutableState.update { it.copy(feedback = error) }; return }
        try {
            val selectedRoutes = routes(current)
            selectedRoutes.forEach { it.eq.validateForSampleRate(LIVE_SAMPLE_RATE) }
            requestedTestEndpointId = null
            playbackController?.startVirtualInput(requireNotNull(current.virtualInputId), selectedRoutes)
        } catch (invalid: IllegalArgumentException) { mutableState.update { it.copy(feedback = invalid.message) } }
    }

    /** Refreshes session-only process identities without activating audio. */
    private fun refreshCaptureApps() {
        if (playbackController?.supportsAppCapture != true) return
        appRefresh?.cancel()
        mutableState.update { it.copy(captureAppsLoading = true, captureAppsError = null) }
        appRefresh = ownerScope.launch {
            try {
                val apps = playbackController.captureApps()
                mutableState.update { it.copy(captureApps = apps, captureAppsLoading = false,
                    selectedCaptureApp = it.selectedCaptureApp?.takeIf { selected -> selected in apps }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.update { it.copy(captureAppsLoading = false, captureAppsError = failure.message ?: "Could not list apps.") }
            }
        }
    }

    /** Reuses the bounded live renderer with an explicit process tree instead of a virtual endpoint. */
    private fun startAppCapture() {
        val current = state.value
        val app = current.selectedCaptureApp
        val speakers = playbackSpeakers(current)
        val error = when {
            current.workspace == WorkspaceMode.GroupSound && current.group.routeError(current.outputs) != null -> current.group.routeError(current.outputs)
            !current.appCaptureMode || current.demoMode -> "Select App audio with Demo mode off."
            !current.appCaptureSupported -> "App audio capture is unavailable on this platform."
            current.captureAppsLoading || current.captureAppsError != null -> "Refresh apps successfully first."
            app == null || app !in current.captureApps -> "Select a running app."
            current.outputs.status != AudioOutputStatus.Ready -> "Refresh outputs successfully first."
            speakers.size !in 1..MAX_PLAYBACK_OUTPUTS -> "Add one or two physical outputs to this room."
            speakers.any { it.output?.role != AudioOutputRole.PlaybackTarget || it.availability != OutputAvailability.Active } -> "Every target must be an available physical playback output."
            else -> null
        }
        if (error != null) { mutableState.update { it.copy(feedback = error) }; return }
        try {
            val selectedRoutes = routes(current)
            selectedRoutes.forEach { it.eq.validateForSampleRate(LIVE_SAMPLE_RATE) }
            requestedTestEndpointId = null
            playbackController?.startAppCapture(requireNotNull(app), selectedRoutes)
        } catch (invalid: IllegalArgumentException) { mutableState.update { it.copy(feedback = invalid.message) } }
    }

    /** Toggles the selected real-device test; stopping also cancels pending native startup. */
    private fun testSpeaker() {
        val selected = state.value.selectedSpeaker ?: return
        if (state.value.testingSpeakerId == selected.id) {
            stopPlayback()
            mutableState.update { it.copy(feedback = "Speaker test stopped.") }
            return
        }
        val output = selected.output
        if (state.value.demoMode || output == null || playbackController == null) {
            mutableState.update { it.copy(feedback = "Demo speakers cannot play audio. Select a real Windows output to test.") }
            return
        }
        if (output.availability != OutputAvailability.Active || state.value.outputs.status != AudioOutputStatus.Ready) {
            mutableState.update { it.copy(feedback = "This output is unavailable. Reconnect it and refresh before testing.") }
            return
        }
        if (output.role != AudioOutputRole.PlaybackTarget) {
            mutableState.update { it.copy(feedback = "The AllConnect virtual output cannot be a physical speaker-test destination.") }; return
        }
        val id = selected.id
        requestedTestEndpointId = output.endpointId
        playbackController.testSpeaker(PlaybackRoute(output.endpointId, selected.channel, selected.volume, selected.delayMillis, selected.eq))
        mutableState.update { it.copy(testingSpeakerId = id,
            feedback = "${SPEAKER_TEST_DURATION_MILLIS / 1000}-second test requested for ${selected.name}. App volume: ${selected.volume}%. Press the test button again to stop early.") }
    }
}
