package com.example.connectallspeakers.View.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.View.main.components.*
import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.state.WorkspaceMode
import com.example.connectallspeakers.View.group.GroupSoundScreen
import com.example.connectallspeakers.ViewModels.main.GroupSoundAction
import com.example.connectallspeakers.state.MainDialog
import com.example.connectallspeakers.state.MainUiState
import com.example.connectallspeakers.state.ProfileStorageStatus
import com.example.connectallspeakers.ui.theme.AllConnectColors as C
import connectallspeakers.shared.generated.resources.Res
import connectallspeakers.shared.generated.resources.allconnect_logo_render
import org.jetbrains.compose.resources.painterResource

// Layout settings
private val ScreenPadding = 24.dp
private val InspectorWidth = 336.dp
private val StackedBreakpoint = 1000.dp
private val MinCanvasHeight = 360.dp
private val WorkspaceChromeHeight = 540.dp

/** Stateless room workspace, with real outputs and a separately labeled demo mode.
 * @param state Immutable session snapshot from the screen owner.
 * @param onAction User intent callback.
 * @param modifier Parent sizing.
 */
@Composable
fun MainScreen(state: MainUiState, onAction: (MainAction) -> Unit, modifier: Modifier = Modifier) {
    if (state.profileStorage == ProfileStorageStatus.Loading) {
        Column(modifier.fillMaxSize().background(C.Paper).padding(ScreenPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text("Loading saved setups…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        return
    }
    if (state.workspace == WorkspaceMode.GroupSound) {
        GroupSoundScreen(state, onAction, modifier)
        SetupDialogs(state, onAction)
        return
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= StackedBreakpoint
        val canvasHeight = (maxHeight - WorkspaceChromeHeight).coerceAtLeast(MinCanvasHeight)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(C.Paper)) {
            WorkspaceHeader(state, onAction)
            SetupToolbar(state, onAction)
            ProfileStorageStatus(state, onAction)
            if (wide) {
                Row(Modifier.fillMaxWidth().padding(ScreenPadding), horizontalArrangement = Arrangement.spacedBy(ScreenPadding)) {
                    RoomColumn(state, onAction, Modifier.weight(1f), canvasHeight)
                    SpeakerInspector(state.selectedSpeaker, state.selectedSetup != null, state.demoMode, onAction, Modifier.width(InspectorWidth),
                        state.selectedSetup?.calibrationReference?.id, testingSpeakerId = state.testingSpeakerId)
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RoomColumn(state, onAction, Modifier.fillMaxWidth(), canvasHeight)
                    SpeakerInspector(state.selectedSpeaker, state.selectedSetup != null, state.demoMode, onAction, Modifier.fillMaxWidth(),
                        state.selectedSetup?.calibrationReference?.id, testingSpeakerId = state.testingSpeakerId)
                }
            }
        }
        state.feedback?.let { feedback ->
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp).widthIn(max = 620.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
                action = { TextButton({ onAction(MainAction.DismissFeedback) }) { Text("Dismiss", color = C.Sage) } }) {
                Text(feedback)
            }
        }
    }
    SetupDialogs(state, onAction)
}

/** Renders the exported 44 × 52 logo slot and the Settings action.
 * @param state Current navigation destination. @param onAction Explicit navigation/settings intent.
 */
@Composable
fun WorkspaceHeader(state: MainUiState, onAction: (MainAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val flexibleSpace = (maxWidth - if (state.workspace == WorkspaceMode.GroupSound) 822.dp else 660.dp).coerceAtLeast(0.dp)
    Row(Modifier.fillMaxWidth().height(76.dp).background(MaterialTheme.colorScheme.surface)
        .horizontalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp, 52.dp).clip(RoundedCornerShape(10.dp)).background(C.Paper)) {
            Image(painterResource(Res.drawable.allconnect_logo_render), "AllConnect logo", Modifier.size(44.dp, 52.dp))
        }
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = C.Plum)) { append("All") }
            withStyle(SpanStyle(color = C.Teal)) { append("Connect") }
        }, style = MaterialTheme.typography.titleLarge)
        WorkspaceTab("Room setup", state.workspace == WorkspaceMode.Room, Glyph.Speaker) { onAction(MainAction.SelectWorkspace(WorkspaceMode.Room)) }
        WorkspaceTab("Group Sound", state.workspace == WorkspaceMode.GroupSound, Glyph.Headphones) { onAction(MainAction.SelectWorkspace(WorkspaceMode.GroupSound)) }
        Spacer(Modifier.width(flexibleSpace))
        if (state.workspace == WorkspaceMode.GroupSound) WorkspaceButton("Audio sources", { onAction(MainAction.Group(GroupSoundAction.ChooseSource)) }, glyph = "\uE890")
        WorkspaceButton("Settings", { onAction(MainAction.OpenDialog(MainDialog.Settings)) }, glyph = Glyph.Settings)
    }
    }
}

