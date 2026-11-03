package com.example.connectallspeakers

import com.example.connectallspeakers.ViewModels.main.*
import com.example.connectallspeakers.data.audio.*
import com.example.connectallspeakers.data.profiles.*
import com.example.connectallspeakers.model.RoomPosition
import com.example.connectallspeakers.model.audio.*
import com.example.connectallspeakers.model.profiles.*
import com.example.connectallspeakers.state.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Restore/explicit-save behavior with controlled storage and discovery; never constructs a native service. */
@OptIn(ExperimentalCoroutinesApi::class)
class PersistentSetupStateTest {
    private class Storage(var disk: SetupProfile? = null) : SetupProfileRepository {
        var loadFailure: Exception? = null
        var saveFailure: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        var writes = 0
        override suspend fun load(): SetupProfile? { loadFailure?.let { throw it }; return disk }
        override suspend fun save(profile: SetupProfile) {
            gate?.await(); saveFailure?.let { throw it }
            disk = SetupProfileCodec.decode(SetupProfileCodec.encode(profile)); writes++
        }
    }
    private class Outputs : AudioOutputRepository {
        override val outputs = MutableStateFlow(AudioOutputSnapshot(AudioOutputStatus.Ready,
            listOf(AudioOutputDevice("endpoint-a", "Same name"), AudioOutputDevice("endpoint-b", "Same name"))))
        override fun refresh() = Unit
    }
    private class Playback : AudioPlaybackController {
        override val playback = MutableStateFlow(PlaybackSnapshot())
        var audioRequests = 0
        override fun loadFile(path: String) { audioRequests++ }
        override fun play(routes: List<PlaybackRoute>, driftReferenceEndpointId: String?) { audioRequests++ }
        override fun testSpeaker(route: PlaybackRoute) { audioRequests++ }
        override fun calibrate(routes: List<PlaybackRoute>, referenceEndpointId: String) { audioRequests++ }
        override fun pause() = Unit
        override fun stop() = Unit
        override fun updateRoutes(routes: List<PlaybackRoute>) = Unit
    }
    private fun TestScope.owner(storage: Storage, outputs: Outputs = Outputs(), playback: Playback = Playback()) =
        MainViewModel(outputRepository = outputs, playbackController = playback,
            collectionScope = backgroundScope, profileRepository = storage)

    @Test fun restoreRetainsSettingsAndSelectionWithoutAnyAudioRequest() = runTest {
        val playback = Playback(); val original = profileFixture(); val storage = Storage(original)
        val owner = owner(storage, playback = playback)
        assertEquals(ProfileStorageStatus.Loading, owner.state.value.profileStorage)
        owner.onAction(MainAction.RenameSpeaker("Ignored while loading"))
        runCurrent()
        val state = owner.state.value
        assertEquals(ProfileStorageStatus.Ready, state.profileStorage)
        assertEquals("room", state.selectedSetupId)
        assertEquals("card-b", state.selectedSpeaker?.id)
        assertEquals(123, state.selectedSpeaker?.delayMillis)
        assertEquals(37, state.selectedSpeaker?.volume)
        assertEquals(RoomPosition(.8f, .1f), state.selectedSpeaker?.homePosition)
        assertTrue(state.selectedSetup!!.driftCorrectionEnabled)
        assertFalse(state.hasUnsavedSetup)
        assertEquals(0, playback.audioRequests)
        assertNull(state.playback.source)
        assertTrue(state.playback.outputTimings.isEmpty())
        owner.onAction(MainAction.SetDemoMode(true))
        assertTrue(owner.state.value.selectedSpeaker!!.isSample)
        assertFalse(owner.state.value.hasUnsavedSetup)
        owner.onAction(MainAction.SetDemoMode(false))
        assertEquals("endpoint-b", owner.state.value.selectedSpeaker?.output?.endpointId)
        assertEquals(0, playback.audioRequests)
    }

    @Test fun onlyDurableCompletionReportsSuccessAndPendingSnapshotStaysIsolated() = runTest {
        val storage = Storage(profileFixture()); val owner = owner(storage)
        runCurrent()
        owner.onAction(MainAction.RenameSpeaker("Captured"))
        storage.gate = CompletableDeferred()
        owner.onAction(MainAction.SaveSetup)
        runCurrent()
        assertTrue(owner.state.value.savingProfiles)
        assertEquals("Right", owner.state.value.savedSetups.getValue("room").speakers.last().name)
        assertFalse(owner.state.value.feedback.orEmpty().contains("saved on this PC"))
        owner.onAction(MainAction.RenameSpeaker("Unsaved later edit"))
        owner.onAction(MainAction.SaveSetup) // Coalesced by the busy guard, not a second write.
        owner.onAction(MainAction.SetDemoMode(true))
        storage.gate!!.complete(Unit); runCurrent()
        assertEquals(1, storage.writes)
        assertTrue(owner.state.value.demoMode)
        assertTrue(owner.state.value.selectedSpeaker!!.isSample)
        owner.onAction(MainAction.SetDemoMode(false))
        assertEquals("Unsaved later edit", owner.state.value.selectedSpeaker?.name)
        assertEquals("Captured", owner.state.value.savedSetups.getValue("room").speakers.last().name)
        assertTrue(owner.state.value.hasUnsavedSetup)
        val reopened = owner(storage); runCurrent()
        assertEquals("Captured", reopened.state.value.selectedSpeaker?.name)
        assertFalse(reopened.state.value.demoMode)
    }

