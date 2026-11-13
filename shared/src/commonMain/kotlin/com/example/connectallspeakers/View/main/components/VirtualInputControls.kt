package com.example.connectallspeakers.View.main.components

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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.data.audio.AudioOutputStatus
import com.example.connectallspeakers.model.audio.*
import com.example.connectallspeakers.state.MainUiState
import com.example.connectallspeakers.ui.theme.AllConnectColors as C

// Layout settings
private val LivePanelPadding = 16.dp
private val LivePanelGap = 8.dp
private val LiveInputListHeight = 160.dp

/** Explicit session-only source choice. Switching requests Stop; composition never opens native resources.
 * @param state Immutable source selection. @param onAction User intents.
 */
@Composable
fun AudioSourceControls(state: MainUiState, onAction: (MainAction) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LivePanelGap)) {
        Text("Audio source", style = MaterialTheme.typography.labelLarge)
        val colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.Sage, selectedLabelColor = C.Ink, labelColor = C.Muted)
        FilterChip(selected = !state.liveInputMode, onClick = { onAction(MainAction.SetLiveInputMode(false)) }, label = { Text("Local file") }, colors = colors)
        FilterChip(selected = state.liveInputMode && !state.appCaptureMode, onClick = { onAction(MainAction.SetLiveInputMode(true)) }, label = { Text("AllConnect virtual output · experimental") }, colors = colors)
        FilterChip(selected = state.appCaptureMode, onClick = { onAction(MainAction.SelectAppCaptureMode) }, label = { Text("App audio · experimental") }, colors = colors)
    }
}

/** Exact-owned-input selector and bounded live transport. A missing driver never falls back to another output.
 * @param state Immutable discovery, selected input and engine status. @param onAction Explicit user intents.
 */
@Composable
fun VirtualInputControls(state: MainUiState, onAction: (MainAction) -> Unit) {
    val discovery = state.outputs
    val inputs = discovery.devices.filter { it.role == AudioOutputRole.AllConnectInput }
    val ready = discovery.status == AudioOutputStatus.Ready
    val playback = state.playback
    val active = playback.liveInput != null || playback.phase == PlaybackPhase.Starting || playback.phase == PlaybackPhase.Playing
    val selected = inputs.find { it.endpointId == state.virtualInputId && it.availability == OutputAvailability.Active }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(PanelCorner))
        .padding(LivePanelPadding), verticalArrangement = Arrangement.spacedBy(LivePanelGap)) {
        Text("AllConnect virtual output · experimental", style = MaterialTheme.typography.titleSmall)
        Text("Select AllConnect Speakers as the audio output in another app, then press Start forwarding here. AllConnect sends that audio to your selected physical speakers or headphones. 48 kHz stereo · 1–2 destinations.",
            style = MaterialTheme.typography.bodySmall)
        when {
            state.demoMode -> Text("Demo speakers cannot forward audio. Switch Demo mode off.")
            !state.virtualInputSupported -> Text("AllConnect virtual output is unavailable on this platform.")
            discovery.status == AudioOutputStatus.Loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Reading Windows outputs…") }
            !ready -> Text(discovery.message ?: "Refresh outputs before forwarding audio.", color = MaterialTheme.colorScheme.error)
            inputs.isEmpty() -> Text("AllConnect virtual driver not detected. Build and install the separate prototype driver in your test environment, then refresh.",
                style = MaterialTheme.typography.bodySmall)
        }
        Column(Modifier.heightIn(max = LiveInputListHeight).verticalScroll(rememberScrollState())) {
            inputs.forEach { input ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = input.endpointId == state.virtualInputId,
                        onClick = { onAction(MainAction.SelectVirtualInput(input.endpointId)) }, enabled = ready && !state.demoMode,
                        modifier = Modifier.semantics { contentDescription = "Virtual output ${input.displayName}. Endpoint ${input.endpointId}" })
                    Column(Modifier.weight(1f)) {
                        Text(input.displayName, style = MaterialTheme.typography.bodyMedium)
                        Text(input.endpointId, style = MaterialTheme.typography.bodySmall, color = C.Muted)
                    }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(LivePanelGap)) {
            WorkspaceButton("Refresh outputs", { onAction(MainAction.RefreshOutputs) }, enabled = discovery.status != AudioOutputStatus.Loading)
            WorkspaceButton("Start forwarding", { onAction(MainAction.StartVirtualInput) }, primary = true,
                enabled = ready && selected != null && state.virtualInputSupported && !state.demoMode && !active)
            WorkspaceButton("Stop", { onAction(MainAction.Stop) }, enabled = active)
        }
        Text(playback.liveInput?.let { "${it.phase} · ${it.bufferedFrames} buffered frames · ${it.inputFrames} input frames" }
            ?: when {
                playback.isTestTone && active -> "Speaker test · live capture stopped"
                playback.isCalibration && active -> "Calibration cues · live capture stopped"
                playback.phase == PlaybackPhase.Starting -> "Starting audio…"
                else -> "Stopped · capture starts only when you press Start."
            }, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (playback.phase == PlaybackPhase.Error || playback.liveInput != null || active) Text(playback.message.orEmpty(),
            color = if (playback.phase == PlaybackPhase.Error) MaterialTheme.colorScheme.error else C.Teal,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        playback.routeUpdateError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text("Stop, source changes and device loss discard live audio. Pause/recording and automatic restart are unavailable. Rate control is experimental; acoustic alignment is unverified.",
            style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}
