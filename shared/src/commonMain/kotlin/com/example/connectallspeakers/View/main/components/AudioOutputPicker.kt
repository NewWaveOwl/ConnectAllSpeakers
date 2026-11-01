package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.data.audio.AudioOutputStatus
import com.example.connectallspeakers.model.audio.AudioOutputType
import com.example.connectallspeakers.state.MainUiState
import com.example.connectallspeakers.ui.theme.AllConnectColors as C

// Layout settings
private val OutputListMaxHeight = 360.dp
private val OutputRowGap = 10.dp

/** Real endpoint picker: stale results stay visible but cannot be added until a successful refresh.
 * @param state Immutable discovery and current room state.
 * @param onAction Explicit select/refresh intents.
 */
@Composable
fun AudioOutputPicker(state: MainUiState, onAction: (MainAction) -> Unit) {
    val snapshot = state.outputs
    Column(verticalArrangement = Arrangement.spacedBy(OutputRowGap)) {
        Text("Windows playback outputs", style = MaterialTheme.typography.titleMedium)
        Text("Present and enabled outputs only. Microphones are excluded. Playback is not implemented.", style = MaterialTheme.typography.bodySmall)
        WorkspaceButton("Refresh outputs", { onAction(MainAction.RefreshOutputs) }, enabled = snapshot.status != AudioOutputStatus.Loading)
        when (snapshot.status) {
            AudioOutputStatus.Loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Reading Windows outputs…")
            }
            AudioOutputStatus.Error -> Text(snapshot.message ?: "Could not read Windows outputs. Refresh to retry.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            AudioOutputStatus.Unsupported -> Text(snapshot.message ?: "Windows discovery is unavailable.", style = MaterialTheme.typography.bodySmall)
            AudioOutputStatus.Ready -> if (snapshot.devices.isEmpty()) Text("No active playback outputs. Connect or enable an output in Windows, then refresh.")
        }
        if (snapshot.devices.isNotEmpty()) {
            if (snapshot.status != AudioOutputStatus.Ready) Text("Last-known outputs — refresh must succeed before adding.", style = MaterialTheme.typography.bodySmall)
            Column(Modifier.heightIn(max = OutputListMaxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(OutputRowGap)) {
                snapshot.devices.forEach { output ->
                    val assigned = state.selectedSetup?.speakers?.any { it.output?.endpointId == output.endpointId } == true
                    Surface(onClick = { onAction(MainAction.AddOutput(output.endpointId)) },
                        enabled = snapshot.status == AudioOutputStatus.Ready,
                        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, C.Line),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${output.displayName}. Endpoint ${output.endpointId}" }) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(output.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(output.type.label(), if (output.isDefault) "Default playback output" else null,
                                if (assigned) "Already in this room — select" else null).joinToString(" · "),
                                color = C.Teal, style = MaterialTheme.typography.bodySmall)
                            Text(output.endpointId, color = C.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** User-facing labels from reliable form-factor metadata, without guessing transport.
 * @return Readable form factor or an explicit unknown value.
 */
fun AudioOutputType.label(): String = when (this) {
    AudioOutputType.Speakers -> "Speakers"; AudioOutputType.Headphones -> "Headphones"
    AudioOutputType.Headset -> "Headset"; AudioOutputType.Handset -> "Handset"
    AudioOutputType.LineLevel -> "Line output"; AudioOutputType.DigitalDisplay -> "HDMI / DisplayPort"
    AudioOutputType.Spdif -> "S/PDIF"; AudioOutputType.RemoteNetwork -> "Network output"
    AudioOutputType.DigitalPassthrough -> "Digital passthrough"; AudioOutputType.Unknown -> "Unknown device type"
}
