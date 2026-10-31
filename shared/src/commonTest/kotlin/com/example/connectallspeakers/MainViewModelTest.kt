package com.example.connectallspeakers

import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.ViewModels.main.MainViewModel
import com.example.connectallspeakers.model.RoomPosition
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.state.MainDialog
import kotlin.test.*

/** Behavioral acceptance tests for the frontend session; no hardware or wall-clock dependency. */
class MainViewModelTest {
    @Test
    fun initialSelectionIsTheRightSampleAtEightyPercent() {
        val state = MainViewModel().state.value
        assertEquals("Living room", state.selectedSetup?.name)
        assertEquals("Studio Right", state.selectedSpeaker?.name)
        assertEquals(80, state.selectedSpeaker?.volume)
        assertEquals(SpeakerChannel.Right, state.selectedSpeaker?.channel)
    }

    @Test
    fun switchingRoomsRetainsIndependentEditsAndSelections() {
        val owner = MainViewModel()
        owner.onAction(MainAction.SelectSpeaker("living-left"))
        owner.onAction(MainAction.RenameSpeaker("Window sample"))
        owner.onAction(MainAction.SetVolume(27))
        owner.onAction(MainAction.SelectSetup("desk"))
        assertEquals("Studio Right", owner.state.value.selectedSpeaker?.name)
        owner.onAction(MainAction.AssignChannel(SpeakerChannel.Mono))
        owner.onAction(MainAction.SelectSetup("living"))
        assertEquals("Window sample", owner.state.value.selectedSpeaker?.name)
        assertEquals(27, owner.state.value.selectedSpeaker?.volume)
        assertEquals(SpeakerChannel.Left, owner.state.value.selectedSpeaker?.channel)
        assertEquals(80, owner.state.value.selectedSetup?.speakers?.last()?.volume)
        owner.onAction(MainAction.SelectSetup("desk"))
        assertEquals(SpeakerChannel.Mono, owner.state.value.selectedSpeaker?.channel)
    }

    @Test
    fun inspectorChangesOnlySelectedSpeakerAndBoundsVolume() {
        val owner = MainViewModel()
        val left = owner.state.value.selectedSetup!!.speakers.first()
        owner.onAction(MainAction.RenameSpeaker("Rear sample"))
        owner.onAction(MainAction.AssignChannel(SpeakerChannel.Left))
        owner.onAction(MainAction.SetVolume(700))
        assertEquals(100, owner.state.value.selectedSpeaker?.volume)
        assertEquals(left, owner.state.value.selectedSetup?.speakers?.first())
        owner.onAction(MainAction.SetVolume(-2))
        assertEquals(0, owner.state.value.selectedSpeaker?.volume)
        owner.onAction(MainAction.SelectSpeaker("missing"))
        assertEquals("living-right", owner.state.value.selectedSpeaker?.id)
    }

    @Test
    fun movesStayNormalizedAndNeverReassignChannels() {
        val owner = MainViewModel()
        owner.onAction(MainAction.MoveSpeaker("living-right", RoomPosition(-4f, 8f)))
        assertEquals(RoomPosition(0f, 1f), owner.state.value.selectedSpeaker?.position)
        assertEquals(SpeakerChannel.Right, owner.state.value.selectedSpeaker?.channel)
        owner.onAction(MainAction.MoveSpeaker("living-right", RoomPosition(Float.NaN, .4f)))
        assertEquals(RoomPosition(0f, 1f), owner.state.value.selectedSpeaker?.position)
    }

    @Test
    fun snapIsAppliedAtGestureEndAndRespectsToggle() {
        val owner = MainViewModel()
        owner.onAction(MainAction.MoveSpeaker("living-right", RoomPosition(.34f, .66f)))
        assertEquals(RoomPosition(.34f, .66f), owner.state.value.selectedSpeaker?.position)
        owner.onAction(MainAction.FinishMove("living-right", .1f, .1f))
        assertEquals(.3f, owner.state.value.selectedSpeaker!!.position.x, .0001f)
        assertEquals(.7f, owner.state.value.selectedSpeaker!!.position.y, .0001f)
        owner.onAction(MainAction.ToggleSnap)
        owner.onAction(MainAction.MoveSpeaker("living-right", RoomPosition(.34f, .66f)))
        owner.onAction(MainAction.FinishMove("living-right", .1f, .1f))
        assertEquals(RoomPosition(.34f, .66f), owner.state.value.selectedSpeaker?.position)
    }

