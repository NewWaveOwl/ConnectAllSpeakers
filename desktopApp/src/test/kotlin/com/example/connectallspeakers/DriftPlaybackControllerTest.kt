package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.*
import com.example.connectallspeakers.data.audio.windows.WindowsAudioPlaybackController
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

/** No WASAPI construction: verifies opt-in identity, safe failures and native lifetime orchestration. */
@OptIn(ExperimentalCoroutinesApi::class)
class DriftPlaybackControllerTest {
    private val routes = listOf(PlaybackRoute("a", SpeakerChannel.Left, 80), PlaybackRoute("b", SpeakerChannel.Right, 60))
    private val decoder = AudioFileDecoder { _, _ -> DecodedAudio("file.wav", 8_000, 1, FloatArray(100_000) { .1f }) }

    @Test fun invalidReferenceCannotOpenDevicesAndChangingReferenceRebuildsPausedSession() = runTest {
        val devices = mutableListOf<FakePlaybackDevices>()
        val controller = WindowsAudioPlaybackController(FakePlaybackOutputs(), decoder,
            { FakePlaybackDevices().also { devices += it } }, StandardTestDispatcher(testScheduler), {}, { testScheduler.currentTime * 1_000_000 })
        try {
            controller.loadFile("file"); runCurrent()
            controller.play(routes, "missing"); runCurrent()
            controller.play(routes.take(1), "a"); runCurrent()
            controller.play(routes.map { it.copy(delayMillis = 1) }, "b"); runCurrent()
            assertTrue(devices.isEmpty())
            controller.play(routes, "a"); runCurrent()
            assertTrue(controller.playback.value.outputTimings.all { it.drift != null })
            controller.pause(); runCurrent(); controller.play(routes, "a"); runCurrent()
            assertEquals(1, devices.size)
            controller.pause(); runCurrent(); controller.play(routes, "b"); runCurrent()
            assertEquals(2, devices.size)
            assertEquals(1, devices.first().closed)
            assertTrue(controller.playback.value.outputTimings.first().drift!!.isReference.not())
            controller.stop(); runCurrent(); controller.play(routes); runCurrent()
            assertTrue(controller.playback.value.outputTimings.all { it.drift == null })
        } finally { controller.close(); controller.awaitClosed() }
    }

    @Test fun unavailableClocksStopWholeSessionAfterGraceAndPreserveExplicitFailureFeedback() = runTest {
        val devices = FakePlaybackDevices()
        val controller = WindowsAudioPlaybackController(FakePlaybackOutputs(), decoder, { devices },
            StandardTestDispatcher(testScheduler), {}, { testScheduler.currentTime * 1_000_000 })
        try {
            controller.loadFile("file"); runCurrent(); controller.play(routes, "a"); runCurrent()
            assertEquals(DriftPhase.Degraded, controller.playback.value.outputTimings.last().drift!!.phase)
            advanceTimeBy(5_201); runCurrent()
            val failure = controller.playback.value
            assertEquals(PlaybackPhase.Error, failure.phase)
            assertTrue(failure.message!!.contains("Reliable drift timing was lost"))
            assertTrue(failure.outputTimings.all { !it.isLive && it.drift!!.phase == DriftPhase.Failed })
            assertTrue(devices.endpoints.values.all { it.closed == 1 && !it.started })
            assertEquals(1, devices.closed)
        } finally { controller.close(); controller.awaitClosed() }
    }

    @Test fun disconnectWhilePausedClosesBothAndReconnectNeverAutoStarts() = runTest {
        val outputs = FakePlaybackOutputs()
        val devices = FakePlaybackDevices()
        val controller = WindowsAudioPlaybackController(outputs, decoder, { devices },
            StandardTestDispatcher(testScheduler), {}, { testScheduler.currentTime * 1_000_000 })
        try {
            controller.loadFile("file"); runCurrent(); controller.play(routes, "a"); runCurrent()
            controller.pause(); runCurrent()
            val original = outputs.outputs.value
            outputs.outputs.value = original.copy(devices = original.devices.take(1))
            advanceTimeBy(51); runCurrent()
            assertEquals(PlaybackPhase.Error, controller.playback.value.phase)
            assertTrue(controller.playback.value.outputTimings.all { !it.isLive })
            outputs.outputs.value = original; advanceTimeBy(200); runCurrent()
            assertTrue(devices.endpoints.values.all { it.closed == 1 && !it.started })
            assertEquals(1, devices.closed)
        } finally { controller.close(); controller.awaitClosed() }
    }
}
