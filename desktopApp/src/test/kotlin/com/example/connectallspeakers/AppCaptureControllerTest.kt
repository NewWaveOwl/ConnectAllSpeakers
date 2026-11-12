package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.AudioFileDecoder
import com.example.connectallspeakers.audio.input.LiveCaptureSource
import com.example.connectallspeakers.data.audio.windows.WindowsAudioPlaybackController
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Process sources use the same cleanup/routing contract without requiring an installed virtual device. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppCaptureControllerTest {
    private val app = CaptureApp(1234, 1000, "player.exe")
    private val routes = listOf(PlaybackRoute("a", SpeakerChannel.Left, 80))

    private fun TestScope.owner(rig: LiveRig, capture: (CaptureApp) -> LiveCaptureSource = { rig.capture }) =
        WindowsAudioPlaybackController(FakePlaybackOutputs(), AudioFileDecoder { _, _ -> error("No file expected") },
            { rig.devices }, StandardTestDispatcher(testScheduler), {}, { testScheduler.currentTime * 1_000_000 },
            { error("No virtual driver expected") }, capture)

    @Test fun explicitAppCaptureNeedsNoVirtualEndpointAndStopReleasesEverything() = runTest {
        val rig = LiveRig(); var calls = 0
        val controller = owner(rig) { selected -> assertEquals(app, selected); calls++; rig.capture }
        try {
            runCurrent(); assertEquals(0, calls)
            controller.startAppCapture(app, routes); runCurrent()
            assertEquals(1, calls)
            assertNotNull(controller.playback.value.liveInput)
            assertEquals("player.exe · app audio", controller.playback.value.source?.name)
            controller.stop(); runCurrent()
            assertEquals(1, rig.closedCapture); assertEquals(1, rig.closedDevices)
            assertNull(controller.playback.value.liveInput)
        } finally { controller.close(); controller.awaitClosed() }
    }

    @Test fun supersededStartAndInvalidTargetsNeverOpenCapture() = runTest {
        val rig = LiveRig(); var calls = 0
        val controller = owner(rig) { calls++; rig.capture }
        try {
            controller.startAppCapture(app, routes); controller.stop(); runCurrent()
            assertEquals(0, calls)
            controller.startAppCapture(app, listOf(routes.single().copy(endpointId = "missing"))); runCurrent()
            assertEquals(PlaybackPhase.Error, controller.playback.value.phase)
            assertEquals(0, calls)
        } finally { controller.close(); controller.awaitClosed() }
    }

    @Test fun exitedAppFailureStopsTargetsAndDoesNotRestart() = runTest {
        val rig = LiveRig(); val controller = owner(rig)
        try {
            controller.startAppCapture(app, routes); runCurrent()
            rig.failPacket = true; advanceTimeBy(6); runCurrent()
            assertEquals(PlaybackPhase.Error, controller.playback.value.phase)
            assertEquals(1, rig.closedCapture)
            assertTrue(rig.endpoints.values.all { it.closed == 1 })
            advanceTimeBy(1000); runCurrent()
            assertEquals(PlaybackPhase.Error, controller.playback.value.phase)
        } finally { controller.close(); controller.awaitClosed() }
    }
}
