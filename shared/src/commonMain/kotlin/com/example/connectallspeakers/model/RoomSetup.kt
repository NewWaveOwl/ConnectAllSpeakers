package com.example.connectallspeakers.model

import com.example.connectallspeakers.model.audio.AudioOutputDevice
import com.example.connectallspeakers.model.audio.OutputAvailability

/** Explicit presentation channels; dragging never changes this assignment.
 * @property label Inspector label.
 * @property caption Short canvas description.
 */
enum class SpeakerChannel(val label: String, val caption: String) {
    Left("Left (L)", "L · Left channel"),
    Right("Right (R)", "R · Right channel"),
    Mono("Mono", "M · Mono channel"),
}

/** Position within the available card travel, in fractions from zero to one.
 * @property x Horizontal fraction; zero is the left edge.
 * @property y Vertical fraction; zero is the top edge.
 */
data class RoomPosition(val x: Float, val y: Float)

/** Room card with user settings separate from optional Windows metadata.
 * @property id Stable, session-unique room instance identity.
 * @property name Editable display name.
 * @property channel Explicit app channel assignment; moving a card never changes it.
 * @property volume App volume in percent, from 0 to 100; no audio is produced.
 * @property position Normalized top-left card travel.
 * @property homePosition Position restored by Reset positions.
 * @property output Windows endpoint metadata; null only for explicit demo samples.
 */
data class RoomSpeaker(
    val id: String,
    val name: String,
    val channel: SpeakerChannel,
    val volume: Int,
    val position: RoomPosition,
    val homePosition: RoomPosition = position,
    val output: AudioOutputDevice? = null,
) {
    /** True only for an explicit demo sample, never a discovered endpoint. */
    val isSample: Boolean get() = output == null
    /** Presentation status; failed discovery is distinguished from a known disconnection. */
    val availability: OutputAvailability get() = output?.availability ?: OutputAvailability.Active
}

/** Immutable room edits retained independently for this application session.
 * @property id Stable setup identity.
 * @property name User-visible room name.
 * @property speakers Sample instances owned by this setup.
 * @property selectedSpeakerId Selected instance, or null for an empty room.
 * @property snapToGrid Whether completed moves snap to the room grid.
 * @property zoom Visual scale from 0.75 to 1.5, keeping every card within the canvas.
 */
data class RoomSetup(
    val id: String,
    val name: String,
    val speakers: List<RoomSpeaker> = emptyList(),
    val selectedSpeakerId: String? = speakers.lastOrNull()?.id,
    val snapToGrid: Boolean = true,
    val zoom: Float = 1f,
)

/** Creates explicit sample fixtures with globally distinct instance IDs.
 * @return Three independent rooms; Living room starts with Studio Right selected.
 */
fun sampleSetups(): List<RoomSetup> = listOf(
    sampleRoom("living", "Living room"),
    sampleRoom("desk", "Desk stereo"),
    sampleRoom("evening", "Evening listening"),
)

/** Builds one demo room without sharing speaker identities with another room.
 * @param id Room identity used as the sample instance prefix.
 * @param name Initial setup name.
 * @return A stereo room matching the reference arrangement.
 */
private fun sampleRoom(id: String, name: String): RoomSetup = RoomSetup(
    id, name,
    listOf(
        RoomSpeaker("$id-left", "Studio Left", SpeakerChannel.Left, 80, RoomPosition(44f / 862f, 110f / 472f)),
        RoomSpeaker("$id-right", "Studio Right", SpeakerChannel.Right, 80, RoomPosition(818f / 862f, 110f / 472f)),
    ),
)

/** Creates real-mode rooms with no fabricated hardware assignments.
 * @return Empty rooms ready for explicit endpoint selection.
 */
fun emptySetups(): List<RoomSetup> = listOf(
    RoomSetup("living", "Living room"), RoomSetup("desk", "Desk stereo"), RoomSetup("evening", "Evening listening"),
)
