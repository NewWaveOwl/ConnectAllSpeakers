package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.AudioFileDecoder
import com.example.connectallspeakers.audio.dsp.DecodedAudio
import com.example.connectallspeakers.data.audio.windows.WindowsAudioPlaybackController
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Worker/controller EQ lifecycle with controlled source rates and fake endpoints only. */
@OptIn(ExperimentalCoroutinesApi::class)
class EqPlaybackControllerTest {
    private val routes = listOf(PlaybackRoute("a", SpeakerChannel.Left, 30), PlaybackRoute("b", SpeakerChannel.Right, 60))
    private val decoder = AudioFileDecoder { path, _ ->
        DecodedAudio(path, if (path == "8k") 8000 else 48000, 2, FloatArray(8000) { .25f })
    }

    @Test fun liveAndPausedInvalidUpdatesRetainSafeRoutesAndDoNotCloseOtherOutputs() = runTest {
        val devices = FakePlaybackDevices()
        val controller = WindowsAudioPlaybackController(FakePlaybackOutputs(), decoder, { devices },
            StandardTestDispatcher(testScheduler), {}, { testScheduler.currentTime * 1_000_000 })
        try {
            controller.loadFile("8k"); runCurrent(); controller.play(routes); runCurrent()
            val invalid = routes.map { if (it.endpointId == "b") it.copy(eq = SpeakerEq(true, high = EqBand(16000.0, 6.0))) else it }
            controller.updateRoutes(invalid); runCurrent()
            assertTrue(controller.playback.value.routeUpdateError!!.contains("3600 Hz"))
            assertEquals(PlaybackPhase.Playing, controller.playback.value.phase)
            assertEquals(0, devices.closed)
            controller.pause(); runCurrent()
            controller.updateRoutes(invalid); runCurrent()
            assertEquals(PlaybackPhase.Paused, controller.playback.value.phase)
            val valid = routes.map { if (it.endpointId == "b") it.copy(eq = SpeakerEq(true, mid = EqBand(1000.0, -6.0))) else it }
            controller.updateRoutes(valid); runCurrent()
            assertNull(controller.playback.value.routeUpdateError)
            controller.play(valid); runCurrent()
            assertEquals(PlaybackPhase.Playing, controller.playback.value.phase)
            assertEquals(0, devices.closed)
            devices.endpoints.values.forEach { it.queued -= 40 }
            advanceTimeBy(6); runCurrent()
            assertTrue(devices.endpoints.values.all { it.started })
            assertEquals(2, devices.events.count { it.startsWith("open:") })
        } finally { controller.close(); controller.awaitClosed() }
        assertEquals(1, devices.closed)
    }

    @Test fun sourceRateChangeValidatesBeforeOpeningAndBypassAllowsSafeRestart() = runTest {
        val devices = mutableListOf<FakePlaybackDevices>()
        val controller = WindowsAudioPlaybackController(FakePlaybackOutputs(), decoder,
            { FakePlaybackDevices().also { devices += it } }, StandardTestDispatcher(testScheduler), {})
        val high = routes.map { it.copy(eq = SpeakerEq(true, high = EqBand(8000.0, 6.0))) }
        try {
            controller.loadFile("48k"); runCurrent(); controller.play(high); runCurrent()
            assertEquals(PlaybackPhase.Playing, controller.playback.value.phase)
            assertEquals(1, devices.size)
            controller.loadFile("8k"); runCurrent()
            assertEquals(1, devices.single().closed)
            controller.play(high); runCurrent()
            assertEquals(PlaybackPhase.Error, controller.playback.value.phase)
            assertTrue(controller.playback.value.message!!.contains("3600 Hz"))
            assertEquals(1, devices.size) // No native factory or stream opens for unsupported source-rate EQ.
            controller.play(high.map { it.copy(eq = it.eq.copy(enabled = false)) }); runCurrent()
            assertEquals(PlaybackPhase.Playing, controller.playback.value.phase)
            assertEquals(2, devices.size)
            assertTrue(controller.playback.value.outputTimings.all { it.eq?.headroomDb == 0.0 })
        } finally { controller.close(); controller.awaitClosed() }
    }
}
