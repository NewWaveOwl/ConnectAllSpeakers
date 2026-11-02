package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.AudioFileDecoder
import com.example.connectallspeakers.audio.dsp.DecodedAudio
import com.example.connectallspeakers.audio.dsp.WavFileException
import com.example.connectallspeakers.data.audio.AudioOutputStatus
import com.example.connectallspeakers.data.audio.windows.WindowsAudioPlaybackController
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.PlaybackPhase
import com.example.connectallspeakers.model.audio.PlaybackRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class WindowsAudioPlaybackControllerTest {
    private val routes = listOf(PlaybackRoute("a", SpeakerChannel.Left, 80), PlaybackRoute("b", SpeakerChannel.Right, 60))
    private val decoder = AudioFileDecoder { path, _ -> DecodedAudio(path, 8_000, 2, FloatArray(160) { .25f }) }

    @Test fun constructionAndFileLoadNeverOpenDevicesAndExplicitPlayOpensBoth() = runTest {
        val devices = FakePlaybackDevices()
        var opened = 0
        val controller = WindowsAudioPlaybackController(FakePlaybackOutputs(), decoder,
            { opened++; devices }, StandardTestDispatcher(testScheduler), {})
        try {
            runCurrent(); assertEquals(0, opened)
            controller.loadFile("known.wav"); runCurrent()
            assertEquals(PlaybackPhase.Ready, controller.playback.value.phase)
            assertEquals(0, opened)
            controller.play(routes); runCurrent()
            assertEquals(PlaybackPhase.Playing, controller.playback.value.phase)
            assertEquals(setOf("a", "b"), controller.playback.value.activeEndpointIds)
            assertEquals(1, opened)
        } finally { controller.close(); controller.awaitClosed() }
        assertEquals(1, devices.closed)
    }
    @Test fun pauseResumeAndStopKeepSourceAndDoNotReopenPausedStreams() = runTest {
        val devices = FakePlaybackDevices()
        val controller = WindowsAudioPlaybackController(FakePlaybackOutputs(), decoder,
            { devices }, StandardTestDispatcher(testScheduler), {})
        try {
            controller.loadFile("file.wav"); runCurrent(); controller.play(routes); runCurrent()
            controller.pause(); runCurrent()
            assertEquals(PlaybackPhase.Paused, controller.playback.value.phase)
            assertTrue(devices.endpoints.values.all { !it.started && it.queued == 80 })
            controller.play(routes); runCurrent()
            assertEquals(2, devices.events.count { it.startsWith("open:") })
            assertEquals(PlaybackPhase.Playing, controller.playback.value.phase)
            controller.stop(); runCurrent()
            assertEquals(PlaybackPhase.Stopped, controller.playback.value.phase)
            assertEquals("file.wav", controller.playback.value.source?.name)
            assertEquals(0L, controller.playback.value.positionMillis)
            assertEquals(1, devices.closed)
        } finally { controller.close(); controller.awaitClosed() }
    }
    @Test fun disconnectWhilePausedStopsAllAndReconnectDoesNotResume() = runTest {
        val outputs = FakePlaybackOutputs()
        val devices = FakePlaybackDevices()
        val controller = WindowsAudioPlaybackController(outputs, decoder, { devices }, StandardTestDispatcher(testScheduler), {})
        try {
            controller.loadFile("file.wav"); runCurrent(); controller.play(routes); runCurrent()
            controller.pause(); runCurrent()
            val original = outputs.outputs.value
            outputs.outputs.value = original.copy(devices = original.devices.take(1))
            advanceTimeBy(51); runCurrent()
            assertEquals(PlaybackPhase.Error, controller.playback.value.phase)
            assertEquals(1, devices.closed)
            outputs.outputs.value = original
            advanceTimeBy(100); runCurrent()
            assertEquals(PlaybackPhase.Error, controller.playback.value.phase)
            assertTrue(devices.endpoints.values.all { !it.started })
        } finally { controller.close(); controller.awaitClosed() }
    }
    @Test fun refreshFailureDoesNotMeanDisconnectButBlocksNewStart() = runTest {
        val outputs = FakePlaybackOutputs()
        val devices = FakePlaybackDevices()
        var opens = 0
        val controller = WindowsAudioPlaybackController(outputs, decoder, { opens++; devices }, StandardTestDispatcher(testScheduler), {})
        try {
            controller.loadFile("file.wav"); runCurrent(); controller.play(routes); runCurrent()
            outputs.outputs.value = outputs.outputs.value.copy(status = AudioOutputStatus.Error)
            advanceTimeBy(6); runCurrent()
            assertEquals(PlaybackPhase.Playing, controller.playback.value.phase)
            controller.stop(); runCurrent(); controller.play(routes); runCurrent()
            assertEquals(PlaybackPhase.Error, controller.playback.value.phase)
            assertEquals(1, opens)
        } finally { controller.close(); controller.awaitClosed() }
    }
    @Test fun stopSupersedesQueuedLoadAndCancellationDuringPreparationPreventsStart() = runTest {
        val devices = FakePlaybackDevices()
        lateinit var controller: WindowsAudioPlaybackController
        controller = WindowsAudioPlaybackController(FakePlaybackOutputs(), decoder, { devices }, StandardTestDispatcher(testScheduler), {})
        try {
            controller.loadFile("cancelled.wav"); controller.stop(); runCurrent()
            assertNull(controller.playback.value.source)
            controller.loadFile("current.wav"); runCurrent()
            devices.beforeOpen = { controller.stop() }
            controller.play(routes); runCurrent()
            assertFalse(devices.events.any { it.startsWith("start:") })
            assertEquals(PlaybackPhase.Stopped, controller.playback.value.phase)
            assertEquals(1, devices.closed)
        } finally { controller.close(); controller.awaitClosed() }
    }
    @Test fun eofDrainsAndTestDoesNotReplaceLoadedFile() = runTest {
        val devices = mutableListOf<FakePlaybackDevices>()
        val controller = WindowsAudioPlaybackController(FakePlaybackOutputs(), decoder,
            { FakePlaybackDevices().also { devices += it } }, StandardTestDispatcher(testScheduler), {})
        try {
            controller.loadFile("file.wav"); runCurrent(); controller.play(routes); runCurrent()
            devices.last().endpoints.values.forEach { it.queued = 0 }
            advanceTimeBy(6); runCurrent()
            assertEquals(PlaybackPhase.Completed, controller.playback.value.phase)
            assertEquals(10L, controller.playback.value.positionMillis)
            controller.testSpeaker(routes.first()); runCurrent()
            assertTrue(controller.playback.value.isTestTone)
            assertEquals(setOf("a"), controller.playback.value.activeEndpointIds)
            controller.stop(); runCurrent()
            assertEquals("file.wav", controller.playback.value.source?.name)
        } finally { controller.close(); controller.awaitClosed() }
    }
    @Test fun invalidRoutesAndDecodeFailureNeverOpenNativeFactory() = runTest {
        var opened = 0
        val controller = WindowsAudioPlaybackController(FakePlaybackOutputs(), AudioFileDecoder { _, _ -> throw WavFileException("Invalid WAV.") },
            { opened++; FakePlaybackDevices() }, StandardTestDispatcher(testScheduler), {})
        try {
            controller.loadFile("bad.wav"); runCurrent()
            assertEquals(PlaybackPhase.Error, controller.playback.value.phase)
            controller.play(listOf(routes.first(), routes.first())); runCurrent()
            assertEquals(PlaybackPhase.Error, controller.playback.value.phase)
            controller.testSpeaker(routes.first().copy(endpointId = "not-present")); runCurrent()
            assertEquals(0, opened)
        } finally { controller.close(); controller.awaitClosed() }
    }
    @Test fun closeDuringLoadAndLiveMembershipChangesCleanUpWithoutFallback() = runTest {
        var opened = 0
        lateinit var controller: WindowsAudioPlaybackController
        controller = WindowsAudioPlaybackController(FakePlaybackOutputs(), AudioFileDecoder { _, cancelled ->
            controller.close()
            if (cancelled()) throw kotlinx.coroutines.CancellationException()
            error("Cancellation was not visible")
        }, { opened++; FakePlaybackDevices() }, StandardTestDispatcher(testScheduler), {})
        controller.loadFile("file.wav"); runCurrent(); controller.awaitClosed()
        assertEquals(0, opened)
        val devices = FakePlaybackDevices()
        val active = WindowsAudioPlaybackController(FakePlaybackOutputs(), decoder, { devices }, StandardTestDispatcher(testScheduler), {})
        try {
            active.loadFile("file.wav"); runCurrent(); active.play(routes); runCurrent()
            active.updateRoutes(routes.take(1)); advanceTimeBy(6); runCurrent()
            assertEquals(PlaybackPhase.Error, active.playback.value.phase)
            assertEquals(1, devices.closed)
        } finally { active.close(); active.awaitClosed() }
    }
    @Test fun settingsChangedImmediatelyAfterPlayAreNotOverwrittenByPreparation() = runTest {
        val devices = FakePlaybackDevices()
        val controller = WindowsAudioPlaybackController(FakePlaybackOutputs(), decoder,
            { devices }, StandardTestDispatcher(testScheduler), {})
        try {
            controller.loadFile("file.wav"); runCurrent()
            controller.play(routes)
            controller.updateRoutes(routes.map { it.copy(volumePercent = 0) })
            runCurrent()
            assertEquals(PlaybackPhase.Playing, controller.playback.value.phase)
            assertTrue(devices.endpoints.values.all { it.submitted.all { byte -> byte == 0.toByte() } })
        } finally { controller.close(); controller.awaitClosed() }
    }
}
