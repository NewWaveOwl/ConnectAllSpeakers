package com.example.connectallspeakers.View.group

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.connectallspeakers.View.main.components.*
import com.example.connectallspeakers.ViewModels.main.*
import com.example.connectallspeakers.model.audio.*
import com.example.connectallspeakers.state.*
import com.example.connectallspeakers.ui.theme.AllConnectColors as C
import kotlin.math.roundToInt

/** Figma Shared Circle: the same session and routes, with selection separate from audio ownership. */
@Composable
internal fun SharedCircleWorkspace(state: MainUiState, onAction: (MainAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 1100.dp) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            CircleCanvas(state, onAction, Modifier.weight(1f))
            CircleInspector(state, onAction, Modifier.width(336.dp))
        } else Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            CircleCanvas(state, onAction, Modifier.fillMaxWidth())
            CircleInspector(state, onAction, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CircleCanvas(state: MainUiState, onAction: (MainAction) -> Unit, modifier: Modifier) {
    Column(modifier.background(C.Deep, RoundedCornerShape(16.dp)).padding(24.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("One shared sound", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            WorkspaceBadge("${state.group.devices.size} devices", C.Ink, C.Sage)
            WorkspaceBadge("Timing not checked", C.Ink, C.Sand)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = maxWidth.value.coerceAtLeast(CircleMinimumWidth)
            val layout = sharedCircleLayout(state.group.devices.size, width)
            val density = layout.density
            Box(Modifier.horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(width.dp).height(CircleHeight.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val rx = width / 2 - if (state.group.devices.size == 3) 162f else 132f
                    drawOval(C.Sand.copy(alpha = .65f), Offset((width/2-rx).dp.toPx(), 65.dp.toPx()),
                        Size((rx*2).dp.toPx(),370.dp.toPx()), style = Stroke(1.dp.toPx()))
                }
                Column(Modifier.align(Alignment.Center).size(density.source.dp).background(C.Plum, CircleShape).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    DesignIcon("\uE405", C.Sand, 32.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(if (state.appCaptureMode) state.selectedCaptureApp?.name ?: "App audio"
                        else if (state.liveInputMode) "Virtual output" else state.playback.source?.name ?: "Choose audio",
                        color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                    Text("One shared mix", color = C.Sand, fontSize = 12.sp)
                }
                state.group.devices.forEachIndexed { index, device ->
                    val selected = state.group.selectedDevice?.output?.endpointId == device.output.endpointId
                    val position = layout.positions[index]
                    Column(Modifier.offset(position.x.dp, position.y.dp).size(density.width.dp, density.height.dp)
                        .selectable(selected, onClick = { onAction(MainAction.Group(GroupSoundAction.SelectDevice(device.output.endpointId))) })
                        .semantics { contentDescription = "${device.output.displayName}, ${if (device.muted) "muted" else "${device.volumePercent} percent"}" },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(Modifier.size(density.avatar.dp).background(if (selected) C.Sage else if (index % 2 == 0) C.Teal else C.Sky, CircleShape), contentAlignment = Alignment.Center) {
                            DesignIcon(deviceGlyph(device), C.Ink, (density.avatar * .54f).dp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text((if (selected) "✓ " else "") + device.output.displayName, color = Color.White, fontSize = density.labelSize.sp,
                            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (device.muted) Text("Muted", color = C.Sand, fontSize = 11.sp)
                    }
                }
                if (state.group.devices.isEmpty()) Text("Add listening devices to build your circle", color = C.Sand,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp))
            }
            }
        }
    }
}

private fun deviceGlyph(device: GroupDevice) = when (device.output.type) {
    AudioOutputType.Speakers, AudioOutputType.DigitalDisplay, AudioOutputType.LineLevel -> Glyph.Speaker
    else -> Glyph.Headphones
}

@Composable
private fun CircleInspector(state: MainUiState, onAction: (MainAction) -> Unit, modifier: Modifier) {
    fun send(action: GroupSoundAction) = onAction(MainAction.Group(action))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val device = state.group.selectedDevice
            if (device == null) Text("Select a listening device", style = MaterialTheme.typography.titleMedium)
            else {
                val id = device.output.endpointId
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DesignIcon(deviceGlyph(device), size = 24.dp)
                    Text(device.output.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text("Selected · Full stereo mix", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                Text("Listening level", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(device.volumePercent.toFloat(), { send(GroupSoundAction.SetVolume(id, it.roundToInt())) }, valueRange = 0f..100f,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Volume for ${device.output.displayName}" },
                        thumb = { Spacer(Modifier.size(0.dp)) }, track = { slider ->
                            Box(Modifier.fillMaxWidth().height(8.dp).background(C.Soft, RoundedCornerShape(4.dp))) {
                                Box(Modifier.fillMaxWidth(slider.value/100f).fillMaxHeight().background(C.Teal, RoundedCornerShape(4.dp)))
                            }
                        })
                    Text("${device.volumePercent}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(36.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WorkspaceButton(if (device.muted) "Unmute" else "Mute", { send(GroupSoundAction.ToggleMute(id)) }, Modifier.weight(1f), "\uE04F")
                    WorkspaceButton(if (state.testingSpeakerId == id) "Stop test" else "Test · 10 s", { send(GroupSoundAction.TestDevice(id)) }, Modifier.weight(1f), Glyph.Play,
                        enabled = state.outputs.devices.any { it.endpointId == id && it.availability == OutputAvailability.Active && it.role == AudioOutputRole.PlaybackTarget })
                }
                WorkspaceButton("Delay · ${device.delayMillis} ms", { send(GroupSoundAction.AdjustTiming) }, Modifier.fillMaxWidth(), "\uE425")
                if (state.group.adjustingTiming) DelayControl(device.delayMillis, false) { send(GroupSoundAction.SetDelay(id, it)) }
                Text("Changes apply only to this device. Select another device in the circle or list.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Devices", style = MaterialTheme.typography.titleSmall)
                Text("${state.group.devices.size} selected", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("Search devices", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            WorkspaceTextField(state.group.deviceSearch, { send(GroupSoundAction.SearchDevices(it)) }, "Search devices", Modifier.fillMaxWidth())
            val filtered = state.group.devices.filter { it.output.displayName.contains(state.group.deviceSearch, ignoreCase = true) }
            Column(Modifier.fillMaxWidth().height(160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (filtered.isEmpty()) Text("No matching devices", color = C.Muted)
                filtered.forEach { device ->
                    val selected = state.group.selectedDevice?.output?.endpointId == device.output.endpointId
                    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).background(if (selected) C.Soft else Color.Transparent, RoundedCornerShape(6.dp))
                        .selectable(selected, onClick = { send(GroupSoundAction.SelectDevice(device.output.endpointId)) }).padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DesignIcon(if (selected) "\uE86C" else deviceGlyph(device), if (selected) C.Plum else C.Teal, 16.dp)
                        Text(device.output.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text(if (device.muted) "Muted" else "${device.volumePercent}%", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("${state.group.devices.size} devices · Scroll to see the full list", color = C.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
