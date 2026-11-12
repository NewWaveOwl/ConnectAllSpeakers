package com.example.connectallspeakers.View.group

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.View.main.WorkspaceHeader
import com.example.connectallspeakers.View.main.components.*
import com.example.connectallspeakers.ViewModels.main.*
import com.example.connectallspeakers.data.audio.AudioOutputStatus
import com.example.connectallspeakers.model.audio.*
import com.example.connectallspeakers.state.*
import com.example.connectallspeakers.ui.theme.AllConnectColors as C
import kotlin.math.roundToInt

// Layout settings — Figma 53:1758, GS 01 / Listening lounge.
private val WorkspacePadding = 24.dp
private val PanelGap = 18.dp
private val WideBreakpoint = 1100.dp
private val DeviceRowBreakpoint = 720.dp
private val GroupCorner = 16.dp

/** Stateless Listening lounge; one source and one engine serve transient physical-device membership.
 * @param state Immutable application/group session. @param onAction User intent, never native work in composition.
 * @param modifier Parent sizing. Body scrolls independently of the persistent transport.
 */
@Composable
fun GroupSoundScreen(state: MainUiState, onAction: (MainAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(C.Paper)) {
        WorkspaceHeader(state, onAction)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= WideBreakpoint
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(WorkspacePadding),
                verticalArrangement = Arrangement.spacedBy(PanelGap)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Group Sound View", style = MaterialTheme.typography.headlineSmall)
                        Text("One source. Full sound for everyone.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    WorkspaceButton("Manage devices", { onAction(MainAction.Group(GroupSoundAction.ManageDevices)) }, glyph = Glyph.Headphones)
                }
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(900f), verticalArrangement = Arrangement.spacedBy(PanelGap)) {
                        SharedSource(state, onAction)
                        ListeningDevices(state, onAction)
                    }
                    Column(Modifier.weight(468f), verticalArrangement = Arrangement.spacedBy(PanelGap)) { Guidance(state, onAction) }
                } else Column(verticalArrangement = Arrangement.spacedBy(PanelGap)) {
                    SharedSource(state, onAction)
                    ListeningDevices(state, onAction)
                    Guidance(state, onAction)
                }
                state.feedback?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton({ onAction(MainAction.DismissFeedback) }) { Text("Dismiss") }
                }
            }
        }
        GroupTransport(state, onAction)
    }
}

/** One shared source; additional source selection controls are shown only on explicit request. */
@Composable
private fun SharedSource(state: MainUiState, onAction: (MainAction) -> Unit) {
    GroupPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DesignIcon(if (state.liveInputMode) "\uE890" else "\uE405", size = 24.dp)
            Spacer(Modifier.width(16.dp))
            Text(if (state.appCaptureMode) "App audio" else if (state.liveInputMode) "Virtual input" else "Local file", color = C.Muted)
            Spacer(Modifier.weight(1f))
            WorkspaceBadge("One shared source", C.Ink)
        }
        Text(when {
            state.appCaptureMode -> state.selectedCaptureApp?.name ?: "Choose an app"
            state.liveInputMode -> state.outputs.devices.find { it.endpointId == state.virtualInputId }?.displayName ?: "Choose AllConnect input"
            else -> state.playback.source?.name ?: "Choose audio to share"
        }, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(if (state.liveInputMode) "Live audio · Stereo · Starts only when you press Start"
            else state.playback.source?.let { "${it.durationMillis / 60000}:${(it.durationMillis / 1000 % 60).toString().padStart(2, '0')} · ${if (it.channels == 2) "Stereo" else "Mono source"} · ${state.playback.phase}" }
                ?: "WAV / MP3 · No audio selected", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        if (state.group.choosingSource || (!state.liveInputMode && state.playback.source == null)) {
            AudioSourceControls(state, onAction)
            if (state.appCaptureMode) AppCaptureControls(state, onAction)
            else if (state.liveInputMode) VirtualInputControls(state, onAction)
            else WorkspaceButton("Choose audio", { onAction(MainAction.ChooseAudioFile) }, glyph = "\uE405")
        }
    }
}

