package com.example.connectallspeakers.state

import com.example.connectallspeakers.data.audio.*
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*

/** Workspace changes always stop the one application-owned audio session. */
enum class WorkspaceMode { Room, GroupSound }

/** Session-only listening preferences; never serialized in room profiles.
 * @property output Exact discovered endpoint, retained as unavailable after loss.
 * @property volumePercent App gain independent of Windows volume. @property muted Retains membership/level.
 * @property delayMillis Manual delay, applied after explicit restart. @property eq Per-device settings.
 */
data class GroupDevice(val output: AudioOutputDevice, val volumePercent: Int = 80,
    val muted: Boolean = false, val delayMillis: Int = 0, val eq: SpeakerEq = SpeakerEq()) {
    /** @return An explicit full-mix route; stereo devices preserve L/R, mono devices average them. */
    fun route(): PlaybackRoute = PlaybackRoute(output.endpointId, SpeakerChannel.Mono, volumePercent,
        delayMillis, eq, PlaybackMix.FullStereo, muted)
}

/** Portable transient group view state. @property devices At most two explicit physical outputs.
 * @property managingDevices Whether the real-device picker is shown. @property choosingSource Source controls visibility.
 * @property adjustingTiming Whether manual timing controls are shown.
 */
data class GroupSoundState(val devices: List<GroupDevice> = emptyList(), val managingDevices: Boolean = false,
    val choosingSource: Boolean = false, val adjustingTiming: Boolean = false) {
    /** Validates fresh discovery; duplicate/unknown identities fail closed, never use the default endpoint.
     * @param outputs Current discovery authority. @return Readable error, or null for one/two valid routes.
     */
    fun routeError(outputs: AudioOutputSnapshot): String? = when {
        devices.size !in 1..MAX_PLAYBACK_OUTPUTS -> "Choose one or two physical listening devices."
        devices.map { it.output.endpointId }.distinct().size != devices.size -> "Each listening device must be unique."
        outputs.status != AudioOutputStatus.Ready -> "Refresh Windows outputs successfully first."
        devices.any { selected -> outputs.devices.singleOrNull { it.endpointId == selected.output.endpointId }
            ?.let { it.availability == OutputAvailability.Active && it.role == AudioOutputRole.PlaybackTarget } != true } ->
            "A listening device is missing or cannot be identified safely. Reconnect it, refresh, then explicitly restart."
        else -> null
    }
}
