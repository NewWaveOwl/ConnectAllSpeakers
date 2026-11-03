package com.example.connectallspeakers

import com.example.connectallspeakers.ViewModels.main.*
import com.example.connectallspeakers.data.audio.*
import com.example.connectallspeakers.data.profiles.*
import com.example.connectallspeakers.model.audio.*
import com.example.connectallspeakers.model.profiles.SetupProfile
import com.example.connectallspeakers.state.MainDialog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Portable parameter validation, selected-card edits and durable restoration with fake services. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerEqStateTest {
    private class Outputs : AudioOutputRepository {
        override val outputs = MutableStateFlow(AudioOutputSnapshot(AudioOutputStatus.Ready,
            listOf(AudioOutputDevice("a", "Same"), AudioOutputDevice("b", "Same"))))
        override fun refresh() = Unit
    }
    private class Playback : AudioPlaybackController {
        override val playback = MutableStateFlow(PlaybackSnapshot(PlaybackPhase.Ready, PlaybackSource("fake.wav", 1000, 48000, 2)))
        val updates = mutableListOf<List<PlaybackRoute>>()
        var starts = 0; var stops = 0
        override fun loadFile(path: String) { starts++ }
        override fun play(routes: List<PlaybackRoute>, driftReferenceEndpointId: String?) { starts++ }
        override fun calibrate(routes: List<PlaybackRoute>, referenceEndpointId: String) { starts++ }
        override fun testSpeaker(route: PlaybackRoute) { starts++ }
        override fun pause() = Unit
        override fun stop() { stops++ }
        override fun updateRoutes(routes: List<PlaybackRoute>) { updates += routes }
    }
    private class Storage : SetupProfileRepository {
        var disk: SetupProfile? = null
        override suspend fun load() = disk
        override suspend fun save(profile: SetupProfile) { disk = SetupProfileCodec.decode(SetupProfileCodec.encode(profile)) }
    }

    @Test fun rejectsNonFiniteAndOutOfRangeParametersAndUsesActualRateForEnabledBands() {
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { EqBand(value) }
            assertFailsWith<IllegalArgumentException> { EqBand(1000.0, value) }
            assertFailsWith<IllegalArgumentException> { EqBand(1000.0, q = value) }
        }
        for (frequency in listOf(39.9, 16000.1)) assertFailsWith<IllegalArgumentException> { EqBand(frequency) }
        for (gain in listOf(-12.1, 12.1)) assertFailsWith<IllegalArgumentException> { EqBand(1000.0, gain) }
        for (q in listOf(.299, 2.001)) assertFailsWith<IllegalArgumentException> { EqBand(1000.0, q = q) }
        for (rate in listOf(8000, 11025, 44100, 48000, 192000)) {
            SpeakerEq(true, high = EqBand(maximumEqFrequency(rate))).validateForSampleRate(rate)
        }
        val high = SpeakerEq(true, high = EqBand(4000.0))
        assertFailsWith<IllegalArgumentException> { high.validateForSampleRate(8000) }
        high.validateForSampleRate(48000)
        high.copy(enabled = false).validateForSampleRate(8000) // Retained bypassed edits are safe.
        assertFailsWith<IllegalArgumentException> { maximumEqFrequency(7999) }
        assertFailsWith<IllegalArgumentException> { maximumEqFrequency(192001) }
    }

    @Test fun eqEditsResetAndSavedSnapshotsAffectOnlyTheSelectedCardAndRoom() = runTest {
        val playback = Playback(); val storage = Storage()
        val owner = MainViewModel(outputRepository = Outputs(), playbackController = playback,
            profileRepository = storage, collectionScope = backgroundScope)
        runCurrent()
        owner.onAction(MainAction.AddOutput("a")); owner.onAction(MainAction.AddOutput("b"))
        owner.onAction(MainAction.SetVolume(37)); owner.onAction(MainAction.SetDelay(25))
        val before = owner.state.value.selectedSpeaker!!
        val stops = playback.stops
        owner.onAction(MainAction.SetEqBand(1, 2000.0, -6.0, 1.2))
        owner.onAction(MainAction.SetEqEnabled(true))
        val savedEq = owner.state.value.selectedSpeaker!!.eq
        assertTrue(savedEq.enabled)
        assertEquals(-6.0, savedEq.mid.gainDb)
        assertEquals(SpeakerEq(), owner.state.value.selectedSetup!!.speakers.first().eq)
        assertEquals(before.copy(eq = savedEq), owner.state.value.selectedSpeaker)
        assertEquals(stops, playback.stops) // Live EQ updates do not restart native queues.
        assertEquals(0, playback.starts)
        assertEquals(savedEq, playback.updates.last().last().eq)
        owner.onAction(MainAction.SaveSetup); runCurrent()
        owner.onAction(MainAction.ResetPositions); owner.onAction(MainAction.ResetDelays)
        assertEquals(savedEq, owner.state.value.selectedSpeaker!!.eq)
        owner.onAction(MainAction.ResetEq)
        assertEquals(SpeakerEq(), owner.state.value.selectedSpeaker!!.eq)
        assertEquals(37, owner.state.value.selectedSpeaker!!.volume)
        assertEquals(savedEq, owner.state.value.savedSetups.getValue("living").speakers.last().eq)
        owner.onAction(MainAction.SelectSetup("desk")); owner.onAction(MainAction.AddOutput("b"))
        assertEquals(SpeakerEq(), owner.state.value.selectedSpeaker!!.eq)
        val restoredPlayback = Playback()
        val reopened = MainViewModel(outputRepository = Outputs(), playbackController = restoredPlayback,
            profileRepository = storage, collectionScope = backgroundScope)
        runCurrent()
        assertEquals(savedEq, reopened.state.value.selectedSpeaker!!.eq)
        assertEquals(before.delayMillis, reopened.state.value.selectedSpeaker!!.delayMillis)
        assertEquals(0, restoredPlayback.starts)
    }

    @Test fun invalidControlValuesOrSourceRateKeepSettingsAndShowReadableFeedback() = runTest {
        val playback = Playback()
        val owner = MainViewModel(outputRepository = Outputs(), playbackController = playback, collectionScope = backgroundScope)
        runCurrent(); owner.onAction(MainAction.AddOutput("a"))
        owner.onAction(MainAction.OpenDialog(MainDialog.SpeakerEq))
        val original = owner.state.value.selectedSpeaker!!.eq
        owner.onAction(MainAction.SetEqBand(0, Double.NaN, 0.0, 1.0))
        assertEquals(original, owner.state.value.selectedSpeaker!!.eq)
        assertTrue(owner.state.value.dialogError!!.contains("frequency"))
        owner.onAction(MainAction.SetEqBand(3, 1000.0, 0.0, 1.0))
        assertEquals(original, owner.state.value.selectedSpeaker!!.eq)
        owner.onAction(MainAction.SetEqBand(2, 16000.0, 6.0, 1.0))
        playback.playback.value = playback.playback.value.copy(source = PlaybackSource("8k.wav", 1000, 8000, 2)); runCurrent()
        owner.onAction(MainAction.SetEqEnabled(true))
        assertFalse(owner.state.value.selectedSpeaker!!.eq.enabled)
        assertTrue(owner.state.value.dialogError!!.contains("3600 Hz"))
        owner.onAction(MainAction.ResetEq)
        assertNull(owner.state.value.dialogError)
        assertEquals(original, owner.state.value.selectedSpeaker!!.eq)
    }
}
