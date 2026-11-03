package com.example.connectallspeakers

import com.example.connectallspeakers.ViewModels.main.*
import com.example.connectallspeakers.data.audio.*
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Platform-neutral opt-in/reference validation and stale-estimate presentation. */
@OptIn(ExperimentalCoroutinesApi::class)
class DriftStateTest {
    private class Outputs : AudioOutputRepository {
        override val outputs = MutableStateFlow(AudioOutputSnapshot(AudioOutputStatus.Ready,
            listOf(AudioOutputDevice("a", "Same"), AudioOutputDevice("b", "Same"))))
        override fun refresh() = Unit
    }
    private class Playback : AudioPlaybackController {
        override val playback = MutableStateFlow(PlaybackSnapshot(PlaybackPhase.Ready))
        val plays = mutableListOf<Pair<List<PlaybackRoute>, String?>>()
        val tests = mutableListOf<PlaybackRoute>()
        val calibrations = mutableListOf<Pair<List<PlaybackRoute>, String>>()
        val updates = mutableListOf<List<PlaybackRoute>>()
        var stops = 0
        override fun loadFile(path: String) = Unit
        override fun play(routes: List<PlaybackRoute>, driftReferenceEndpointId: String?) { plays += routes to driftReferenceEndpointId }
        override fun pause() = Unit
        override fun stop() { stops++ }
        override fun updateRoutes(routes: List<PlaybackRoute>) { updates += routes }
        override fun testSpeaker(route: PlaybackRoute) { tests += route }
        override fun calibrate(routes: List<PlaybackRoute>, referenceEndpointId: String) { calibrations += routes to referenceEndpointId }
    }
    private fun TestScope.owner(playback: Playback) = MainViewModel(outputRepository = Outputs(),
        playbackController = playback, collectionScope = backgroundScope)

    @Test fun optInUsesExactReferencePreservesOtherSettingsAndIsIndependentPerRoomAndSnapshot() = runTest {
        val playback = Playback()
        val owner = owner(playback)
        runCurrent(); owner.onAction(MainAction.AddOutput("a")); owner.onAction(MainAction.AddOutput("b"))
        owner.onAction(MainAction.AssignChannel(SpeakerChannel.Right)); owner.onAction(MainAction.SetVolume(37))
        owner.onAction(MainAction.SetDelay(47))
        owner.onAction(MainAction.Play)
        assertNull(playback.plays.single().second)
        val original = owner.state.value.selectedSetup!!
        val stops = playback.stops
        owner.onAction(MainAction.SetDriftCorrection(true))
        assertEquals(stops + 1, playback.stops)
        assertEquals(original.speakers, owner.state.value.selectedSetup!!.speakers)
        owner.onAction(MainAction.SaveSetup)
        owner.onAction(MainAction.Play)
        assertEquals("a", playback.plays.last().second)
        assertEquals(47, playback.plays.last().first.last().delayMillis)
        owner.onAction(MainAction.SetVolume(13))
        assertEquals(13, playback.updates.last().last().volumePercent)
        owner.onAction(MainAction.SelectSetup("desk"))
        assertFalse(owner.state.value.selectedSetup!!.driftCorrectionEnabled)
        owner.onAction(MainAction.SelectSetup("living"))
        assertTrue(owner.state.value.selectedSetup!!.driftCorrectionEnabled)
        owner.onAction(MainAction.SetDriftCorrection(false))
        assertTrue(owner.state.value.savedSetups.getValue("living").driftCorrectionEnabled)
    }

    @Test fun oneOutputAndDemoCannotStartDriftAndCueOrTestRequestsStayExplicitlySeparate() = runTest {
        val playback = Playback()
        val owner = owner(playback)
        runCurrent(); owner.onAction(MainAction.AddOutput("a")); owner.onAction(MainAction.SetDriftCorrection(true))
        owner.onAction(MainAction.Play)
        assertTrue(playback.plays.isEmpty())
        assertTrue(owner.state.value.feedback!!.contains("two real outputs"))
        owner.onAction(MainAction.AddOutput("b")); owner.onAction(MainAction.SetDelay(21))
        owner.onAction(MainAction.PlayCalibration); owner.onAction(MainAction.TestSpeaker)
        assertEquals("a", playback.calibrations.single().second)
        assertEquals(21, playback.tests.single().delayMillis)
        assertTrue(playback.plays.isEmpty())
        owner.onAction(MainAction.SetDemoMode(true)); owner.onAction(MainAction.SetDriftCorrection(true))
        assertFalse(owner.state.value.selectedSetup!!.driftCorrectionEnabled)
        owner.onAction(MainAction.Play)
        assertTrue(playback.plays.isEmpty())
    }

    @Test fun stalePacketsClearDriftEstimatesAndFreshPacketsRecover() = runTest {
        val playback = Playback()
        val owner = owner(playback)
        val drift = OutputDriftTiming("a", false, DriftPhase.Tracking, 200.0, .2, -40.0, -200.0, .1, "Synthetic")
        val timing = OutputTiming("b", 48_000, 40, 1920, 4800, 9600, 2400, 5280, drift = drift)
        playback.playback.value = PlaybackSnapshot(PlaybackPhase.Playing, outputTimings = listOf(timing), timingSequence = 1)
        runCurrent(); assertEquals(DriftPhase.Tracking, owner.state.value.playback.outputTimings.single().drift!!.phase)
        advanceTimeBy(1001); runCurrent()
        val stale = owner.state.value.playback.outputTimings.single().drift!!
        assertEquals(DriftPhase.Inactive, stale.phase)
        assertNull(stale.relativeRatePpm); assertNull(stale.skewMillis); assertNull(stale.uncertaintyMillis)
        assertEquals(-200.0, stale.correctionPpm) // Last applied rate is historical, never a live estimate.
        playback.playback.value = playback.playback.value.copy(timingSequence = 2); runCurrent()
        assertEquals(drift, owner.state.value.playback.outputTimings.single().drift)
    }

    @Test fun invalidCorrectionCannotEnterPortableState() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1000.1, 1000.1))
            assertFailsWith<IllegalArgumentException> { OutputDriftTiming("a", false, DriftPhase.Tracking, correctionPpm = invalid, message = "Invalid") }
    }
}
