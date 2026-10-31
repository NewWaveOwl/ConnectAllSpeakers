package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.model.SampleSpeaker
import com.example.connectallspeakers.model.SpeakerChannel
import com.example.connectallspeakers.state.MainDialog
import com.example.connectallspeakers.ui.theme.AllConnectColors as C

// Layout settings
private val InspectorHeight = 450.dp
private val TrayHeight = 184.dp
private val PanelPadding = 20.dp

/** Renders the selected sample and sample picker entry point.
 * @param speaker Selected sample, or null for an empty room.
 * @param hasSetup Whether adding a sample has an active destination.
 * @param onAction Screen intent callback.
 * @param modifier Parent width constraints.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SpeakerInspector(speaker: SampleSpeaker?, hasSetup: Boolean, onAction: (MainAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(min = InspectorHeight).background(Color.White, RoundedCornerShape(PanelCorner))
            .padding(start = PanelPadding, top = PanelPadding, end = PanelPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (speaker == null) {
                Text("No speaker selected", style = MaterialTheme.typography.titleSmall)
                Text("Add a sample speaker to arrange this room.", color = C.Muted)
            } else {
                Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DesignIcon(Glyph.Speaker, if (speaker.channel == SpeakerChannel.Right) C.Sky else C.Teal, 24.dp)
                    Text(speaker.name.ifBlank { "Unnamed sample" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                WorkspaceBadge("Selected speaker", color = C.Plum, modifier = Modifier.width(126.dp))
                Column(Modifier.height(76.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InspectorLabel("Channel assignment")
                    ChannelPicker(speaker.channel) { onAction(MainAction.AssignChannel(it)) }
                }
                Column(Modifier.heightIn(min = 76.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InspectorLabel("Speaker name")
                    WorkspaceTextField(speaker.name, { onAction(MainAction.RenameSpeaker(it)) }, "Speaker name")
                    if (speaker.name.isBlank()) Text("Enter a speaker name.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                InspectorLabel("Volume")
                Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Slider(speaker.volume / 100f, { onAction(MainAction.SetVolume((it * 100).toInt())) },
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Sample speaker volume" },
                        thumb = { Box(Modifier.size(10.dp).background(C.Sky, CircleShape)) },
                        track = { slider ->
                            Box(Modifier.fillMaxWidth().height(5.dp).background(C.Soft, RoundedCornerShape(3.dp))) {
                                Box(Modifier.fillMaxWidth(slider.value).height(5.dp).background(C.Sky, RoundedCornerShape(3.dp)))
                            }
                        },
                        colors = SliderDefaults.colors(thumbColor = C.Sky, activeTrackColor = C.Sky, inactiveTrackColor = C.Soft))
                    Text("${speaker.volume}%", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium)
                }
                WorkspaceButton("Test speaker", { onAction(MainAction.TestSpeaker) }, Modifier.fillMaxWidth(), Glyph.Play)
                Text("Drag the speaker on the canvas\nto change its position.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(min = TrayHeight).background(Color.White, RoundedCornerShape(PanelCorner)).padding(PanelPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Speaker tray", style = MaterialTheme.typography.titleMedium)
            Text("Add a sample to this room layout.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            WorkspaceButton("Add speaker", { onAction(MainAction.OpenDialog(MainDialog.AddSpeaker)) }, Modifier.fillMaxWidth(), Glyph.Add, enabled = hasSetup)
            Text("Demo — sample speakers", color = C.Muted, style = MaterialTheme.typography.labelSmall)
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
