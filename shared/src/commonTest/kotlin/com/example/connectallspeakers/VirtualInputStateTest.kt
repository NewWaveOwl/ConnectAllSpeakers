package com.example.connectallspeakers

import com.example.connectallspeakers.ViewModels.main.*
import com.example.connectallspeakers.data.audio.*
import com.example.connectallspeakers.data.profiles.*
import com.example.connectallspeakers.model.*
import com.example.connectallspeakers.model.audio.*
import com.example.connectallspeakers.state.MainUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Portable live source/target selection with controlled metadata; never uses Windows bindings. */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualInputStateTest {
    private class Outputs : AudioOutputRepository {
        override val outputs = MutableStateFlow(AudioOutputSnapshot(AudioOutputStatus.Ready, listOf(
            AudioOutputDevice("input", "Renamed speaker", role = AudioOutputRole.AllConnectInput),
            AudioOutputDevice("a", "AllConnect Speakers"), AudioOutputDevice("b", "AllConnect Speakers"),
            AudioOutputDevice("unknown", "AllConnect Speakers", role = AudioOutputRole.Unknown))))
        override fun refresh() = Unit
    }
    private class Playback(override val supportsVirtualInput: Boolean = true) : AudioPlaybackController {
        override val playback = MutableStateFlow(PlaybackSnapshot(PlaybackPhase.Empty))
        val live = mutableListOf<Pair<String, List<PlaybackRoute>>>()
        val updated = mutableListOf<List<PlaybackRoute>>()
        var stopped = 0
        var otherAudio = 0
        override fun startVirtualInput(endpointId: String, routes: List<PlaybackRoute>) { live += endpointId to routes }
        override fun loadFile(path: String) { otherAudio++ }
        override fun play(routes: List<PlaybackRoute>, driftReferenceEndpointId: String?) { otherAudio++ }
        override fun testSpeaker(route: PlaybackRoute) { otherAudio++ }
        override fun calibrate(routes: List<PlaybackRoute>, referenceEndpointId: String) { otherAudio++ }
        override fun pause() = Unit
        override fun stop() { stopped++ }
        override fun updateRoutes(routes: List<PlaybackRoute>) { updated += routes }
    }

    @Test fun exactOwnershipNotNamesControlsInputAndTargetsAndNothingAutostarts() = runTest {
        val output = Outputs(); val playback = Playback()
        val owner = MainViewModel(outputRepository = output, playbackController = playback, collectionScope = backgroundScope)
        runCurrent()
        owner.onAction(MainAction.AddOutput("input")); owner.onAction(MainAction.AddOutput("unknown"))
        assertTrue(owner.state.value.selectedSetup!!.speakers.isEmpty())
        owner.onAction(MainAction.AddOutput("a")); owner.onAction(MainAction.AssignChannel(SpeakerChannel.Left))
        owner.onAction(MainAction.AddOutput("b")); owner.onAction(MainAction.AssignChannel(SpeakerChannel.Right))
        owner.onAction(MainAction.SetVolume(25)); owner.onAction(MainAction.RenameSpeaker("My right speaker"))
        owner.onAction(MainAction.SetLiveInputMode(true))
        owner.onAction(MainAction.SelectVirtualInput("a")); assertNull(owner.state.value.virtualInputId)
        owner.onAction(MainAction.SelectVirtualInput("input"))
        assertTrue(playback.live.isEmpty())
        owner.onAction(MainAction.Play); owner.onAction(MainAction.ChooseAudioFile)
        assertEquals(0, playback.otherAudio)
        owner.onAction(MainAction.StartVirtualInput)
        assertEquals("input", playback.live.single().first)
        assertEquals(listOf(PlaybackRoute("a", SpeakerChannel.Left, 80), PlaybackRoute("b", SpeakerChannel.Right, 25)), playback.live.single().second)
        owner.onAction(MainAction.SetVolume(37))
        assertEquals(37, playback.updated.last().last().volumePercent)
        assertEquals("My right speaker", owner.state.value.selectedSpeaker?.name)
        owner.onAction(MainAction.SetLiveInputMode(false))
        assertTrue(playback.stopped > 0)
        assertEquals(1, playback.live.size)
    }

    @Test fun missingInputErrorsDisconnectedTargetsDemoAndUnsupportedPlatformsBlockStart() = runTest {
        val outputs = Outputs(); val playback = Playback()
        val owner = MainViewModel(outputRepository = outputs, playbackController = playback, collectionScope = backgroundScope)
        runCurrent(); owner.onAction(MainAction.SetLiveInputMode(true))
        owner.onAction(MainAction.StartVirtualInput); assertTrue(playback.live.isEmpty())
        owner.onAction(MainAction.SelectVirtualInput("input"))
        owner.onAction(MainAction.StartVirtualInput); assertTrue(playback.live.isEmpty())
        owner.onAction(MainAction.AddOutput("a"))
        val original = outputs.outputs.value
        for (snapshot in listOf(original.copy(status = AudioOutputStatus.Error), original.copy(devices = original.devices.filterNot { it.endpointId == "a" }),
            original.copy(devices = original.devices.filterNot { it.endpointId == "input" }))) {
            outputs.outputs.value = snapshot; runCurrent()
            owner.onAction(MainAction.StartVirtualInput); assertTrue(playback.live.isEmpty())
        }
        outputs.outputs.value = original; runCurrent()
        assertTrue(playback.live.isEmpty()) // Reconnect never restarts.
        owner.onAction(MainAction.SetDemoMode(true)); owner.onAction(MainAction.StartVirtualInput)
        assertTrue(playback.live.isEmpty())
        val unsupported = Playback(false)
        val portable = MainViewModel(outputRepository = outputs, playbackController = unsupported, collectionScope = backgroundScope)
        runCurrent(); portable.onAction(MainAction.AddOutput("a")); portable.onAction(MainAction.SetLiveInputMode(true))
        portable.onAction(MainAction.SelectVirtualInput("input")); portable.onAction(MainAction.StartVirtualInput)
        assertTrue(unsupported.live.isEmpty()); assertFalse(portable.state.value.virtualInputSupported)
    }

    @Test fun restoredCardIsReclassifiedByEndpointMetadataAndCannotTestOrRouteToOwnedInput() = runTest {
        val playback = Playback(); val output = Outputs()
        val room = RoomSetup("room", "Restored", speakers = listOf(RoomSpeaker("card", "Custom label", SpeakerChannel.Left,
            25, RoomPosition(.2f, .3f), output = AudioOutputDevice("input", "Old OS name"))), selectedSpeakerId = "card")
        val owner = MainViewModel(MainUiState(setups = listOf(room), selectedSetupId = "room"), outputRepository = output,
            playbackController = playback, collectionScope = backgroundScope)
        runCurrent()
        assertEquals(AudioOutputRole.AllConnectInput, owner.state.value.selectedSpeaker?.output?.role)
        assertEquals("Custom label", owner.state.value.selectedSpeaker?.name)
        owner.onAction(MainAction.Play); owner.onAction(MainAction.TestSpeaker)
        owner.onAction(MainAction.SetLiveInputMode(true)); owner.onAction(MainAction.SelectVirtualInput("input"))
        owner.onAction(MainAction.StartVirtualInput)
        assertTrue(playback.live.isEmpty()); assertEquals(0, playback.otherAudio)
    }

    @Test fun profileRoundTripPersistsRoomEditsWithoutCaptureModeIdentityOrAutoplay() = runTest {
        var saved: com.example.connectallspeakers.model.profiles.SetupProfile? = null
        val storage = object : SetupProfileRepository {
            override suspend fun load() = saved
            override suspend fun save(profile: com.example.connectallspeakers.model.profiles.SetupProfile) { saved = SetupProfileCodec.decode(SetupProfileCodec.encode(profile)) }
        }
        val playback = Playback(); val output = Outputs()
        val owner = MainViewModel(outputRepository = output, playbackController = playback, profileRepository = storage, collectionScope = backgroundScope)
        runCurrent(); owner.onAction(MainAction.AddOutput("a")); owner.onAction(MainAction.SetVolume(31))
        owner.onAction(MainAction.SetLiveInputMode(true)); owner.onAction(MainAction.SelectVirtualInput("input"))
        owner.onAction(MainAction.SaveSetup); runCurrent()
        val reopened = MainViewModel(outputRepository = output, playbackController = playback, profileRepository = storage, collectionScope = backgroundScope)
        runCurrent()
        assertEquals(31, reopened.state.value.selectedSpeaker?.volume)
        assertFalse(reopened.state.value.liveInputMode); assertNull(reopened.state.value.virtualInputId)
        assertTrue(playback.live.isEmpty()); assertEquals(0, playback.otherAudio)
    }
}