/** Accessible mode selector, preserving the design's sage selected treatment.
 * @param text Visible destination. @param active Whether this is the current view.
 * @param glyph Decorative icon. @param onClick Explicit navigation intent.
 */
@Composable
private fun WorkspaceTab(text: String, active: Boolean, glyph: String, onClick: () -> Unit) {
    if (active) Surface(onClick, shape = RoundedCornerShape(10.dp), color = C.Sage,
        modifier = Modifier.height(42.dp).semantics { selected = true; role = Role.Tab }) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesignIcon(glyph, C.Ink)
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    } else WorkspaceButton(text, onClick, glyph = glyph, modifier = Modifier.semantics { role = Role.Tab; selected = false })
}

/** Horizontal room picker and small dialog actions; overflow remains scrollable.
 * @param state Session rooms and current identity.
 * @param onAction User intent callback.
 */
@Composable
private fun SetupToolbar(state: MainUiState, onAction: (MainAction) -> Unit) {
    Row(Modifier.fillMaxWidth().height(100.dp).background(MaterialTheme.colorScheme.surface)
        .horizontalScroll(rememberScrollState()).padding(horizontal = ScreenPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("All setups", color = C.Teal, style = MaterialTheme.typography.titleLarge)
        state.setups.forEach { room ->
            if (room.id == state.selectedSetupId) {
                Surface(onClick = { onAction(MainAction.SelectSetup(room.id)) }, color = C.Sage, shape = RoundedCornerShape(13.dp),
                    modifier = Modifier.semantics { selected = true }) {
                    WorkspaceBadge(room.name, C.Ink, C.Sage, Modifier.widthIn(min = 93.5.dp))
                }
            } else {
                WorkspaceButton(room.name, { onAction(MainAction.SelectSetup(room.id)) }, glyph = Glyph.Speaker,
                    modifier = Modifier.widthIn(min = if (room.id == "evening") 190.dp else 150.dp))
            }
        }
        WorkspaceButton("Add setup", { onAction(MainAction.OpenDialog(MainDialog.AddSetup)) }, Modifier.width(148.dp), Glyph.Add, primary = true)
        WorkspaceButton("Edit", { onAction(MainAction.OpenDialog(MainDialog.RenameSetup)) }, Modifier.width(96.dp), Glyph.Edit, enabled = state.selectedSetup != null)
        WorkspaceButton("Delete", { onAction(MainAction.OpenDialog(MainDialog.DeleteSetup)) }, Modifier.width(108.dp), Glyph.Delete, enabled = state.selectedSetup != null && !state.savingProfiles)
    }
}

/** Room title, playback, canvas and tools; handles a session with no remaining rooms.
 * @param state Immutable room, transport and temporary highlight snapshot.
 * @param onAction User intent callback.
 * @param modifier Responsive column sizing.
 * @param canvasHeight Height derived from the window, in dp.
 */
@Composable
private fun RoomColumn(state: MainUiState, onAction: (MainAction) -> Unit,
    modifier: Modifier, canvasHeight: androidx.compose.ui.unit.Dp) {
    val room = state.selectedSetup
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (room == null) {
            Text("Create your first setup", style = MaterialTheme.typography.headlineSmall)
            Text("Add a setup, then choose speakers to arrange your room.")
            WorkspaceButton("Add setup", { onAction(MainAction.OpenDialog(MainDialog.AddSetup)) }, glyph = Glyph.Add, primary = true)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val titleSpace = (maxWidth - 612.dp).coerceAtLeast(0.dp)
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(room.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                    val stereo = room.speakers.size == 2 && room.speakers.map { it.channel }.toSet() ==
                        setOf(com.example.connectallspeakers.model.SpeakerChannel.Left, com.example.connectallspeakers.model.SpeakerChannel.Right)
                    WorkspaceBadge("${if (stereo) "Stereo" else "Layout"} · ${room.speakers.size} speakers", modifier = Modifier.width(145.5.dp))
                    Spacer(Modifier.width(titleSpace))
                    WorkspaceButton(if (state.savingProfiles) "Saving…" else "Save setup", { onAction(MainAction.SaveSetup) }, Modifier.width(164.dp), Glyph.Save, primary = true,
                        enabled = !state.savingProfiles && state.profileStorage != ProfileStorageStatus.LoadFailed)
                }
            }
            AudioSourceControls(state, onAction)
            if (state.appCaptureMode) AppCaptureControls(state, onAction)
            else if (state.liveInputMode) VirtualInputControls(state, onAction)
            else PlaybackControls(state.playback, state.demoMode, onAction)
            CalibrationPanel(state, onAction)
            RoomCanvas(room, state.testingSpeakerId, onAction, Modifier.fillMaxWidth().height(canvasHeight))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton({ onAction(MainAction.ResetPositions) }, contentPadding = PaddingValues(0.dp)) {
                    DesignIcon(Glyph.Reset, C.Muted)
                    Spacer(Modifier.width(16.dp))
                    Text("Reset positions", color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                }
                Text("•", color = C.Muted)
                Text("Use the speaker tray to add outputs to your room.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