/** Physical endpoint membership, with unavailable selections retained and a two-device limit. */
@Composable
private fun ListeningDevices(state: MainUiState, onAction: (MainAction) -> Unit) {
    GroupPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Listening devices", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            WorkspaceBadge("${state.group.devices.size} selected", C.Ink)
        }
        if (state.group.devices.isEmpty()) Text("Choose one or two headphones or speakers connected to this PC.", color = C.Muted)
        state.group.devices.forEachIndexed { index, device -> GroupDeviceRow(device, index, state, onAction) }
        if (state.group.managingDevices) {
            Text("Available Windows outputs", style = MaterialTheme.typography.titleSmall)
            WorkspaceButton("Refresh outputs", { onAction(MainAction.RefreshOutputs) }, enabled = state.outputs.status != AudioOutputStatus.Loading)
            if (state.outputs.status == AudioOutputStatus.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.outputs.status != AudioOutputStatus.Ready) Text(state.outputs.message ?: "Waiting for Windows discovery.", color = C.Muted)
            val available = state.outputs.devices.filter { it.role == AudioOutputRole.PlaybackTarget && it.availability == OutputAvailability.Active }
            if (available.isEmpty() && state.outputs.status == AudioOutputStatus.Ready) Text("No available physical outputs. Connect devices in Windows, then refresh.")
            available.forEach { output ->
                val selected = state.group.devices.any { it.output.endpointId == output.endpointId }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(output.displayName)
                        Text(output.endpointId, style = MaterialTheme.typography.bodySmall, color = C.Muted)
                    }
                    WorkspaceButton(if (selected) "Remove" else "Add", {
                        onAction(MainAction.Group(if (selected) GroupSoundAction.RemoveDevice(output.endpointId) else GroupSoundAction.AddDevice(output.endpointId)))
                    }, enabled = state.outputs.status == AudioOutputStatus.Ready && (selected || state.group.devices.size < MAX_PLAYBACK_OUTPUTS))
                }
            }
            state.group.devices.filter { it.output.availability != OutputAvailability.Active }.forEach { missing ->
                WorkspaceButton("Remove ${missing.output.displayName}", { onAction(MainAction.Group(GroupSoundAction.RemoveDevice(missing.output.endpointId))) })
            }
        }
    }
}

