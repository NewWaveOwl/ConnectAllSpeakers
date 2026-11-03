package com.example.connectallspeakers.model.profiles

import com.example.connectallspeakers.model.RoomPosition
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.MAX_OUTPUT_DELAY_MILLIS
import com.example.connectallspeakers.model.audio.SpeakerEq

/** Maximum UTF-8 profile size, including framing; enforced before parsing and writing. */
const val MAX_PROFILE_BYTES = 1_048_576

/** Explicit storage banks; a demo card can never become a Windows endpoint. */
enum class ProfileMode { Real, Demo }

/** Durable card settings; contains no native resources or live Windows metadata.
 * @property id Stable card identity within its bank.
 * @property name User-editable label, independent of the endpoint name.
 * @property channel Deliberate source-channel assignment.
 * @property volume App gain in whole percent, 0..100.
 * @property position Normalized current card travel.
 * @property homePosition Normalized Reset positions target.
 * @property endpointId Opaque Windows endpoint identity; null only in the demo bank.
 * @property endpointName Last saved display label, used until discovery supplies fresh metadata.
 * @property delayMillis Manual delay in whole milliseconds, 0..500.
 * @property extensions Optional future settings, retained verbatim; keys start with ext., for example ext.eq.
 * @property eq Validated per-card EQ; older schema-one profiles migrate to neutral/bypassed defaults.
 */
data class SpeakerProfile(
    val id: String, val name: String, val channel: SpeakerChannel, val volume: Int,
    val position: RoomPosition, val homePosition: RoomPosition,
    val endpointId: String? = null, val endpointName: String? = null, val delayMillis: Int = 0,
    val extensions: Map<String, String> = emptyMap(),
    val eq: SpeakerEq = SpeakerEq(),
)

/** One explicitly saved room, independent of later in-memory edits.
 * @property id Stable room identity within its bank.
 * @property name Saved room label.
 * @property speakers Saved card settings in rendering order.
 * @property selectedSpeakerId Selected card, or null.
 * @property snapToGrid Saved grid preference.
 * @property zoom Visual zoom fraction, 0.75..1.5.
 * @property referenceSpeakerId Zero-delay calibration reference, or null for the first card.
 * @property driftCorrectionEnabled User opt-in only; no estimator state or playback intent is stored.
 * @property extensions Optional future room settings with ext. keys.
 */
data class RoomProfile(
    val id: String, val name: String, val speakers: List<SpeakerProfile> = emptyList(),
    val selectedSpeakerId: String? = null, val snapToGrid: Boolean = true, val zoom: Float = 1f,
    val referenceSpeakerId: String? = null, val driftCorrectionEnabled: Boolean = false,
    val extensions: Map<String, String> = emptyMap(),
)

/** A saved workspace bank; an empty list is intentional and must not restore default rooms.
 * @property rooms Only explicitly saved rooms, in workspace order.
 * @property selectedRoomId Saved selection, null exactly when no rooms remain.
 */
data class ProfileBank(val rooms: List<RoomProfile> = emptyList(), val selectedRoomId: String? = null)

/** Durable workspace; the codec reads version one and writes version two with typed EQ.
 * @property real Real endpoint assignments, independent of demo fixtures.
 * @property demo Explicitly saved sample workspace.
 * @property mode Active bank at the last successful save/delete.
 */
data class SetupProfile(
    val real: ProfileBank = ProfileBank(), val demo: ProfileBank = ProfileBank(),
    val mode: ProfileMode = ProfileMode.Real,
) {
    /** Rejects the entire invalid profile rather than repairing identities or redirecting outputs.
     * @throws IllegalArgumentException For invalid settings, duplicate identities or excessive counts.
     */
    fun validate() {
        for ((bank, bankMode) in listOf(real to ProfileMode.Real, demo to ProfileMode.Demo)) {
            require(bank.rooms.size <= 64) { "At most 64 saved rooms per mode are supported." }
            require((bank.rooms.isEmpty() && bank.selectedRoomId == null) || bank.rooms.any { it.id == bank.selectedRoomId }) {
                "Invalid saved room selection."
            }
            val identities = mutableSetOf<String>()
            for (room in bank.rooms) {
                identity(room.id); require(identities.add(room.id)) { "Duplicate saved identity." }
                require(room.name.isNotBlank() && room.name.length <= 60) { "Room names must contain 1–60 characters." }
                require(room.zoom.isFinite() && room.zoom in .75f..1.5f) { "Invalid saved zoom." }
                require(room.speakers.size <= 64) { "At most 64 cards per saved room are supported." }
                require(room.selectedSpeakerId == null || room.speakers.any { it.id == room.selectedSpeakerId }) { "Invalid selected card." }
                require(room.referenceSpeakerId == null || room.speakers.any { it.id == room.referenceSpeakerId }) { "Invalid calibration reference." }
                extensions(room.extensions)
                val endpoints = mutableSetOf<String>()
                for (speaker in room.speakers) {
                    identity(speaker.id); require(identities.add(speaker.id)) { "Duplicate saved identity." }
                    require(speaker.name.isNotBlank() && speaker.name.length <= 80) { "Speaker names must contain 1–80 characters." }
                    require(speaker.volume in 0..100) { "Saved volume must be 0–100%." }
                    require(speaker.delayMillis in 0..MAX_OUTPUT_DELAY_MILLIS) { "Invalid saved delay." }
                    position(speaker.position); position(speaker.homePosition); extensions(speaker.extensions)
                    if (bankMode == ProfileMode.Real) {
                        require(!speaker.endpointId.isNullOrBlank() && speaker.endpointId.length <= 4096) { "Missing or oversized endpoint identity." }
                        require(endpoints.add(speaker.endpointId)) { "Duplicate endpoint in a saved room." }
                        require(!speaker.endpointName.isNullOrBlank() && speaker.endpointName.length <= 4096) { "Invalid saved endpoint label." }
                    } else require(speaker.endpointId == null && speaker.endpointName == null && speaker.delayMillis == 0) { "Demo cards cannot contain hardware or calibration." }
                }
                val reference = room.speakers.find { it.id == room.referenceSpeakerId } ?: room.speakers.firstOrNull()
                require(reference == null || reference.delayMillis == 0) { "Saved calibration reference must have zero delay." }
                require(bankMode == ProfileMode.Real || (!room.driftCorrectionEnabled && room.referenceSpeakerId == null)) { "Demo rooms cannot enable drift correction." }
            }
        }
    }
}

/** @param value Unparsed instance ID. @throws IllegalArgumentException For a blank/oversized ID. */
private fun identity(value: String) { require(value.isNotBlank() && value.length <= 256) { "Invalid saved identity." } }

/** @param value Normalized position. @throws IllegalArgumentException For non-finite/out-of-range coordinates. */
private fun position(value: RoomPosition) {
    require(value.x.isFinite() && value.y.isFinite() && value.x in 0f..1f && value.y in 0f..1f) { "Invalid saved card position." }
}

/** @param values Bounded optional fields. @throws IllegalArgumentException For unsafe extension sizes/keys. */
private fun extensions(values: Map<String, String>) {
    require(values.size <= 32 && values.all { (key, value) ->
        key.startsWith("ext.") && key.length in 5..80 && key.all { it.isLetterOrDigit() || it in "._-" } && value.length <= 4096
    }) { "Invalid optional profile settings." }
}
