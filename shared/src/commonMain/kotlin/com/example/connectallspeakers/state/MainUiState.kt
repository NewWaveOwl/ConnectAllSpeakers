package com.example.connectallspeakers.state

import com.example.connectallspeakers.model.RoomSetup
import com.example.connectallspeakers.model.RoomSpeaker
import com.example.connectallspeakers.model.emptySetups
import com.example.connectallspeakers.model.sampleSetups
import com.example.connectallspeakers.data.audio.AudioOutputSnapshot
import com.example.connectallspeakers.data.audio.AudioOutputStatus
import com.example.connectallspeakers.model.audio.PlaybackSnapshot
import com.example.connectallspeakers.data.profiles.hasUnsavedChanges

/** Lightweight modal destinations, confined to this screen. */
enum class MainDialog { AddSetup, RenameSetup, DeleteSetup, AddSpeaker, Settings, ReloadSetups }

/** Storage readiness, independent of native device discovery and playback. */
enum class ProfileStorageStatus { SessionOnly, Loading, Ready, LoadFailed }

/** One authoritative, immutable frontend session snapshot.
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
 */
data class MainUiState(
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
