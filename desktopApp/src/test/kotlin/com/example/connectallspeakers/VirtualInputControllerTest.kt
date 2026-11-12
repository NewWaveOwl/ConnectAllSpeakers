package com.example.connectallspeakers

import com.example.connectallspeakers.audio.dsp.*
import com.example.connectallspeakers.audio.input.LiveCaptureSource
import com.example.connectallspeakers.data.audio.windows.WindowsAudioPlaybackController
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Worker tests inject both factories; they never initialize COM or real audio streams. */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualInputControllerTest {
    private val routes = listOf(PlaybackRoute("a", SpeakerChannel.Left, 80), PlaybackRoute("b", SpeakerChannel.Right, 60))
    private val decoder = AudioFileDecoder { _, _ -> DecodedAudio("retained.wav", 48_000, 2, FloatArray(9600) { .1f }) }
    private fun outputs() = FakePlaybackOutputs().apply {
        outputs.value = outputs.value.copy(devices = outputs.value.devices + AudioOutputDevice("input", "Same name", role = AudioOutputRole.AllConnectInput))
    }
    private fun TestScope.controller(outputs: FakePlaybackOutputs, rig: LiveRig, captureFactory: (String) -> LiveCaptureSource = {
        assertEquals("input", it); rig.capture
    }) = WindowsAudioPlaybackController(outputs, decoder, { rig.devices }, StandardTestDispatcher(testScheduler), {},
        { testScheduler.currentTime * 1_000_000 }, captureFactory)

    @Test fun constructionLoadAndRefreshStaySilentThenExplicitStartOwnsAndReleasesBothLifetimes() = runTest {
        val outputs = outputs(); val rig = LiveRig()
        var captures = 0
        val owner = controller(outputs, rig) { captures++; rig.capture }
        try {
            runCurrent(); outputs.refresh(); owner.loadFile("retained.wav"); runCurrent()
            assertEquals(0, captures); assertTrue(rig.endpoints.isEmpty())
            owner.startVirtualInput("input", routes); runCurrent()
            assertEquals(1, captures); assertEquals(setOf("a", "b"), rig.endpoints.keys)
            assertEquals(LIVE_SAMPLE_RATE, owner.playback.value.source?.sampleRate)
            assertEquals(LiveInputPhase.Idle, owner.playback.value.liveInput?.phase)
            owner.stop(); runCurrent()
            assertEquals(1, rig.closedCapture); assertEquals(1, rig.closedDevices)
            assertNull(owner.playback.value.liveInput)
            assertEquals("retained.wav", owner.playback.value.source?.name)
        } finally { owner.close(); owner.awaitClosed() }
        assertEquals(1, rig.closedCapture)
    }

    @Test fun renamedOwnedInputsAndUnknownDevicesAreNeverPhysicalTargetsIncludingFileAndTest() = runTest {
        val outputs = outputs()
        outputs.outputs.value = outputs.outputs.value.copy(devices = outputs.outputs.value.devices + AudioOutputDevice("unknown", "Speaker", role = AudioOutputRole.Unknown))
        for (id in listOf("input", "unknown")) {
            val rig = LiveRig(); var captureCalls = 0
            val owner = controller(outputs, rig) { captureCalls++; rig.capture }
            val invalid = listOf(routes.first().copy(endpointId = id))
            try {
                owner.loadFile("retained.wav"); runCurrent()
                owner.play(invalid); runCurrent(); assertEquals(PlaybackPhase.Error, owner.playback.value.phase)
                owner.testSpeaker(invalid.single()); runCurrent(); assertEquals(PlaybackPhase.Error, owner.playback.value.phase)
                owner.startVirtualInput("input", invalid); runCurrent(); assertEquals(PlaybackPhase.Error, owner.playback.value.phase)
                assertTrue(rig.endpoints.isEmpty()); assertEquals(0, captureCalls)
            } finally { owner.close(); owner.awaitClosed() }
        }
    }

    @Test fun physicalOrVirtualRemovalStopsAllAndReconnectionDoesNotAutostart() = runTest {
        for (missing in listOf("input", "a")) {
            val outputs = outputs(); val original = outputs.outputs.value; val rig = LiveRig()
            val owner = controller(outputs, rig)
            try {
                owner.startVirtualInput("input", routes); runCurrent()
                outputs.outputs.value = original.copy(devices = original.devices.filterNot { it.endpointId == missing })
                advanceTimeBy(6); runCurrent()
                assertEquals(PlaybackPhase.Error, owner.playback.value.phase)
                assertEquals(1, rig.closedCapture); assertTrue(rig.endpoints.values.all { it.closed == 1 })
                outputs.outputs.value = original
                advanceTimeBy(500); runCurrent()
                assertEquals(PlaybackPhase.Error, owner.playback.value.phase)
                assertEquals(1, rig.endpoints.getValue("b").closed)
            } finally { owner.close(); owner.awaitClosed() }
        }
    }

    @Test fun badSourceCancellationAndPartialOpenNeverLeaveNativeResources() = runTest {
        val outputs = outputs(); val rig = LiveRig().apply { failOpenId = "b" }
        val owner = controller(outputs, rig)
        try {
            owner.startVirtualInput("a", routes); runCurrent()
            assertEquals(PlaybackPhase.Error, owner.playback.value.phase)
            assertTrue(rig.endpoints.isEmpty()); assertEquals(0, rig.closedCapture)
            owner.startVirtualInput("input", routes); owner.stop(); runCurrent()
            assertTrue(rig.endpoints.isEmpty())
            owner.startVirtualInput("input", routes); runCurrent()
            assertEquals(PlaybackPhase.Error, owner.playback.value.phase)
            assertEquals(1, rig.closedCapture); assertEquals(1, rig.closedDevices)
            assertEquals(1, rig.endpoints.getValue("a").closed)
        } finally { owner.close(); owner.awaitClosed() }
    }

    @Test fun failedCaptureAndPauseOrNewFileDiscardLiveSessionRatherThanRecordOrResume() = runTest {
        for (action in listOf("capture error", "pause", "load")) {
            val outputs = outputs(); val rig = LiveRig()
            val owner = controller(outputs, rig)
            try {
                owner.startVirtualInput("input", routes); runCurrent()
                when (action) {
                    "capture error" -> { rig.failPacket = true; advanceTimeBy(6) }
                    "pause" -> owner.pause()
                    else -> owner.loadFile("retained.wav")
                }
                runCurrent()
                assertEquals(1, rig.closedCapture); assertEquals(1, rig.closedDevices)
                assertNull(owner.playback.value.liveInput)
                assertNotEquals(PlaybackPhase.Paused, owner.playback.value.phase)
            } finally { owner.close(); owner.awaitClosed() }
        }
    }
}
