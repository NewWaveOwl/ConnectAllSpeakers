package com.example.connectallspeakers

import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.ViewModels.main.MainViewModel
import com.example.connectallspeakers.data.audio.*
import com.example.connectallspeakers.model.RoomPosition
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.model.audio.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Controlled discovery acceptance tests, with no Windows APIs or hardware dependency. */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioDiscoveryStateTest {
    @Test
    fun realModeNeverFallsBackToSamplesIncludingUnsupportedAndError() = runTest {
        val repository = ControlledOutputs()
        val owner = MainViewModel(outputRepository = repository, collectionScope = backgroundScope)
        runCurrent()
        assertFalse(owner.state.value.demoMode)
        assertTrue(owner.state.value.setups.all { it.speakers.isEmpty() })
        owner.onAction(MainAction.AddSpeaker("studio"))
        repository.outputs.value = AudioOutputSnapshot(AudioOutputStatus.Error, message = "Controlled failure")
        runCurrent()
        assertTrue(owner.state.value.setups.all { it.speakers.isEmpty() })
        assertEquals(AudioOutputStatus.Error, owner.state.value.outputs.status)
        owner.onAction(MainAction.SetDemoMode(true))
        assertTrue(owner.state.value.selectedSpeaker!!.isSample)
        owner.onAction(MainAction.SetDemoMode(false))
        assertTrue(owner.state.value.selectedSetup!!.speakers.isEmpty())
        assertEquals(AudioOutputStatus.Error, owner.state.value.outputs.status)
    }

    @Test
    fun duplicateNamesUseEndpointIdentityAndCannotDuplicateAnAssignment() = runTest {
        val repository = ControlledOutputs()
        val owner = MainViewModel(outputRepository = repository, collectionScope = backgroundScope)
        repository.ready(AudioOutputDevice("endpoint-a", "Speakers"), AudioOutputDevice("endpoint-b", "Speakers"))
        runCurrent()
        owner.onAction(MainAction.AddOutput("endpoint-a"))
        val firstId = owner.state.value.selectedSpeaker!!.id
        owner.onAction(MainAction.AddOutput("endpoint-b"))
        assertEquals(2, owner.state.value.selectedSetup!!.speakers.size)
        assertNotEquals(firstId, owner.state.value.selectedSpeaker!!.id)
        owner.onAction(MainAction.AddOutput("endpoint-a"))
        assertEquals(2, owner.state.value.selectedSetup!!.speakers.size)
        assertEquals(firstId, owner.state.value.selectedSpeaker!!.id)
        owner.onAction(MainAction.AddOutput("not-an-endpoint"))
        assertEquals(2, owner.state.value.selectedSetup!!.speakers.size)
    }

    @Test
    fun disconnectAndSameEndpointReconnectPreserveAllRoomEditsAndSelection() = runTest {
        val repository = ControlledOutputs()
        val owner = MainViewModel(outputRepository = repository, collectionScope = backgroundScope)
        repository.ready(AudioOutputDevice("stable-id", "Windows speaker"))
        runCurrent()
        owner.onAction(MainAction.AddOutput("stable-id"))
        val instanceId = owner.state.value.selectedSpeaker!!.id
        owner.onAction(MainAction.RenameSpeaker("My custom name"))
        owner.onAction(MainAction.AssignChannel(SpeakerChannel.Right))
        owner.onAction(MainAction.SetVolume(34))
        owner.onAction(MainAction.MoveSpeaker(instanceId, RoomPosition(.2f, .7f)))
        val edited = owner.state.value.selectedSpeaker!!
        repository.ready(AudioOutputDevice("different-id", "Windows speaker"))
        runCurrent()
        assertEquals(OutputAvailability.Unavailable, owner.state.value.selectedSpeaker!!.availability)
        assertEquals("stable-id", owner.state.value.selectedSpeaker!!.output!!.endpointId)
        repository.ready(AudioOutputDevice("stable-id", "New Windows label", isDefault = true, type = AudioOutputType.Headphones))
        runCurrent()
        val restored = owner.state.value.selectedSpeaker!!
        assertEquals(edited.copy(output = restored.output), restored)
        assertEquals(OutputAvailability.Active, restored.availability)
        assertEquals("New Windows label", restored.output!!.displayName)
        assertEquals("My custom name", restored.name)
        assertTrue(restored.output!!.isDefault)
        assertEquals(instanceId, owner.state.value.selectedSetup!!.selectedSpeakerId)
    }

    @Test
    fun failedOrLoadingRefreshMeansUnknownAndBlocksStalePickerAdds() = runTest {
        val repository = ControlledOutputs()
        val owner = MainViewModel(outputRepository = repository, collectionScope = backgroundScope)
        repository.ready(AudioOutputDevice("a", "First"), AudioOutputDevice("b", "Second"))
        runCurrent()
        owner.onAction(MainAction.AddOutput("a"))
        repository.outputs.value = repository.outputs.value.copy(status = AudioOutputStatus.Loading)
        runCurrent()
        assertEquals(OutputAvailability.Unknown, owner.state.value.selectedSpeaker!!.availability)
        owner.onAction(MainAction.AddOutput("b"))
        assertEquals(1, owner.state.value.selectedSetup!!.speakers.size)
        repository.outputs.value = repository.outputs.value.copy(status = AudioOutputStatus.Error, message = "Refresh failed")
        runCurrent()
        assertEquals(OutputAvailability.Unknown, owner.state.value.selectedSpeaker!!.availability)
        assertEquals(2, owner.state.value.outputs.devices.size)
        owner.onAction(MainAction.AddOutput("b"))
        assertEquals(1, owner.state.value.selectedSetup!!.speakers.size)
        repository.ready()
        runCurrent()
        assertEquals(OutputAvailability.Unavailable, owner.state.value.selectedSpeaker!!.availability)
        assertEquals(AudioOutputStatus.Ready, owner.state.value.outputs.status)
        assertTrue(owner.state.value.outputs.devices.isEmpty())
    }

    @Test
    fun metadataAndDisconnectChangesReachEveryRoomWithoutChangingSettings() = runTest {
        val repository = ControlledOutputs()
        val owner = MainViewModel(outputRepository = repository, collectionScope = backgroundScope)
        repository.ready(AudioOutputDevice("a", "Output"))
        runCurrent()
        owner.onAction(MainAction.AddOutput("a"))
        owner.onAction(MainAction.SetVolume(23))
        owner.onAction(MainAction.SelectSetup("desk"))
        owner.onAction(MainAction.AddOutput("a"))
        owner.onAction(MainAction.SetVolume(67))
        repository.ready()
        runCurrent()
        assertEquals(OutputAvailability.Unavailable, owner.state.value.selectedSpeaker!!.availability)
        assertEquals(67, owner.state.value.selectedSpeaker!!.volume)
        owner.onAction(MainAction.SelectSetup("living"))
        assertEquals(OutputAvailability.Unavailable, owner.state.value.selectedSpeaker!!.availability)
        assertEquals(23, owner.state.value.selectedSpeaker!!.volume)
    }

    @Test
    fun demoAndRealEditsRemainIsolatedWhileInactiveRealCardsReceiveUpdates() = runTest {
        val repository = ControlledOutputs()
        val owner = MainViewModel(outputRepository = repository, collectionScope = backgroundScope)
        repository.ready(AudioOutputDevice("a", "Real output"))
        runCurrent()
        owner.onAction(MainAction.AddOutput("a"))
        owner.onAction(MainAction.RenameSpeaker("Real custom name"))
        owner.onAction(MainAction.SaveSetup)
        owner.onAction(MainAction.SetDemoMode(true))
        owner.onAction(MainAction.RenameSpeaker("Demo custom name"))
        owner.onAction(MainAction.SaveSetup)
        repository.ready()
        runCurrent()
        assertTrue(owner.state.value.selectedSpeaker!!.isSample)
        assertEquals("Demo custom name", owner.state.value.selectedSpeaker!!.name)
        owner.onAction(MainAction.SetDemoMode(false))
        assertEquals("Real custom name", owner.state.value.selectedSpeaker!!.name)
        assertEquals(OutputAvailability.Unavailable, owner.state.value.selectedSpeaker!!.availability)
        assertFalse(owner.state.value.savedSetups.getValue("living").speakers.first().isSample)
        owner.onAction(MainAction.SetDemoMode(true))
        assertEquals("Demo custom name", owner.state.value.selectedSpeaker!!.name)
        assertTrue(owner.state.value.savedSetups.getValue("living").speakers.first().isSample)
    }

    @Test
    fun refreshDelegatesToRepositoryAndUnknownTypesStayUnknown() = runTest {
        val repository = ControlledOutputs()
        val owner = MainViewModel(outputRepository = repository, collectionScope = backgroundScope)
        repository.ready(AudioOutputDevice("a", "Bluetooth USB HDMI Speakers"))
        runCurrent()
        owner.onAction(MainAction.RefreshOutputs)
        assertEquals(1, repository.refreshes)
        owner.onAction(MainAction.AddOutput("a"))
        assertEquals(AudioOutputType.Unknown, owner.state.value.selectedSpeaker!!.output!!.type)
    }

    /** Explicit controllable source, never used by platform application assembly. */
    private class ControlledOutputs : AudioOutputRepository {
        override val outputs = MutableStateFlow(AudioOutputSnapshot())
        var refreshes = 0
        override fun refresh() { refreshes++ }
        fun ready(vararg devices: AudioOutputDevice) {
            outputs.value = AudioOutputSnapshot(AudioOutputStatus.Ready, devices.toList(), revision = outputs.value.revision + 1)
        }
    }
}
