package com.example.connectallspeakers.state

import com.example.connectallspeakers.model.RoomSetup
import com.example.connectallspeakers.model.RoomSpeaker
import com.example.connectallspeakers.model.emptySetups
import com.example.connectallspeakers.model.sampleSetups
import com.example.connectallspeakers.data.audio.AudioOutputSnapshot
import com.example.connectallspeakers.data.audio.AudioOutputStatus

/** Lightweight modal destinations, confined to this screen. */
enum class MainDialog { AddSetup, RenameSetup, DeleteSetup, AddSpeaker }

/** One authoritative, immutable frontend session snapshot.
 * @property setups Current independently edited rooms.
 * @property selectedSetupId Active room, or null when none remain.
 * @property savedSetups In-memory copies captured by Save setup; never written to disk.
 * @property dialog Currently presented modal, if any.
 * @property dialogName Draft setup name retained while a modal is visible.
 * @property dialogError Validation feedback, if any.
 * @property feedback Durable feedback until explicitly dismissed or replaced.
 * @property testingSpeakerId Temporary visual-only test highlight.
 * @property demoMode Whether the user explicitly selected the isolated sample workspace.
 * @property outputs Current real-output discovery state; never replaced with demo fixtures.
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
) {
    /** Active room, absent when all rooms have been deleted. */
    val selectedSetup: RoomSetup? get() = setups.find { it.id == selectedSetupId }

    /** Selected room card, absent for an empty room. */
    val selectedSpeaker: RoomSpeaker?
        get() = selectedSetup?.let { room -> room.speakers.find { it.id == room.selectedSpeakerId } }

    companion object {
        /** Explicit sample fixture for previews and frontend tests.
         * @return An isolated demo workspace with no hardware claims.
         */
        fun demo(): MainUiState = MainUiState(setups = sampleSetups(), demoMode = true)
    }
}
