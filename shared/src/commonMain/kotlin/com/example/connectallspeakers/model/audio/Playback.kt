package com.example.connectallspeakers.model.audio

import com.example.connectallspeakers.model.SpeakerChannel

/** Explicit limit for this first independent-output playback milestone. */
const val MAX_PLAYBACK_OUTPUTS = 2

/** Playback lifecycle. No state means that acoustic synchronization has been verified. */
enum class PlaybackPhase { Unsupported, Empty, Loading, Ready, Starting, Playing, Paused, Stopped, Completed, Error }

/** User-selected local source metadata; file paths and decoded samples stay in the desktop owner.
 * @property name File name without its directory, or the explicit test-tone label.
 * @property durationMillis Source duration in milliseconds.
 * @property sampleRate Source frames per second.
 * @property channels Source channel count, one or two.
 */
data class PlaybackSource(val name: String, val durationMillis: Long, val sampleRate: Int, val channels: Int)

/** One explicit output route, never a default-device substitution.
 * @property endpointId Opaque Windows render endpoint identity.
 * @property channel Source channel deliberately selected by the user.
 * @property volumePercent Linear amplitude gain in percent, 0..100; never Windows master volume.
 */
data class PlaybackRoute(val endpointId: String, val channel: SpeakerChannel, val volumePercent: Int) {
    init {
        require(endpointId.isNotBlank()) { "Choose an explicit playback output." }
        require(volumePercent in 0..100) { "Volume must be between 0 and 100%." }
    }
}

/** Immutable playback presentation; endpoint timing is nominal, not an acoustic measurement.
 * @property phase Current engine lifecycle.
 * @property source Presented source metadata, if any; a tone temporarily shows its own metadata
 * while the controller retains the loaded file separately for subsequent playback.
 * @property positionMillis Conservative queued-playback position, excluding unknown Bluetooth latency.
 * @property activeEndpointIds Exact outputs owned by the current playback session.
 * @property isTestTone Whether the current stream is a short, explicit speaker test.
 * @property message User-readable status or failure, without exposing local paths.
 */
data class PlaybackSnapshot(
    val phase: PlaybackPhase = PlaybackPhase.Unsupported,
    val source: PlaybackSource? = null,
    val positionMillis: Long = 0,
    val activeEndpointIds: Set<String> = emptySet(),
    val isTestTone: Boolean = false,
    val message: String? = "Playback is available in the Windows desktop app only.",
)
