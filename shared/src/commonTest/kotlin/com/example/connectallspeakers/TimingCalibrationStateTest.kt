package com.example.connectallspeakers

import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.ViewModels.main.MainViewModel
import com.example.connectallspeakers.data.audio.*
import com.example.connectallspeakers.model.RoomPosition
import com.example.connectallspeakers.model.RoomSpeaker
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Portable calibration rules and measurement freshness, with controlled repository state. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimingCalibrationStateTest {
    private class Outputs : AudioOutputRepository {
        override val outputs = MutableStateFlow(AudioOutputSnapshot(AudioOutputStatus.Ready,
            listOf(AudioOutputDevice("a", "Same"), AudioOutputDevice("b", "Same"))))
        override fun refresh() = Unit
    }
    private class Playback : AudioPlaybackController {
        override val playback = MutableStateFlow(PlaybackSnapshot(PlaybackPhase.Ready))
        val calibrations = mutableListOf<Pair<List<PlaybackRoute>, String>>()
        val plays = mutableListOf<List<PlaybackRoute>>()
        val tests = mutableListOf<PlaybackRoute>()
        var stops = 0
        override fun loadFile(path: String) = Unit
        override fun play(routes: List<PlaybackRoute>, driftReferenceEndpointId: String?) { plays += routes }
        override fun pause() = Unit
        override fun stop() { stops++ }
        override fun updateRoutes(routes: List<PlaybackRoute>) = Unit
        override fun testSpeaker(route: PlaybackRoute) { tests += route }
        override fun calibrate(routes: List<PlaybackRoute>, referenceEndpointId: String) { calibrations += routes to referenceEndpointId }
    }
    private fun TestScope.owner(outputs: Outputs = Outputs(), playback: Playback = Playback()) = MainViewModel(
        outputRepository = outputs, playbackController = playback, collectionScope = backgroundScope)

    @Test fun delayFramesUseSourceRateCeilingAndRejectInvalidRanges() {
        assertEquals(0, outputDelayFrames(0, 44_100))
        assertEquals(8, outputDelayFrames(1, 8_000))
        assertEquals(45, outputDelayFrames(1, 44_100))
        assertEquals(48, outputDelayFrames(1, 48_000))
        assertEquals(96_000, outputDelayFrames(500, 192_000))
        for (invalid in listOf(-1, 501, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { outputDelayFrames(invalid, 48_000) }
            assertFailsWith<IllegalArgumentException> { PlaybackRoute("a", SpeakerChannel.Left, 80, invalid) }
            assertFailsWith<IllegalArgumentException> { RoomSpeaker("a", "Output", SpeakerChannel.Left, 80, RoomPosition(0f, 0f), delayMillis = invalid) }
        }
        assertFailsWith<IllegalArgumentException> { outputDelayFrames(1, 7_999) }
        assertFailsWith<IllegalArgumentException> { outputDelayFrames(1, 192_001) }
    }

    @Test fun selectedDelayAndResetStayInTheirRoomAndSavedSnapshotsRemainIndependent() = runTest {
        val playback = Playback()
        val owner = owner(playback = playback)
        runCurrent()
        owner.onAction(MainAction.AddOutput("a"))
        val referenceId = owner.state.value.selectedSpeaker!!.id
        owner.onAction(MainAction.AddOutput("b"))
        owner.onAction(MainAction.SetDelay(123))
        owner.onAction(MainAction.SetVolume(37))
        owner.onAction(MainAction.AssignChannel(SpeakerChannel.Right))
        owner.onAction(MainAction.RenameSpeaker("My output"))
        owner.onAction(MainAction.MoveSpeaker(owner.state.value.selectedSpeaker!!.id, RoomPosition(.8f, .7f)))
        val edited = owner.state.value.selectedSpeaker!!
        assertEquals(0, owner.state.value.selectedSetup!!.speakers.first().delayMillis)
        owner.onAction(MainAction.SaveSetup)
        owner.onAction(MainAction.ResetPositions)
        assertEquals(123, owner.state.value.selectedSpeaker!!.delayMillis)
        assertEquals(edited.channel, owner.state.value.selectedSpeaker!!.channel)
        assertEquals(edited.volume, owner.state.value.selectedSpeaker!!.volume)
        owner.onAction(MainAction.SelectSetup("desk"))
        owner.onAction(MainAction.AddOutput("a")); owner.onAction(MainAction.AddOutput("b"))
        owner.onAction(MainAction.SetDelay(45))
        owner.onAction(MainAction.ResetDelays)
        assertTrue(owner.state.value.selectedSetup!!.speakers.all { it.delayMillis == 0 })
        owner.onAction(MainAction.SelectSetup("living"))
        assertEquals(123, owner.state.value.selectedSpeaker!!.delayMillis)
        assertEquals(referenceId, owner.state.value.selectedSetup!!.calibrationReference!!.id)
        owner.onAction(MainAction.ResetDelays)
        assertEquals(0, owner.state.value.selectedSpeaker!!.delayMillis)
        assertEquals(123, owner.state.value.savedSetups.getValue("living").speakers.last().delayMillis)
        assertTrue(playback.plays.isEmpty()) // Editing never starts playback.
    }

    @Test fun referenceStaysZeroAndChangingItStopsPlaybackWithoutChangingOtherSettings() = runTest {
        val playback = Playback()
        val owner = owner(playback = playback)
        runCurrent(); owner.onAction(MainAction.AddOutput("a"))
        owner.onAction(MainAction.SetDelay(20))
        assertEquals(0, owner.state.value.selectedSpeaker!!.delayMillis)
        owner.onAction(MainAction.AddOutput("b")); owner.onAction(MainAction.SetDelay(100))
        val before = owner.state.value.selectedSpeaker!!
        val stops = playback.stops
        owner.onAction(MainAction.SelectCalibrationReference(before.id))
        assertEquals(before.copy(delayMillis = 0), owner.state.value.selectedSpeaker)
        assertEquals(stops + 1, playback.stops)
        owner.onAction(MainAction.SelectSpeaker(owner.state.value.selectedSetup!!.speakers.first().id))
        owner.onAction(MainAction.SetDelay(72))
        val valid = owner.state.value.selectedSpeaker!!
        for (invalid in listOf(-1, 501)) owner.onAction(MainAction.SetDelay(invalid))
        assertEquals(valid, owner.state.value.selectedSpeaker)
        owner.onAction(MainAction.SelectCalibrationReference("missing"))
        assertEquals(before.id, owner.state.value.selectedSetup!!.calibrationReference!!.id)
    }

    @Test fun calibrationUsesExactEndpointIdsAndDelayIsPreservedAcrossMetadataChanges() = runTest {
        val outputs = Outputs()
        val playback = Playback()
        val owner = owner(outputs, playback)
        runCurrent(); owner.onAction(MainAction.AddOutput("a")); owner.onAction(MainAction.PlayCalibration)
        assertTrue(playback.calibrations.isEmpty())
        owner.onAction(MainAction.AddOutput("b")); owner.onAction(MainAction.SetDelay(31))
        owner.onAction(MainAction.PlayCalibration)
        assertEquals("a", playback.calibrations.single().second)
        assertEquals(listOf(0, 31), playback.calibrations.single().first.map { it.delayMillis })
        owner.onAction(MainAction.TestSpeaker)
        assertEquals(31, playback.tests.single().delayMillis)
        val original = outputs.outputs.value
        outputs.outputs.value = original.copy(devices = original.devices.take(1)); runCurrent()
        assertEquals(31, owner.state.value.selectedSpeaker!!.delayMillis)
        assertEquals(OutputAvailability.Unavailable, owner.state.value.selectedSpeaker!!.availability)
        owner.onAction(MainAction.PlayCalibration)
        assertEquals(1, playback.calibrations.size)
        outputs.outputs.value = original; runCurrent()
        assertEquals(31, owner.state.value.selectedSpeaker!!.delayMillis)
        owner.onAction(MainAction.Play)
        assertEquals(31, playback.plays.single().last().delayMillis)
        owner.onAction(MainAction.SetDemoMode(true)); owner.onAction(MainAction.PlayCalibration)
        assertEquals(1, playback.calibrations.size)
    }

    @Test fun staleMeasurementsBecomeUnknownAndPositionUpdatesCannotResurrectThem() = runTest {
        val playback = Playback()
        val owner = owner(playback = playback)
        val timing = OutputTiming("a", 48_000, 0, 0, 480, 480, 240, 240,
            OutputClockTiming(240, 48_000, 10_000_000, 5.0), null, 10.0)
        playback.playback.value = PlaybackSnapshot(PlaybackPhase.Playing, outputTimings = listOf(timing), timingSequence = 1)
        runCurrent()
        assertTrue(owner.state.value.playback.outputTimings.single().isLive)
        advanceTimeBy(1_001); runCurrent()
        val stale = owner.state.value.playback.outputTimings.single()
        assertFalse(stale.isLive)
        assertNull(stale.clock); assertNull(stale.queuedFrames); assertNull(stale.sourcePositionFrames)
        assertNull(stale.streamLatencyMillis)
        assertEquals(480L, stale.submittedFrames) // Historical counter remains labeled, not live.
        playback.playback.value = playback.playback.value.copy(positionMillis = 10); runCurrent()
        assertFalse(owner.state.value.playback.outputTimings.single().isLive)
        playback.playback.value = playback.playback.value.copy(timingSequence = 2); runCurrent()
        assertTrue(owner.state.value.playback.outputTimings.single().isLive)
        playback.playback.value = PlaybackSnapshot(PlaybackPhase.Stopped); runCurrent()
        advanceTimeBy(1_001); runCurrent()
        assertTrue(owner.state.value.playback.outputTimings.isEmpty())
    }
}
