package com.example.connectallspeakers.data.profiles

import com.example.connectallspeakers.model.RoomSetup
import com.example.connectallspeakers.model.RoomSpeaker
import com.example.connectallspeakers.model.audio.AudioOutputDevice
import com.example.connectallspeakers.model.audio.OutputAvailability
import com.example.connectallspeakers.model.profiles.RoomProfile
import com.example.connectallspeakers.model.profiles.SpeakerProfile

/** Captures only editable settings and opaque endpoint references, retaining future optional fields.
 * @param previous Last durable version, used only to preserve extensions for unchanged identities.
 * @return Independent profile; native availability/default/type and timing are excluded.
 */
fun RoomSetup.toProfile(previous: RoomProfile? = null): RoomProfile = RoomProfile(
    id, name, speakers.map { speaker -> SpeakerProfile(speaker.id, speaker.name, speaker.channel, speaker.volume,
        speaker.position, speaker.homePosition, speaker.output?.endpointId, speaker.output?.displayName,
        speaker.delayMillis, previous?.speakers?.find { it.id == speaker.id }?.extensions?.toMap().orEmpty()) },
    selectedSpeakerId, snapToGrid, zoom, calibrationReferenceSpeakerId, driftCorrectionEnabled,
    previous?.extensions?.toMap().orEmpty(),
)

/** Restores room edits only; discovery, never the file, decides endpoint availability.
 * @return Independent room with Unknown Windows metadata and no playback intent.
 */
fun RoomProfile.toRoom(): RoomSetup = RoomSetup(id, name, speakers.map { speaker ->
    RoomSpeaker(speaker.id, speaker.name, speaker.channel, speaker.volume, speaker.position, speaker.homePosition,
        speaker.endpointId?.let { AudioOutputDevice(it, speaker.endpointName.orEmpty(), OutputAvailability.Unknown) },
        speaker.delayMillis)
}, selectedSpeakerId, snapToGrid, zoom, referenceSpeakerId, driftCorrectionEnabled)

/** Compares user edits without counting fresh Windows metadata as an unsaved change.
 * @param saved Last successfully saved room, if any.
 * @return True when an explicit Save is required to retain these edits.
 */
fun RoomSetup.hasUnsavedChanges(saved: RoomSetup?): Boolean {
    val current = toProfile()
    val old = saved?.toProfile() ?: return true
    return current.copy(speakers = current.speakers.map { it.copy(endpointName = null) }) !=
        old.copy(speakers = old.speakers.map { it.copy(endpointName = null) })
}
