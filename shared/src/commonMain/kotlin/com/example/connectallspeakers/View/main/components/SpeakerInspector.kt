package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.model.RoomSpeaker
import com.example.connectallspeakers.model.audio.OutputAvailability
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.state.MainDialog
import com.example.connectallspeakers.ui.theme.AllConnectColors as C

// Layout settings
private val InspectorHeight = 450.dp
private val TrayHeight = 184.dp
private val PanelPadding = 20.dp

/** Renders room edits independently of OS metadata and offers an explicit demo-mode switch.
 * @param speaker Selected room card, or null for an empty room.
 * @param hasSetup Whether adding a speaker has an active destination.
 * @param demoMode Whether the user explicitly selected the sample workspace.
 * @param onAction Screen intent callback.
 * @param modifier Parent width constraints.
 */
@Composable
fun SpeakerInspector(speaker: RoomSpeaker?, hasSetup: Boolean, demoMode: Boolean, onAction: (MainAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(min = InspectorHeight).background(Color.White, RoundedCornerShape(PanelCorner))
            .padding(start = PanelPadding, top = PanelPadding, end = PanelPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (speaker == null) {
                Text("No speaker selected", style = MaterialTheme.typography.titleSmall)
                Text(if (demoMode) "Add a sample speaker to arrange this room." else "Add a Windows playback output to arrange this room.", color = C.Muted)
            } else {
                Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesignIcon(Glyph.Speaker, if (speaker.channel == SpeakerChannel.Right) C.Sky else C.Teal, 24.dp)
                    Text(speaker.name.ifBlank { "Unnamed speaker" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                WorkspaceBadge(when {
                    speaker.isSample -> "Demo sample"
                    speaker.availability == OutputAvailability.Unavailable -> "Unavailable"
                    speaker.availability == OutputAvailability.Unknown -> "Availability unknown"
                    else -> "Available output"
                }, color = if (speaker.availability == OutputAvailability.Unavailable) MaterialTheme.colorScheme.error else C.Plum)
                Column(Modifier.height(76.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InspectorLabel("Channel assignment")
                    ChannelPicker(speaker.channel) { onAction(MainAction.AssignChannel(it)) }
                }
                Column(Modifier.heightIn(min = 76.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InspectorLabel("Speaker name")
                    WorkspaceTextField(speaker.name, { onAction(MainAction.RenameSpeaker(it)) }, "Speaker name")
                    if (speaker.name.isBlank()) Text("Enter a speaker name.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                InspectorLabel("App volume")
                VolumeSlider(speaker.volume, { onAction(MainAction.SetVolume(it)) })
                WorkspaceButton("Test speaker", { onAction(MainAction.TestSpeaker) }, Modifier.fillMaxWidth(), Glyph.Play)
                Text("Test plays a one-second tone at app volume.\nCanvas position does not change audio timing.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                speaker.output?.let { output ->
                    Text("Windows: ${output.displayName}\n${output.type.label()}${if (output.isDefault) " · Default output" else ""}",
                        color = C.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(min = TrayHeight).background(Color.White, RoundedCornerShape(PanelCorner)).padding(PanelPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Speaker tray", style = MaterialTheme.typography.titleMedium)
            Text(if (demoMode) "Add a sample to this room layout." else "Choose an available Windows output.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            WorkspaceButton("Add speaker", { onAction(MainAction.OpenDialog(MainDialog.AddSpeaker)) }, Modifier.fillMaxWidth(), Glyph.Add, enabled = hasSetup)
            Text(if (demoMode) "Demo — sample speakers" else "Local WAV playback · up to two outputs", color = C.Muted, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Demo mode", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(demoMode, { onAction(MainAction.SetDemoMode(it)) },
                    modifier = Modifier.semantics { contentDescription = "Demo mode with sample speakers" })
            }
        }
    }
}

/** Renders a muted inspector label.
 * @param text Visible label.
 */
@Composable
private fun InspectorLabel(text: String) = Text(text, color = C.Muted, style = MaterialTheme.typography.labelMedium)

/** Native dropdown for deliberate channel changes.
 * @param channel Current channel.
 * @param onChannel Callback invoked after choosing an option.
 */
@Composable
private fun ChannelPicker(channel: SpeakerChannel, onChannel: (SpeakerChannel) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { expanded = true }, shape = RoundedCornerShape(8.dp), color = C.Paper,
            modifier = Modifier.fillMaxWidth().height(44.dp).semantics { contentDescription = "Channel assignment: ${channel.label}" }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(channel.label, Modifier.weight(1f))
                DesignIcon(Glyph.Expand, C.Muted, 18.dp)
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            SpeakerChannel.entries.forEach { item -> DropdownMenuItem(
                text = { Text(item.label) }, onClick = { onChannel(item); expanded = false },
            ) }
        }
    }
}
