package com.example.connectallspeakers.state

import com.example.connectallspeakers.model.RoomSetup
import com.example.connectallspeakers.model.SampleSpeaker
import com.example.connectallspeakers.model.sampleSetups

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
 */
data class MainUiState(
    val setups: List<RoomSetup> = sampleSetups(),
    val selectedSetupId: String? = "living",
    val savedSetups: Map<String, RoomSetup> = emptyMap(),
    val dialog: MainDialog? = null,
    val dialogName: String = "",
    val dialogError: String? = null,
    val feedback: String? = null,
    val testingSpeakerId: String? = null,
) {
    /** Active room, absent when all rooms have been deleted. */
    val selectedSetup: RoomSetup? get() = setups.find { it.id == selectedSetupId }

    /** Selected sample in the active room, absent for an empty room. */
    val selectedSpeaker: SampleSpeaker?
        get() = selectedSetup?.let { room -> room.speakers.find { it.id == room.selectedSpeakerId } }
}
