package com.example.connectallspeakers

import com.example.connectallspeakers.ViewModels.main.*
import com.example.connectallspeakers.data.audio.*
import com.example.connectallspeakers.model.audio.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppCaptureStateTest {
    private val app = CaptureApp(42, 100, "player.exe")
    private class Playback : AudioPlaybackController {
        override val playback = MutableStateFlow(PlaybackSnapshot())
        override val supportsAppCapture = true
        var apps: List<CaptureApp> = emptyList()
        var starts = 0
        var stops = 0
        var selected: CaptureApp? = null
        override suspend fun captureApps() = apps
        override fun startAppCapture(app: CaptureApp, routes: List<PlaybackRoute>) { starts++; selected = app }
        override fun stop() { stops++ }
        override fun pause() = Unit
        override fun loadFile(path: String) = error("No file capture expected")
        override fun play(routes: List<PlaybackRoute>, driftReferenceEndpointId: String?) = Unit
        override fun updateRoutes(routes: List<PlaybackRoute>) = Unit
        override fun calibrate(routes: List<PlaybackRoute>, referenceEndpointId: String) = Unit
        override fun testSpeaker(route: PlaybackRoute) = Unit
    }
    private class Outputs : AudioOutputRepository {
        override val outputs = MutableStateFlow(AudioOutputSnapshot(AudioOutputStatus.Ready,
            listOf(AudioOutputDevice("speaker", "Physical speaker"))))
        override fun refresh() = Unit
    }

    @Test fun refreshAndSelectionStaySilentUntilExplicitStartWithoutVirtualDriver() = runTest {
        val playback = Playback().apply { apps = listOf(app) }
        val owner = MainViewModel(outputRepository = Outputs(), playbackController = playback, collectionScope = backgroundScope)
        runCurrent(); owner.onAction(MainAction.AddOutput("speaker"))
        owner.onAction(MainAction.SelectAppCaptureMode); runCurrent()
        assertEquals(listOf(app), owner.state.value.captureApps)
        owner.onAction(MainAction.SelectCaptureApp(app))
        assertEquals(0, playback.starts)
        owner.onAction(MainAction.StartAppCapture)
        assertEquals(1, playback.starts); assertEquals(app, playback.selected)
        owner.onAction(MainAction.SetLiveInputMode(false))
        assertFalse(owner.state.value.appCaptureMode); assertFalse(owner.state.value.liveInputMode)
        assertTrue(playback.stops >= 2)
    }

    @Test fun refreshedProcessIdentityMustBeReselectedAndDemoCannotStart() = runTest {
        val playback = Playback().apply { apps = listOf(app) }
        val owner = MainViewModel(outputRepository = Outputs(), playbackController = playback, collectionScope = backgroundScope)
        runCurrent(); owner.onAction(MainAction.AddOutput("speaker"))
        owner.onAction(MainAction.SelectAppCaptureMode); runCurrent()
        owner.onAction(MainAction.SelectCaptureApp(app))
        playback.apps = listOf(app.copy(startedAtMillis = 200))
        owner.onAction(MainAction.RefreshCaptureApps); runCurrent()
        assertNull(owner.state.value.selectedCaptureApp)
        owner.onAction(MainAction.StartAppCapture); assertEquals(0, playback.starts)
        owner.onAction(MainAction.SelectCaptureApp(playback.apps.single()))
        owner.onAction(MainAction.SetDemoMode(true))
        owner.onAction(MainAction.StartAppCapture); assertEquals(0, playback.starts)
    }

    @Test fun switchingToVirtualModeCannotStartTheSelectedProcess() = runTest {
        val playback = Playback().apply { apps = listOf(app) }
        val owner = MainViewModel(outputRepository = Outputs(), playbackController = playback, collectionScope = backgroundScope)
        runCurrent(); owner.onAction(MainAction.AddOutput("speaker"))
        owner.onAction(MainAction.SelectAppCaptureMode); runCurrent()
        owner.onAction(MainAction.SelectCaptureApp(app))
        owner.onAction(MainAction.SetLiveInputMode(true))
        owner.onAction(MainAction.StartAppCapture)
        assertEquals(0, playback.starts)
        assertFalse(owner.state.value.appCaptureMode)
    }
}
