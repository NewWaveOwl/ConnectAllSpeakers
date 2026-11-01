package com.example.connectallspeakers.View.main

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import com.example.connectallspeakers.model.RoomSetup
import com.example.connectallspeakers.state.MainDialog
import com.example.connectallspeakers.state.MainUiState
import com.example.connectallspeakers.ui.theme.AllConnectColors as C
import connectallspeakers.shared.generated.resources.Res
import connectallspeakers.shared.generated.resources.allconnect_logo_render
import org.jetbrains.compose.resources.painterResource

// Layout settings
private val ScreenPadding = 24.dp
private val InspectorWidth = 336.dp
private val StackedBreakpoint = 1000.dp
private val MinCanvasHeight = 360.dp
private val WorkspaceChromeHeight = 360.dp

/** Stateless main workspace, using only explicitly labeled sample data.
 * @param state Immutable session snapshot from the screen owner.
 * @param onAction User intent callback.
 * @param modifier Parent sizing.
 */
@Composable
fun MainScreen(state: MainUiState, onAction: (MainAction) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= StackedBreakpoint
        val canvasHeight = (maxHeight - WorkspaceChromeHeight).coerceAtLeast(MinCanvasHeight)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(C.Paper)) {
            WorkspaceHeader()
            SetupToolbar(state, onAction)
            if (wide) {
                Row(Modifier.fillMaxWidth().padding(ScreenPadding), horizontalArrangement = Arrangement.spacedBy(ScreenPadding)) {
                    RoomColumn(state.selectedSetup, state.testingSpeakerId, onAction, Modifier.weight(1f), canvasHeight)
                    SpeakerInspector(state.selectedSpeaker, state.selectedSetup != null, state.demoMode, onAction, Modifier.width(InspectorWidth))
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RoomColumn(state.selectedSetup, state.testingSpeakerId, onAction, Modifier.fillMaxWidth(), canvasHeight)
                    SpeakerInspector(state.selectedSpeaker, state.selectedSetup != null, state.demoMode, onAction, Modifier.fillMaxWidth())
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

/** Renders the exported 44 × 52 logo slot and a keyboard-readable Settings explanation. */
@Composable
private fun WorkspaceHeader() {
    BoxWithConstraints(Modifier.fillMaxWidth().height(76.dp).background(MaterialTheme.colorScheme.surface)) {
        val flexibleSpace = (maxWidth - 740.dp).coerceIn(0.dp, 700.dp)
        Row(Modifier.fillMaxSize().padding(horizontal = ScreenPadding), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp, 52.dp).clip(RoundedCornerShape(10.dp)).background(C.Paper)) {
                Image(painterResource(Res.drawable.allconnect_logo_render), "AllConnect logo",
                    Modifier.size(44.dp, 52.dp))
            }
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = C.Plum)) { append("All") }
                withStyle(SpanStyle(color = C.Teal)) { append("Connect") }
            }, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(flexibleSpace))
            Box(Modifier.semantics(mergeDescendants = true) {
                contentDescription = "Settings unavailable. A settings screen is not included in this prototype."
            }.focusable()) {
                WorkspaceButton("Settings", {}, Modifier.width(124.dp), Glyph.Settings, enabled = false)
            }
        }
    }
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
        WorkspaceButton("Delete", { onAction(MainAction.OpenDialog(MainDialog.DeleteSetup)) }, Modifier.width(108.dp), Glyph.Delete, enabled = state.selectedSetup != null)
    }
}

/** Room title, canvas and tools; handles a session with no remaining rooms.
 * @param room Active room or null.
 * @param testingSpeakerId Temporary highlight identity.
 * @param onAction User intent callback.
 * @param modifier Responsive column sizing.
 * @param canvasHeight Height derived from the window, in dp.
 */
@Composable
private fun RoomColumn(room: RoomSetup?, testingSpeakerId: String?, onAction: (MainAction) -> Unit,
    modifier: Modifier, canvasHeight: androidx.compose.ui.unit.Dp) {
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
                    WorkspaceButton("Save setup", { onAction(MainAction.SaveSetup) }, Modifier.width(164.dp), Glyph.Save, primary = true)
                }
            }
            RoomCanvas(room, testingSpeakerId, onAction, Modifier.fillMaxWidth().height(canvasHeight))
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
