package com.example.connectallspeakers

import com.example.connectallspeakers.ViewModels.main.*
import com.example.connectallspeakers.data.audio.*
import com.example.connectallspeakers.model.audio.*
import com.example.connectallspeakers.state.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** No Windows APIs: validates transient group intent, identity and single-engine ownership. */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupSoundStateTest {
    private class Outputs : AudioOutputRepository {
        override val outputs = MutableStateFlow(AudioOutputSnapshot(AudioOutputStatus.Ready,
            listOf(AudioOutputDevice("a", "Same name"), AudioOutputDevice("b", "Same name"),
                AudioOutputDevice("virtual", "Speaker", role = AudioOutputRole.AllConnectInput),
                AudioOutputDevice("unknown", "Speaker", role = AudioOutputRole.Unknown))))
        override fun refresh() = Unit
    }
    private class Playback : AudioPlaybackController {
        override val playback = MutableStateFlow(PlaybackSnapshot(PlaybackPhase.Ready, PlaybackSource("fixture.wav", 1000, 48000, 2)))
        override val supportsAppCapture = true
        override val supportsVirtualInput = true
        override suspend fun captureApps() = listOf(CaptureApp(42, 100, "player.exe"))
        override fun startAppCapture(app: CaptureApp, routes: List<PlaybackRoute>) { starts++; this.routes = routes }
        override fun startVirtualInput(endpointId: String, routes: List<PlaybackRoute>) { starts++; this.routes = routes }
        var starts = 0; var stops = 0; var pauses = 0; var tests = 0
        var routes = emptyList<PlaybackRoute>()
        override fun loadFile(path: String) = Unit
        override fun play(routes: List<PlaybackRoute>, driftReferenceEndpointId: String?) { starts++; this.routes = routes }
        override fun updateRoutes(routes: List<PlaybackRoute>) { this.routes = routes }
        override fun pause() { pauses++ }
        override fun stop() { stops++ }
        override fun testSpeaker(route: PlaybackRoute) { tests++; routes = listOf(route) }
        override fun calibrate(routes: List<PlaybackRoute>, referenceEndpointId: String) = Unit
    }
    private fun MainViewModel.group(action: GroupSoundAction) = onAction(MainAction.Group(action))

    @Test fun groupPreservesBothSourceChannelsAndRoomEditsWhileNavigationNeverStarts() = runTest {
        val engine = Playback(); val outputs = Outputs()
        val owner = MainViewModel(outputRepository = outputs, playbackController = engine, collectionScope = backgroundScope)
        runCurrent()
        val room = owner.state.value.setups
        owner.onAction(MainAction.SelectWorkspace(WorkspaceMode.GroupSound))
        owner.group(GroupSoundAction.AddDevice("a")); owner.group(GroupSoundAction.AddDevice("b"))
        assertEquals(0, engine.starts)
        owner.onAction(MainAction.Play)
        assertEquals(1, engine.starts)
        assertEquals(setOf("a", "b"), engine.routes.map { it.endpointId }.toSet())
        assertTrue(engine.routes.all { it.mix == PlaybackMix.FullStereo })
        owner.onAction(MainAction.SelectWorkspace(WorkspaceMode.Room))
        assertEquals(room, owner.state.value.setups)
        assertEquals(2, owner.state.value.group.devices.size)
        assertEquals(1, engine.starts)
        assertTrue(engine.stops >= 2)
    }
    @Test fun invalidIdentitiesAndLayoutOnlyGroupsNeverStartPlayback() = runTest {
        val engine = Playback(); val outputs = Outputs()
        val owner = MainViewModel(outputRepository = outputs, playbackController = engine, collectionScope = backgroundScope)
        runCurrent(); owner.onAction(MainAction.SelectWorkspace(WorkspaceMode.GroupSound))
        for (id in listOf("virtual", "unknown", "missing")) owner.group(GroupSoundAction.AddDevice(id))
        assertTrue(owner.state.value.group.devices.isEmpty())
        owner.group(GroupSoundAction.AddDevice("a")); owner.group(GroupSoundAction.AddDevice("a"))
        assertEquals(1, owner.state.value.group.devices.size)
        owner.group(GroupSoundAction.AddDevice("b"))
        outputs.outputs.value = outputs.outputs.value.copy(devices = outputs.outputs.value.devices + AudioOutputDevice("c", "Third"))
        runCurrent(); owner.group(GroupSoundAction.AddDevice("c")); assertEquals(3, owner.state.value.group.devices.size)
        outputs.outputs.value = outputs.outputs.value.copy(devices = outputs.outputs.value.devices + AudioOutputDevice("a", "Ambiguous"))
        runCurrent(); owner.onAction(MainAction.Play); assertEquals(0, engine.starts)
        outputs.outputs.value = outputs.outputs.value.copy(devices = outputs.outputs.value.devices.filterNot { it.endpointId == "b" })
        runCurrent(); owner.onAction(MainAction.Play); assertEquals(0, engine.starts)
        assertEquals(3, owner.state.value.group.devices.size)
    }
    @Test fun levelsMuteTestsAndDelayHaveIndependentExplicitLifetimes() = runTest {
        val engine = Playback()
        val owner = MainViewModel(outputRepository = Outputs(), playbackController = engine, collectionScope = backgroundScope)
        runCurrent(); owner.onAction(MainAction.SelectWorkspace(WorkspaceMode.GroupSound))
        owner.group(GroupSoundAction.AddDevice("a")); owner.group(GroupSoundAction.AddDevice("b"))
        val stops = engine.stops
        owner.group(GroupSoundAction.SetVolume("a", 31)); owner.group(GroupSoundAction.ToggleMute("a"))
        assertEquals(stops, engine.stops)
        assertEquals(31, engine.routes.first().volumePercent); assertTrue(engine.routes.first().muted)
        assertEquals(80, engine.routes.last().volumePercent); assertFalse(engine.routes.last().muted)
        owner.group(GroupSoundAction.ToggleMute("a")); assertEquals(31, owner.state.value.group.devices.first().volumePercent)
        owner.group(GroupSoundAction.TestDevice("b")); assertEquals(1, engine.tests); assertEquals("b", engine.routes.single().endpointId)
        owner.group(GroupSoundAction.TestDevice("b")); assertTrue(engine.stops > stops)
        owner.group(GroupSoundAction.TestDevice("b")); assertEquals(2, engine.tests)
        engine.playback.value = engine.playback.value.copy(phase = PlaybackPhase.Completed)
        runCurrent()
        owner.group(GroupSoundAction.TestDevice("b")); assertEquals(3, engine.tests)
        owner.group(GroupSoundAction.SetDelay("a", 60)); assertEquals(60, owner.state.value.group.devices.first().delayMillis)
        owner.group(GroupSoundAction.SetDelay("a", -1)); assertEquals(60, owner.state.value.group.devices.first().delayMillis)
        assertEquals(0, engine.starts)
    }
    @Test fun liveSourcesUseGroupRoutesAndReplacementNeverAutoStarts() = runTest {
        val engine = Playback()
        val owner = MainViewModel(outputRepository = Outputs(), playbackController = engine, collectionScope = backgroundScope)
        runCurrent(); owner.onAction(MainAction.SelectWorkspace(WorkspaceMode.GroupSound))
        owner.group(GroupSoundAction.AddDevice("a"))
        owner.onAction(MainAction.SelectAppCaptureMode); runCurrent()
        owner.onAction(MainAction.SelectCaptureApp(owner.state.value.captureApps.single()))
        assertEquals(0, engine.starts)
        owner.onAction(MainAction.StartAppCapture)
        assertEquals(1, engine.starts); assertEquals(PlaybackMix.FullStereo, engine.routes.single().mix)
        owner.onAction(MainAction.SetLiveInputMode(true))
        owner.onAction(MainAction.SelectVirtualInput("virtual"))
        assertEquals(1, engine.starts)
        owner.onAction(MainAction.StartVirtualInput)
        assertEquals(2, engine.starts); assertEquals("a", engine.routes.single().endpointId)
        owner.onAction(MainAction.SetLiveInputMode(false))
        assertEquals(2, engine.starts)
        assertTrue(engine.stops >= 3)
    }
    @Test fun routeValidationIncludesCountAndTransientSettingsNeverBecomeRoomProfiles() {
        val outputs = Outputs().outputs.value
        assertNotNull(GroupSoundState().routeError(outputs))
        val a = GroupDevice(outputs.devices.first())
        assertNull(GroupSoundState(listOf(a)).routeError(outputs))
        assertNotNull(GroupSoundState(listOf(a,a)).routeError(outputs))
        assertNotNull(GroupSoundState(listOf(a)).routeError(outputs.copy(status = AudioOutputStatus.Error)))
        val state = MainUiState(group = GroupSoundState(listOf(a)))
        assertTrue(state.setups.all { it.speakers.isEmpty() })
        assertTrue(MainUiState().group.devices.isEmpty())
    }
    @Test fun circleSelectionFilteringAndViewSwitchKeepRoutesAndPlaybackUntouched() = runTest {
        val engine = Playback(); val outputs = Outputs()
        outputs.outputs.value = outputs.outputs.value.copy(devices = List(13) { AudioOutputDevice("$it", "Device $it") })
        val owner = MainViewModel(outputRepository = outputs, playbackController = engine, collectionScope = backgroundScope)
        runCurrent(); owner.onAction(MainAction.SelectWorkspace(WorkspaceMode.GroupSound))
        repeat(13) { owner.group(GroupSoundAction.AddDevice("$it")) }
        assertEquals(12, owner.state.value.group.devices.size)
        assertNotNull(owner.state.value.group.routeError(outputs.outputs.value))
        val stops = engine.stops; val routes = engine.routes
        owner.group(GroupSoundAction.SelectDevice("8"))
        owner.group(GroupSoundAction.SearchDevices("Device 1"))
        owner.group(GroupSoundAction.SetView(GroupSoundView.ListeningLounge))
        assertEquals("8", owner.state.value.group.selectedDevice?.output?.endpointId)
        assertEquals(12, owner.state.value.group.devices.size)
        assertEquals(stops, engine.stops); assertEquals(routes, engine.routes)
        owner.onAction(MainAction.Play); assertEquals(0, engine.starts)
        owner.group(GroupSoundAction.TestDevice("8")); assertEquals("8", engine.routes.single().endpointId)
        owner.group(GroupSoundAction.RemoveDevice("8"))
        assertEquals("0", owner.state.value.group.selectedDevice?.output?.endpointId)
    }

}
