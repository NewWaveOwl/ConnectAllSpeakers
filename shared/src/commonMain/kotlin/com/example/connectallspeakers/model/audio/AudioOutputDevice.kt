package com.example.connectallspeakers.model.audio

/** Endpoint presence, never evidence of playback. Unknown includes a failed refresh. */
enum class OutputAvailability { Active, Unavailable, Unknown }

/** Windows-reported endpoint form factor; transport is deliberately not guessed from names. */
enum class AudioOutputType {
    Speakers, Headphones, Headset, Handset, LineLevel, DigitalDisplay, Spdif,
    RemoteNetwork, DigitalPassthrough, Unknown,
}

/** Immutable OS metadata kept separate from room edits.
 * @property endpointId Opaque Windows endpoint ID, never parsed and never keyed by name.
 * @property displayName Windows friendly name, possibly shared by multiple endpoints.
 * @property availability Last established presence; Active means present and enabled only.
 * @property isDefault Whether this is the Windows multimedia-role playback default.
 * @property type Reliable form-factor metadata, or Unknown when absent/unrecognized.
 */
data class AudioOutputDevice(
    val endpointId: String,
    val displayName: String,
    val availability: OutputAvailability = OutputAvailability.Active,
    val isDefault: Boolean = false,
    val type: AudioOutputType = AudioOutputType.Unknown,
)