    @Test fun unsavedOtherRoomsAndSelectionDoNotLeakIntoAnExplicitRoomSave() = runTest {
        val storage = Storage(); val owner = owner(storage)
        runCurrent()
        owner.onAction(MainAction.AddOutput("endpoint-a")); owner.onAction(MainAction.RenameSpeaker("Saved left"))
        owner.onAction(MainAction.SaveSetup); runCurrent()
        owner.onAction(MainAction.RenameSpeaker("Discard me"))
        owner.onAction(MainAction.SelectSetup("desk"))
        owner.onAction(MainAction.AddOutput("endpoint-b"))
        owner.onAction(MainAction.SaveSetup); runCurrent()
        owner.onAction(MainAction.SelectSetup("evening")) // Selection itself needs Save.
        val reopened = owner(storage); runCurrent()
        assertEquals("desk", reopened.state.value.selectedSetupId)
        assertEquals(2, reopened.state.value.setups.size)
        reopened.onAction(MainAction.SelectSetup("living"))
        assertEquals("Saved left", reopened.state.value.selectedSpeaker?.name)
        assertEquals(0, reopened.state.value.selectedSpeaker?.delayMillis)
    }

    @Test fun missingReconnectedAndSameNamedReplacementNeverChangeAssignedIdentityOrEdits() = runTest {
        val outputs = Outputs()
        outputs.outputs.value = AudioOutputSnapshot(AudioOutputStatus.Ready, listOf(AudioOutputDevice("different-id", "Same name")))
        val owner = owner(Storage(profileFixture()), outputs); runCurrent()
        assertEquals(2, owner.state.value.selectedSetup!!.speakers.size)
        assertTrue(owner.state.value.selectedSetup!!.speakers.all { it.availability == OutputAvailability.Unavailable })
        assertEquals("endpoint-b", owner.state.value.selectedSpeaker?.output?.endpointId)
        outputs.outputs.value = AudioOutputSnapshot(AudioOutputStatus.Error, message = "Refresh failed")
        runCurrent()
        assertEquals(OutputAvailability.Unknown, owner.state.value.selectedSpeaker?.availability)
        outputs.outputs.value = AudioOutputSnapshot(AudioOutputStatus.Ready, listOf(AudioOutputDevice("endpoint-b", "Fresh OS name", isDefault = true)))
        runCurrent()
        assertEquals(OutputAvailability.Active, owner.state.value.selectedSpeaker?.availability)
        assertEquals("Right", owner.state.value.selectedSpeaker?.name)
        assertEquals(123, owner.state.value.selectedSpeaker?.delayMillis)
        assertFalse(owner.state.value.hasUnsavedSetup)
    }

    @Test fun loadFailureIsVisibleBlocksSavingAndExplicitRetryCanRecover() = runTest {
        val storage = Storage(profileFixture()).apply { loadFailure = IllegalArgumentException("Unsupported version") }
        val owner = owner(storage); runCurrent()
        assertEquals(ProfileStorageStatus.LoadFailed, owner.state.value.profileStorage)
        assertTrue(owner.state.value.profileError!!.contains("Unsupported version"))
        owner.onAction(MainAction.SaveSetup); runCurrent()
        assertEquals(0, storage.writes)
        owner.onAction(MainAction.AddOutput("endpoint-a"))
        owner.onAction(MainAction.OpenDialog(MainDialog.ReloadSetups))
        owner.onAction(MainAction.ConfirmDialog); runCurrent()
        assertEquals(1, owner.state.value.selectedSetup?.speakers?.size) // Failed retry keeps edits.
        storage.loadFailure = null
        owner.onAction(MainAction.OpenDialog(MainDialog.ReloadSetups))
        owner.onAction(MainAction.ConfirmDialog); runCurrent()
        assertEquals(ProfileStorageStatus.Ready, owner.state.value.profileStorage)
        assertEquals("room", owner.state.value.selectedSetupId)
        assertNull(owner.state.value.profileError)
    }

