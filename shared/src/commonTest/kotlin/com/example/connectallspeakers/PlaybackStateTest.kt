package com.example.connectallspeakers

import com.example.connectallspeakers.ViewModels.main.*
import com.example.connectallspeakers.data.audio.*
import com.example.connectallspeakers.model.RoomPosition
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import com.example.connectallspeakers.state.MainDialog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Platform-neutral playback intents with controlled metadata; never invokes an audio device. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStateTest {
    private class Outputs : AudioOutputRepository {
        override val outputs = MutableStateFlow(AudioOutputSnapshot(AudioOutputStatus.Ready,
            listOf(AudioOutputDevice("a", "Same"), AudioOutputDevice("b", "Same"), AudioOutputDevice("c", "Third"))))
        override fun refresh() = Unit
    }
    private class Playback : AudioPlaybackController {
        override val playback = MutableStateFlow(PlaybackSnapshot(PlaybackPhase.Ready, PlaybackSource("test.wav", 1000, 48_000, 2)))
        val played = mutableListOf<List<PlaybackRoute>>()
        val updated = mutableListOf<List<PlaybackRoute>>()
        val tested = mutableListOf<PlaybackRoute>()
        var stopped = 0
        var paused = 0
        override fun loadFile(path: String) = Unit
        override fun play(routes: List<PlaybackRoute>) { played += routes }
        override fun pause() { paused++ }
        override fun stop() { stopped++ }
        override fun updateRoutes(routes: List<PlaybackRoute>) { updated += routes }
        override fun testSpeaker(route: PlaybackRoute) { tested += route }
    }
    private fun TestScope.owner(outputs: Outputs, playback: Playback, choose: () -> Unit = {}) =
        MainViewModel(outputRepository = outputs, collectionScope = backgroundScope, playbackController = playback, chooseAudioFile = choose)

    @Test fun explicitPlayUsesEndpointIdentityAndSelectedEditsImmediatelyUpdateRoutes() = runTest {
        val playback = Playback()
        val owner = owner(Outputs(), playback)
        runCurrent()
        owner.onAction(MainAction.AddOutput("a")); owner.onAction(MainAction.AssignChannel(SpeakerChannel.Left))
        owner.onAction(MainAction.AddOutput("b")); owner.onAction(MainAction.AssignChannel(SpeakerChannel.Right))
        owner.onAction(MainAction.SetVolume(37)); owner.onAction(MainAction.RenameSpeaker("My speaker"))
        owner.onAction(MainAction.Play)
        assertEquals(listOf(PlaybackRoute("a", SpeakerChannel.Left, 80), PlaybackRoute("b", SpeakerChannel.Right, 37)), playback.played.single())
        owner.onAction(MainAction.SetVolume(0))
        assertEquals(0, playback.updated.last().last().volumePercent)
        assertEquals(80, playback.updated.last().first().volumePercent)
        owner.onAction(MainAction.Pause); assertEquals(1, playback.paused)
    }
    @Test fun movingSelectingRenamingAndResettingDoNotChangeAudioRoutingOrRestartStreams() = runTest {
        val playback = Playback()
        val owner = owner(Outputs(), playback)
        runCurrent(); owner.onAction(MainAction.AddOutput("a"))
        val selected = owner.state.value.selectedSpeaker!!
        val stops = playback.stopped
        owner.onAction(MainAction.MoveSpeaker(selected.id, RoomPosition(.8f, .7f)))
        owner.onAction(MainAction.SelectSpeaker(selected.id)); owner.onAction(MainAction.RenameSpeaker("Renamed"))
        owner.onAction(MainAction.ResetPositions)
        assertEquals(stops, playback.stopped)
        assertTrue(playback.played.isEmpty())
        assertTrue(playback.updated.isEmpty())
        assertEquals(selected.channel, owner.state.value.selectedSpeaker!!.channel)
    }
    @Test fun roomAndDemoSwitchStopPlaybackAndRetainIndependentSettings() = runTest {
        val playback = Playback()
        val owner = owner(Outputs(), playback)
        runCurrent(); owner.onAction(MainAction.AddOutput("a")); owner.onAction(MainAction.SetVolume(21))
        var stops = playback.stopped
        owner.onAction(MainAction.SelectSetup("desk")); assertEquals(++stops, playback.stopped)
        owner.onAction(MainAction.AddOutput("a")); owner.onAction(MainAction.SetVolume(73))
        owner.onAction(MainAction.SelectSetup("living"))
        assertEquals(21, owner.state.value.selectedSpeaker!!.volume)
        stops = playback.stopped
        owner.onAction(MainAction.SetDemoMode(true)); assertEquals(stops + 1, playback.stopped)
        owner.onAction(MainAction.Play); owner.onAction(MainAction.TestSpeaker)
        assertTrue(playback.played.isEmpty()); assertTrue(playback.tested.isEmpty())
    }
    @Test fun missingUnavailableUnknownAndTooManyOutputsBlockWholeRoomPlayback() = runTest {
        val outputs = Outputs()
        val playback = Playback()
        val owner = owner(outputs, playback)
        runCurrent(); owner.onAction(MainAction.Play)
        assertTrue(playback.played.isEmpty())
        owner.onAction(MainAction.AddOutput("a"))
        val original = outputs.outputs.value
        outputs.outputs.value = original.copy(devices = emptyList()); runCurrent()
        owner.onAction(MainAction.Play); assertTrue(playback.played.isEmpty())
        outputs.outputs.value = original.copy(status = AudioOutputStatus.Error); runCurrent()
        owner.onAction(MainAction.Play); assertTrue(playback.played.isEmpty())
        outputs.outputs.value = original; runCurrent()
        owner.onAction(MainAction.AddOutput("b")); owner.onAction(MainAction.AddOutput("c"))
        owner.onAction(MainAction.Play); assertTrue(playback.played.isEmpty())
        assertNotNull(owner.state.value.feedback)
    }
    @Test fun pickerIsExplicitAndEngineStateAndTestFeedbackReachScreen() = runTest {
        val playback = Playback()
        var chosen = 0
        val owner = owner(Outputs(), playback) { chosen++ }
        runCurrent(); assertEquals(0, chosen)
        owner.onAction(MainAction.ChooseAudioFile); assertEquals(1, chosen)
        owner.onAction(MainAction.AddOutput("b")); owner.onAction(MainAction.SetVolume(25))
        owner.onAction(MainAction.TestSpeaker)
        assertEquals(PlaybackRoute("b", SpeakerChannel.Mono, 25), playback.tested.single())
        val id = owner.state.value.selectedSpeaker!!.id
        assertEquals(id, owner.state.value.testingSpeakerId)
        playback.playback.value = PlaybackSnapshot(PlaybackPhase.Error, message = "Output disconnected.")
        runCurrent(); assertEquals("Output disconnected.", owner.state.value.playback.message)
        advanceTimeBy(1401); runCurrent(); assertNull(owner.state.value.testingSpeakerId)
        owner.onAction(MainAction.SetDemoMode(true)); owner.onAction(MainAction.ChooseAudioFile)
        assertEquals(1, chosen)
    }
    @Test fun deletingCurrentRoomStopsAndAddingOutputDoesNotAutoplay() = runTest {
        val playback = Playback()
        val owner = owner(Outputs(), playback)
        runCurrent(); owner.onAction(MainAction.AddOutput("a"))
        val stops = playback.stopped
        owner.onAction(MainAction.OpenDialog(MainDialog.DeleteSetup)); owner.onAction(MainAction.ConfirmDialog)
        assertEquals(stops + 1, playback.stopped)
        assertTrue(playback.played.isEmpty())
    }
    @Test fun testSettingsTargetOnlyRequestedOutputBeforeEngineStateArrives() = runTest {
        val playback = Playback()
        val owner = owner(Outputs(), playback)
        runCurrent(); owner.onAction(MainAction.AddOutput("a")); owner.onAction(MainAction.AddOutput("b"))
        owner.onAction(MainAction.TestSpeaker)
        owner.onAction(MainAction.SetVolume(0))
        assertEquals(listOf(PlaybackRoute("b", SpeakerChannel.Mono, 0)), playback.updated.last())
        owner.onAction(MainAction.Play)
        owner.onAction(MainAction.SetVolume(50))
        assertEquals(setOf("a", "b"), playback.updated.last().map { it.endpointId }.toSet())
    }
}