/** One device's independently adjustable level, mute and ten-second test; names never identify routes. */
@Composable
private fun GroupDeviceRow(device: GroupDevice, index: Int, state: MainUiState, onAction: (MainAction) -> Unit) {
    val endpointId = device.output.endpointId
    val available = state.outputs.status == AudioOutputStatus.Ready && device.output.availability == OutputAvailability.Active && device.output.role == AudioOutputRole.PlaybackTarget
    val testing = state.testingSpeakerId == endpointId
    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 108.dp), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, C.Line), color = Color.White) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val wide = maxWidth >= DeviceRowBreakpoint
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(52.dp).background(if (index == 0) C.Sage else C.Sky, CircleShape), contentAlignment = Alignment.Center) {
                        DesignIcon(Glyph.Headphones, C.Ink, 28.dp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(device.output.displayName, style = MaterialTheme.typography.labelLarge)
                        Text("Full stereo mix · ${if (available) "Ready" else "Unavailable"}${if (device.muted) " · Muted" else ""}", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                    }
                    if (wide) DeviceActions(device, testing, available, if (index == 0) C.Teal else C.Sky, onAction, Modifier.width(416.dp))
                }
                if (!wide) DeviceActions(device, testing, available, if (index == 0) C.Teal else C.Sky, onAction, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Design's compact volume rail with native keyboard/range semantics and independent mute/test actions. */
@Composable
private fun DeviceActions(device: GroupDevice, testing: Boolean, available: Boolean, accent: Color, onAction: (MainAction) -> Unit, modifier: Modifier) {
    val id = device.output.endpointId
    Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.width(176.dp), verticalAlignment = Alignment.CenterVertically) {
            Slider(device.volumePercent.toFloat(), { onAction(MainAction.Group(GroupSoundAction.SetVolume(id, it.roundToInt()))) },
                modifier = Modifier.weight(1f).semantics { contentDescription = "Volume for ${device.output.displayName}" }, valueRange = 0f..100f,
                thumb = { Spacer(Modifier.size(0.dp)) }, track = { slider ->
                    Box(Modifier.fillMaxWidth().height(8.dp).background(C.Soft, RoundedCornerShape(4.dp))) {
                        Box(Modifier.fillMaxWidth(slider.value / 100f).fillMaxHeight().background(accent, RoundedCornerShape(4.dp)))
                    }
                })
            Text("${device.volumePercent}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(38.dp))
        }
        WorkspaceButton(if (device.muted) "Unmute" else "Mute", { onAction(MainAction.Group(GroupSoundAction.ToggleMute(id))) }, glyph = "\uE04F")
        WorkspaceButton(if (testing) "Stop test" else "Test · 10 s", { onAction(MainAction.Group(GroupSoundAction.TestDevice(id))) },
            glyph = if (testing) Glyph.Pause else Glyph.Play, enabled = available)
    }
}

/** Timing guidance stays explicit about the absence of acoustic acceptance. */
@Composable
private fun Guidance(state: MainUiState, onAction: (MainAction) -> Unit) {
    GroupPanel {
        Text("Listen together", style = MaterialTheme.typography.titleLarge)
        Text("Both devices hear the full mix. Each keeps its own listening level.", color = C.Muted)
        WorkspaceBadge("Timing not checked", C.Ink, C.Sand)
        Text("If one device sounds later, adjust its delay before listening together.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        WorkspaceButton("Adjust timing", { onAction(MainAction.Group(GroupSoundAction.AdjustTiming)) }, Modifier.fillMaxWidth(), "\uE425")
        if (state.group.adjustingTiming) state.group.devices.forEach { device ->
            Text(device.output.displayName, style = MaterialTheme.typography.labelLarge)
            DelayControl(device.delayMillis, false) { onAction(MainAction.Group(GroupSoundAction.SetDelay(device.output.endpointId, it))) }
        }
    }
    GroupPanel(C.Soft) {
        Text("Your level, your choice", style = MaterialTheme.typography.titleMedium)
        Text("Adjust a device without changing what everyone else hears. Muting keeps it in the group.", color = C.Muted)
        if (state.appCaptureMode) Text("The app's original output keeps playing. Capture does not mute or reroute it.", color = C.Muted)
    }
}

/** Bottom transport distinguishes finite file pause/resume from live Start/Stop. */
@Composable
private fun GroupTransport(state: MainUiState, onAction: (MainAction) -> Unit) {
    val playback = state.playback
    val running = playback.phase == PlaybackPhase.Playing
    val busy = playback.phase in listOf(PlaybackPhase.Starting, PlaybackPhase.Loading)
    val paused = playback.phase == PlaybackPhase.Paused
    val routeError = state.group.routeError(state.outputs)
    val sourceReady = if (state.appCaptureMode) state.appCaptureSupported && state.selectedCaptureApp != null && !state.captureAppsLoading && state.captureAppsError == null
        else if (state.liveInputMode) state.virtualInputSupported && state.outputs.devices.any { it.endpointId == state.virtualInputId && it.role == AudioOutputRole.AllConnectInput }
        else playback.source != null && !playback.isTestTone && !playback.isCalibration
    BoxWithConstraints(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 24.dp, vertical = 16.dp)) {
        val wide = maxWidth >= WideBreakpoint
        val statusContent: @Composable () -> Unit = {
Text("${if (playback.isTestTone && running) "Testing device" else playback.liveInput?.phase?.name ?: if (running || paused || busy || playback.phase == PlaybackPhase.Error) playback.phase.name else if (routeError == null && sourceReady) "Ready" else "Choose source and devices"} · ${state.group.devices.size} devices selected",
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(playback.routeUpdateError ?: if (playback.phase == PlaybackPhase.Error) playback.message.orEmpty()
            else "The full mix goes to both. Timing has not been measured.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
        val actionsContent: @Composable () -> Unit = {
Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WorkspaceButton("Change source", { onAction(MainAction.Group(GroupSoundAction.ChooseSource)) }, glyph = "\uE890")
            WorkspaceButton(if (state.liveInputMode) "Start for everyone" else if (paused) "Resume for everyone" else "Play for everyone", {
                onAction(if (state.appCaptureMode) MainAction.StartAppCapture else if (state.liveInputMode) MainAction.StartVirtualInput else MainAction.Play)
            }, glyph = Glyph.Play, primary = true, enabled = routeError == null && sourceReady && !running && !busy && playback.phase != PlaybackPhase.Unsupported)
            if (!state.liveInputMode) WorkspaceButton("Pause", { onAction(MainAction.Pause) }, enabled = running && !playback.isTestTone && !playback.isCalibration)
            WorkspaceButton("Stop", { onAction(MainAction.Stop) }, enabled = running || paused || busy)
        }
        }
        if (wide) Row(Modifier.fillMaxWidth().heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { statusContent() }
            actionsContent()
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            statusContent()
            actionsContent()
        }
    }
}

/** Shared Figma panel treatment. @param color Fill. @param content Stateless panel contents. */
@Composable
private fun GroupPanel(color: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(color, RoundedCornerShape(GroupCorner)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}
