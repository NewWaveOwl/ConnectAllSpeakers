package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.LocalAudioFileDecoder
import com.example.connectallspeakers.data.audio.windows.WindowsAudioPlaybackController
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.PlaybackPhase
import com.example.connectallspeakers.model.audio.PlaybackRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** End-to-end MP3 load/transport through the real codec and fake endpoints only. */
@OptIn(ExperimentalCoroutinesApi::class)
class Mp3PlaybackControllerTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun mp3LoadsSilentlyThenUsesExistingRoutesPauseStopAndFailureCleanup() = runTest {
        val encoded = javaClass.getResourceAsStream("/audio/stereo-vbr-48000.mp3")!!.use { it.readBytes() }
        val source = folder.newFile("check.mp3").apply {
            writeBytes(encoded)
        }
        val bad = folder.newFile("bad.mp3").apply { writeText("not an MP3") }
        val devices = mutableListOf<FakePlaybackDevices>()
        val controller = WindowsAudioPlaybackController(FakePlaybackOutputs(), LocalAudioFileDecoder(),
            { FakePlaybackDevices().also { devices += it } }, StandardTestDispatcher(testScheduler), {})
        val routes = listOf(PlaybackRoute("a", SpeakerChannel.Left, 30), PlaybackRoute("b", SpeakerChannel.Right, 60))
        try {
            controller.loadFile(source.path); runCurrent()
            assertEquals(PlaybackPhase.Ready, controller.playback.value.phase)
            assertEquals("check.mp3", controller.playback.value.source?.name)
            assertTrue(devices.isEmpty())
            controller.play(routes); runCurrent()
            assertEquals(PlaybackPhase.Playing, controller.playback.value.phase)
            assertEquals(setOf("a", "b"), controller.playback.value.activeEndpointIds)
            controller.pause(); runCurrent()
            assertEquals(PlaybackPhase.Paused, controller.playback.value.phase)
            controller.play(routes); runCurrent()
            assertEquals(1, devices.size)
            controller.stop(); runCurrent()
            assertEquals(PlaybackPhase.Stopped, controller.playback.value.phase)
            assertEquals(1, devices.single().closed)
            controller.play(routes); runCurrent()
            controller.loadFile(bad.path); runCurrent()
            assertEquals(PlaybackPhase.Error, controller.playback.value.phase)
            assertEquals("check.mp3", controller.playback.value.source?.name)
            assertEquals(2, devices.size)
            assertTrue(devices.all { it.closed == 1 })
        } finally { controller.close(); controller.awaitClosed() }
    }
}