    @Test fun saveFailureKeepsOldSnapshotAndAllowsRetryWithoutLosingEdits() = runTest {
        val original = profileFixture(); val storage = Storage(original); val owner = owner(storage)
        runCurrent()
        owner.onAction(MainAction.SetVolume(9))
        storage.saveFailure = IllegalStateException("Controlled access failure")
        owner.onAction(MainAction.SaveSetup); runCurrent()
        assertEquals(original, storage.disk)
        assertEquals(37, owner.state.value.savedSetups.getValue("room").speakers.last().volume)
        assertEquals(9, owner.state.value.selectedSpeaker?.volume)
        assertTrue(owner.state.value.profileError!!.contains("not saved"))
        assertFalse(owner.state.value.savingProfiles)
        storage.saveFailure = null
        owner.onAction(MainAction.SaveSetup); runCurrent()
        assertEquals(9, storage.disk!!.real.rooms.single().speakers.last().volume)
        assertNull(owner.state.value.profileError)
    }

    @Test fun savedDeletionWaitsForStorageAndFailedDeletionRetainsRoomThenRestoresEmptyBank() = runTest {
        val storage = Storage(profileFixture()); val owner = owner(storage); runCurrent()
        storage.saveFailure = IllegalStateException("Cannot replace")
        owner.onAction(MainAction.OpenDialog(MainDialog.DeleteSetup)); owner.onAction(MainAction.ConfirmDialog); runCurrent()
        assertNotNull(owner.state.value.selectedSetup)
        assertEquals(1, storage.disk!!.real.rooms.size)
        storage.saveFailure = null; storage.gate = CompletableDeferred()
        owner.onAction(MainAction.OpenDialog(MainDialog.DeleteSetup)); owner.onAction(MainAction.ConfirmDialog); runCurrent()
        assertNotNull(owner.state.value.selectedSetup)
        owner.onAction(MainAction.SetDemoMode(true))
        storage.gate!!.complete(Unit); runCurrent()
        assertEquals("Demo room", owner.state.value.selectedSetup?.name)
        owner.onAction(MainAction.SetDemoMode(false))
        assertNull(owner.state.value.selectedSetup)
        val reopened = owner(storage); runCurrent()
        assertTrue(reopened.state.value.setups.isEmpty())
        assertNull(reopened.state.value.selectedSetupId)
        reopened.onAction(MainAction.OpenDialog(MainDialog.AddSetup))
        reopened.onAction(MainAction.DialogName("New room")); reopened.onAction(MainAction.ConfirmDialog)
        assertEquals("New room", reopened.state.value.selectedSetup?.name)
        assertTrue(reopened.state.value.selectedSetup!!.speakers.isEmpty())
    }

    @Test fun demoSaveAndNewIdentitiesStaySeparateAfterRestore() = runTest {
        val storage = Storage(profileFixture()); val owner = owner(storage); runCurrent()
        owner.onAction(MainAction.SetDemoMode(true))
        owner.onAction(MainAction.RenameSpeaker("Saved demo")); owner.onAction(MainAction.SaveSetup); runCurrent()
        val reopened = owner(storage); runCurrent()
        assertTrue(reopened.state.value.demoMode)
        assertEquals("Saved demo", reopened.state.value.selectedSpeaker?.name)
        reopened.onAction(MainAction.AddSpeaker("studio"))
        val cards = reopened.state.value.setups.flatMap { it.speakers }
        assertEquals(cards.size, cards.map { it.id }.toSet().size)
        reopened.onAction(MainAction.SetDemoMode(false))
        assertEquals("endpoint-b", reopened.state.value.selectedSpeaker?.output?.endpointId)
        assertEquals("Right", reopened.state.value.selectedSpeaker?.name)
        assertFalse(reopened.state.value.selectedSpeaker!!.isSample)
    }

    @Test fun invalidDraftSaveIsRecoverableAndFutureSettingsSurviveSuccessfulRetry() = runTest {
        val base = profileFixture()
        val room = base.real.rooms.single()
        val futureRoom = room.copy(extensions = mapOf("ext.eq.enabled" to "false"),
            speakers = room.speakers.map { it.copy(extensions = mapOf("ext.eq.lowGainDb" to "-2.5")) })
        val original = base.copy(real = ProfileBank(listOf(futureRoom), room.id))
        val storage = Storage(original); val owner = owner(storage); runCurrent()
        owner.onAction(MainAction.RenameSpeaker("   "))
        owner.onAction(MainAction.SaveSetup); runCurrent()
        assertEquals(0, storage.writes)
        assertEquals(original, storage.disk)
        assertTrue(owner.state.value.profileError!!.contains("Speaker names"))
        owner.onAction(MainAction.RenameSpeaker("Valid draft"))
        owner.onAction(MainAction.SaveSetup); runCurrent()
        val saved = storage.disk!!.real.rooms.single()
        assertEquals("Valid draft", saved.speakers.last().name)
        assertEquals("-2.5", saved.speakers.last().extensions["ext.eq.lowGainDb"])
        assertEquals("false", saved.extensions["ext.eq.enabled"])
        assertNull(owner.state.value.profileError)
    }
}
