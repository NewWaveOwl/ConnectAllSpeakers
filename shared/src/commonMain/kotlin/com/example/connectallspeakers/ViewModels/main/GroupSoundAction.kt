package com.example.connectallspeakers.ViewModels.main

/** Explicit group intents, all executed by the existing application audio owner. */
sealed interface GroupSoundAction {
    data class SetView(val view: com.example.connectallspeakers.state.GroupSoundView) : GroupSoundAction
    data object ManageDevices : GroupSoundAction
    data object ChooseSource : GroupSoundAction
    data object AdjustTiming : GroupSoundAction
    /** @property endpointId Existing group member to inspect; selection never starts or stops audio. */
    data class SelectDevice(val endpointId: String) : GroupSoundAction
    /** @property query Session-only roster filter; does not remove or reroute devices. */
    data class SearchDevices(val query: String) : GroupSoundAction
    /** @property endpointId Exact physical endpoint, validated against fresh discovery. */
    data class AddDevice(val endpointId: String) : GroupSoundAction
    /** @property endpointId Selected endpoint to remove without changing other settings. */
    data class RemoveDevice(val endpointId: String) : GroupSoundAction
    /** @property endpointId Selected endpoint. @property percent App gain, clamped to 0..100. */
    data class SetVolume(val endpointId: String, val percent: Int) : GroupSoundAction
    /** @property endpointId Selected endpoint; membership/level survive mute. */
    data class ToggleMute(val endpointId: String) : GroupSoundAction
    /** @property endpointId Selected endpoint. @property millis Whole milliseconds, 0..500. */
    data class SetDelay(val endpointId: String, val millis: Int) : GroupSoundAction
    /** @property endpointId Selected real endpoint to test for ten seconds, replacing any source. */
    data class TestDevice(val endpointId: String) : GroupSoundAction
}