    @Test
    fun resetRestoresHomesAndZoomWhileRetainingInspectorEdits() {
        val owner = MainViewModel()
        val home = owner.state.value.selectedSpeaker!!.position
        owner.onAction(MainAction.RenameSpeaker("Edited sample"))
        owner.onAction(MainAction.SetVolume(19))
        owner.onAction(MainAction.AssignChannel(SpeakerChannel.Mono))
        owner.onAction(MainAction.MoveSpeaker("living-right", RoomPosition(.4f, .9f)))
        owner.onAction(MainAction.Zoom(.5f))
        owner.onAction(MainAction.SelectSetup("desk"))
        owner.onAction(MainAction.MoveSpeaker("desk-right", RoomPosition(.8f, .7f)))
        owner.onAction(MainAction.SelectSetup("living"))
        owner.onAction(MainAction.ResetPositions)
        assertEquals(home, owner.state.value.selectedSpeaker?.position)
        assertEquals(1f, owner.state.value.selectedSetup?.zoom)
        assertEquals("Edited sample", owner.state.value.selectedSpeaker?.name)
        assertEquals(19, owner.state.value.selectedSpeaker?.volume)
        assertEquals(SpeakerChannel.Mono, owner.state.value.selectedSpeaker?.channel)
        owner.onAction(MainAction.SelectSetup("desk"))
        assertEquals(RoomPosition(.8f, .7f), owner.state.value.selectedSpeaker?.position)
    }

    @Test
    fun savedSnapshotRemainsIndependentOfSubsequentEdits() {
        val owner = MainViewModel()
        owner.onAction(MainAction.SaveSetup)
        val saved = owner.state.value.savedSetups.getValue("living")
        owner.onAction(MainAction.RenameSpeaker("After save"))
        owner.onAction(MainAction.MoveSpeaker("living-right", RoomPosition(.2f, .3f)))
        assertEquals(saved, owner.state.value.savedSetups["living"])
        assertEquals("Studio Right", saved.speakers.last().name)
        assertTrue(owner.state.value.feedback!!.contains("this session"))
    }

    @Test
    fun setupDialogsRejectBlankAndDuplicateNamesAndTrimValidNames() {
        val owner = MainViewModel()
        owner.onAction(MainAction.OpenDialog(MainDialog.AddSetup))
        owner.onAction(MainAction.DialogName("   "))
        owner.onAction(MainAction.ConfirmDialog)
        assertNotNull(owner.state.value.dialogError)
        owner.onAction(MainAction.DialogName("living ROOM"))
        owner.onAction(MainAction.ConfirmDialog)
        assertNotNull(owner.state.value.dialogError)
        assertEquals(3, owner.state.value.setups.size)
        owner.onAction(MainAction.DialogName("  New room  "))
        owner.onAction(MainAction.ConfirmDialog)
        assertEquals("New room", owner.state.value.selectedSetup?.name)
        assertTrue(owner.state.value.selectedSetup!!.speakers.isEmpty())
        owner.onAction(MainAction.OpenDialog(MainDialog.RenameSetup))
        owner.onAction(MainAction.DialogName("Reading room"))
        owner.onAction(MainAction.ConfirmDialog)
        assertEquals("Reading room", owner.state.value.selectedSetup?.name)
    }

    @Test
    fun deletingLastSetupAllowsCreatingAnEmptyRoomAgain() {
        val owner = MainViewModel()
        repeat(3) {
            owner.onAction(MainAction.OpenDialog(MainDialog.DeleteSetup))
            owner.onAction(MainAction.ConfirmDialog)
        }
        assertNull(owner.state.value.selectedSetup)
        assertNull(owner.state.value.selectedSpeaker)
        owner.onAction(MainAction.SaveSetup)
        owner.onAction(MainAction.ResetPositions)
        owner.onAction(MainAction.AddSpeaker("studio"))
        assertTrue(owner.state.value.setups.isEmpty())
        owner.onAction(MainAction.OpenDialog(MainDialog.AddSetup))
        owner.onAction(MainAction.DialogName("Recovered room"))
        owner.onAction(MainAction.ConfirmDialog)
        assertEquals("Recovered room", owner.state.value.selectedSetup?.name)
    }

    @Test
    fun repeatedSamplePicksHaveUniqueIdsAndResetToTheirOwnHome() {
        val owner = MainViewModel()
        repeat(4) { owner.onAction(MainAction.AddSpeaker("studio")) }
        val speakers = owner.state.value.setups.flatMap { it.speakers }
        assertEquals(speakers.size, speakers.map { it.id }.toSet().size)
        val added = owner.state.value.selectedSpeaker!!
        owner.onAction(MainAction.MoveSpeaker(added.id, RoomPosition(1f, 0f)))
        owner.onAction(MainAction.ResetPositions)
        assertEquals(added.homePosition, owner.state.value.selectedSpeaker?.position)
        owner.onAction(MainAction.AddSpeaker("unknown"))
        assertEquals(6, owner.state.value.selectedSetup?.speakers?.size)
    }
}
