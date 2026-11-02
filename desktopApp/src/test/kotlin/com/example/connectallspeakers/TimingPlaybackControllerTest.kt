package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.AudioFileDecoder
import com.example.connectallspeakers.audio.dsp.DecodedAudio
import com.example.connectallspeakers.data.audio.windows.WindowsAudioPlaybackController
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Serial-worker lifecycle coverage using only fake endpoints and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimingPlaybackControllerTest {
    private val decoder = AudioFileDecoder { _, _ -> DecodedAudio("retained.wav", 8_000, 1, FloatArray(160) { .25f }) }
    private val routes = listOf(PlaybackRoute("a", SpeakerChannel.Left, 80), PlaybackRoute("b", SpeakerChannel.Right, 60, 10))
    private fun TestScope.controller(devices: () -> FakePlaybackDevices) = WindowsAudioPlaybackController(
        FakePlaybackOutputs(), decoder, devices, StandardTestDispatcher(testScheduler), {}, { testScheduler.currentTime * 1_000_000 })

    @Test fun measurementsAreThrottledRefreshWhilePausedAndBecomeUnknownOnStop() = runTest {
        val devices = FakePlaybackDevices()
        val controller = controller { devices }
        try {
            controller.loadFile("file"); runCurrent(); controller.play(routes); runCurrent()
            val first = controller.playback.value
            assertEquals(listOf(0, 80), first.outputTimings.map { it.delayFrames })
            assertTrue(first.outputTimings.all { it.isLive && it.clock == null })
            advanceTimeBy(199); runCurrent()
            assertEquals(first.timingSequence, controller.playback.value.timingSequence)
            advanceTimeBy(2); runCurrent()
            assertTrue(controller.playback.value.timingSequence > first.timingSequence)
            controller.pause(); runCurrent()
            val paused = controller.playback.value
            assertEquals(PlaybackPhase.Paused, paused.phase)
            assertTrue(paused.outputTimings.all { it.clockMessage!!.contains("Paused") })
            advanceTimeBy(201); runCurrent()
            assertTrue(controller.playback.value.timingSequence > paused.timingSequence)
            controller.stop(); runCurrent()
            assertTrue(controller.playback.value.outputTimings.all { !it.isLive && it.queuedFrames == null && it.clock == null })
            assertEquals(1, devices.closed)
        } finally { controller.close(); controller.awaitClosed() }
    }

    @Test fun slowMeasurementCannotPublishOldQueueDataAsLive() = runTest {
        val devices = FakePlaybackDevices()
        var observations = 0
        val controller = WindowsAudioPlaybackController(FakePlaybackOutputs(), decoder, { devices },
            StandardTestDispatcher(testScheduler), {}, { if (++observations <= 2) 0L else 2_000_000_000L })
        try {
            controller.loadFile("file"); runCurrent(); controller.play(routes); runCurrent()
            assertTrue(controller.playback.value.outputTimings.isNotEmpty())
            assertTrue(controller.playback.value.outputTimings.all { !it.isLive && it.queuedFrames == null })
            assertTrue(controller.playback.value.outputTimings.all { it.clockMessage!!.contains("took too long") })
            assertEquals(PlaybackPhase.Playing, controller.playback.value.phase)
        } finally { controller.close(); controller.awaitClosed() }
    }

    @Test fun delayChangesStopPausedSessionAndExplicitRestartUsesFreshBuffers() = runTest {
        val devices = mutableListOf<FakePlaybackDevices>()
        val controller = controller { FakePlaybackDevices().also { devices += it } }
        try {
            controller.loadFile("file"); runCurrent(); controller.play(routes); runCurrent()
            controller.pause(); runCurrent()
            val changed = routes.map { if (it.endpointId == "b") it.copy(delayMillis = 20) else it }
            controller.updateRoutes(changed); runCurrent()
            assertEquals(PlaybackPhase.Stopped, controller.playback.value.phase)
            assertEquals(1, devices.single().closed)
            advanceTimeBy(500); runCurrent()
            assertEquals(1, devices.size)
            controller.play(changed); runCurrent()
            assertEquals(2, devices.size)
            assertEquals(160, controller.playback.value.outputTimings.last().delayFrames)
            assertTrue(devices.last().endpoints.getValue("b").submitted.all { it == 0.toByte() })
            controller.pause(); runCurrent(); controller.play(changed); runCurrent()
            assertEquals(2, devices.size) // Unchanged delay resumes; it does not add another delay line.
        } finally { controller.close(); controller.awaitClosed() }
    }

    @Test fun delayChangeDuringPreparationCancelsBeforeAnyEndpointStarts() = runTest {
        val devices = FakePlaybackDevices()
        val controller = controller { devices }
        try {
            controller.loadFile("file"); runCurrent()
            devices.beforeOpen = { controller.updateRoutes(routes.map { it.copy(delayMillis = 0) }) }
            controller.play(routes); runCurrent()
            assertFalse(devices.events.any { it.startsWith("start:") })
            assertEquals(PlaybackPhase.Stopped, controller.playback.value.phase)
            assertEquals(1, devices.closed)
        } finally { controller.close(); controller.awaitClosed() }
    }

    @Test fun calibrationRequiresExactZeroReferenceAndKeepsTheLoadedFileAfterStopping() = runTest {
        val devices = mutableListOf<FakePlaybackDevices>()
        val controller = controller { FakePlaybackDevices().also { devices += it } }
        try {
            controller.loadFile("file"); runCurrent()
            controller.calibrate(routes, "missing"); runCurrent()
            assertTrue(devices.isEmpty())
            assertEquals(PlaybackPhase.Error, controller.playback.value.phase)
            controller.calibrate(routes, "b"); runCurrent()
            assertTrue(devices.isEmpty())
            controller.calibrate(routes, "a"); runCurrent()
            assertTrue(controller.playback.value.isCalibration)
            assertFalse(controller.playback.value.isTestTone)
            assertEquals(15_000L, controller.playback.value.source?.durationMillis)
            assertEquals(setOf("a", "b"), controller.playback.value.activeEndpointIds)
            controller.stop(); runCurrent()
            assertFalse(controller.playback.value.isCalibration)
            assertEquals("retained.wav", controller.playback.value.source?.name)
            controller.play(routes); runCurrent()
            assertEquals("retained.wav", controller.playback.value.source?.name)
        } finally { controller.close(); controller.awaitClosed() }
    }

    @Test fun underrunRetainsHistoricalCounterAndInvalidatesLiveTimingsDuringWholeSessionCleanup() = runTest {
        val devices = FakePlaybackDevices()
        val controller = controller { devices }
        try {
            controller.loadFile("file"); runCurrent(); controller.play(routes); runCurrent()
            devices.endpoints.getValue("a").queued = 0
            advanceTimeBy(6); runCurrent()
            val failed = controller.playback.value
            assertEquals(PlaybackPhase.Error, failed.phase)
            assertEquals(1, failed.outputTimings.first().underruns)
            assertTrue(failed.outputTimings.none { it.isLive })
            assertTrue(failed.outputTimings.all { it.sourcePositionFrames == null })
            assertTrue(devices.endpoints.values.all { it.closed == 1 })
        } finally { controller.close(); controller.awaitClosed() }
    }
}
