package com.example.connectallspeakers.state

import com.example.connectallspeakers.model.audio.CaptureApp

import com.example.connectallspeakers.model.RoomSetup
import com.example.connectallspeakers.model.RoomSpeaker
import com.example.connectallspeakers.model.emptySetups
import com.example.connectallspeakers.model.sampleSetups
import com.example.connectallspeakers.data.audio.AudioOutputSnapshot
import com.example.connectallspeakers.data.audio.AudioOutputStatus
import com.example.connectallspeakers.model.audio.PlaybackSnapshot
import com.example.connectallspeakers.data.profiles.hasUnsavedChanges

/** Lightweight modal destinations, confined to this screen. */
enum class MainDialog { AddSetup, RenameSetup, DeleteSetup, AddSpeaker, Settings, ReloadSetups, SpeakerEq }

/** Storage readiness, independent of native device discovery and playback. */
enum class ProfileStorageStatus { SessionOnly, Loading, Ready, LoadFailed }

/** One authoritative, immutable frontend session snapshot.
 * @property workspace Active presentation; switching stops audio.
 * @property group Session-only group membership and listening settings, excluded from profiles.
 * @property setups Current independently edited rooms.
 * @property selectedSetupId Active room, or null when none remain.
 * @property savedSetups Last successfully saved snapshots for the active bank; independent of unsaved edits.
 * @property dialog Currently presented modal, if any.
 * @property dialogName Draft setup name retained while a modal is visible.
 * @property dialogError Validation feedback, if any.
 * @property feedback Durable feedback until explicitly dismissed or replaced.
 * @property testingSpeakerId Card highlighted during the requested/active test, cleared on completion or stop.
 * @property demoMode Whether the user explicitly selected the isolated sample workspace.
 * @property outputs Current real-output discovery state; never replaced with demo fixtures.
 * @property playback Platform playback state; no audio samples or local paths are exposed.
 * @property profileStorage Storage readiness; SessionOnly only on launchers without a storage repository.
 * @property savingProfiles Whether an explicit save or durable deletion is in progress.
 * @property profileError Recoverable storage feedback, retained until a successful load/save.
 * @property liveInputMode Explicit session-only source choice; never restored as an autoplay intent.
 * @property virtualInputId Explicit driver-owned input selection, independent of physical room routes.
 * @property virtualInputSupported Whether this platform supplies a real capture implementation.
 * @property appCaptureMode Whether the explicitly selected live source is a process tree.
 * @property appCaptureSupported Whether the platform supplies process-loopback capture.
 * @property captureApps Temporary readable-process discovery results, never saved in room profiles.
 * @property selectedCaptureApp Exact process/creation identity chosen without starting capture.
 * @property captureAppsLoading Whether an explicit app refresh is in progress.
 * @property captureAppsError Readable discovery failure, independent of physical-output discovery.
 */
data class MainUiState(
    val workspace: WorkspaceMode = WorkspaceMode.Room,
    val group: GroupSoundState = GroupSoundState(),
    val setups: List<RoomSetup> = emptySetups(),
    val selectedSetupId: String? = "living",
    val savedSetups: Map<String, RoomSetup> = emptyMap(),
    val dialog: MainDialog? = null,
    val dialogName: String = "",
    val dialogError: String? = null,
    val feedback: String? = null,
    val testingSpeakerId: String? = null,
    val demoMode: Boolean = false,
    val outputs: AudioOutputSnapshot = AudioOutputSnapshot(AudioOutputStatus.Unsupported,
        message = "Windows output discovery is unavailable on this platform."),
    val playback: PlaybackSnapshot = PlaybackSnapshot(),
    val profileStorage: ProfileStorageStatus = ProfileStorageStatus.SessionOnly,
    val savingProfiles: Boolean = false,
    val profileError: String? = null,
    val liveInputMode: Boolean = false,
    val appCaptureMode: Boolean = false,
    val appCaptureSupported: Boolean = false,
    val captureApps: List<CaptureApp> = emptyList(),
    val selectedCaptureApp: CaptureApp? = null,
    val captureAppsLoading: Boolean = false,
    val captureAppsError: String? = null,
    val virtualInputId: String? = null,
    val virtualInputSupported: Boolean = false,
) {
    /** Active room, absent when all rooms have been deleted. */
    val selectedSetup: RoomSetup? get() = setups.find { it.id == selectedSetupId }

    /** Selected room card, absent for an empty room. */
    val selectedSpeaker: RoomSpeaker?
        get() = selectedSetup?.let { room -> room.speakers.find { it.id == room.selectedSpeakerId } }

    /** Metadata notifications do not make saved user settings dirty. */
    val hasUnsavedSetup: Boolean get() = selectedSetup?.hasUnsavedChanges(savedSetups[selectedSetupId]) == true

    companion object {
        /** Explicit sample fixture for previews and frontend tests.
         * @return An isolated demo workspace with no hardware claims.
         */
        fun demo(): MainUiState = MainUiState(setups = sampleSetups(), demoMode = true)
    }
}
