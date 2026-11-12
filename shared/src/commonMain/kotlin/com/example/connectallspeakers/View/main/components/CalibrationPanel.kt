package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.model.audio.OutputTiming
import com.example.connectallspeakers.model.audio.PlaybackPhase
import com.example.connectallspeakers.model.audio.OutputDriftTiming
import com.example.connectallspeakers.state.MainUiState
import com.example.connectallspeakers.ui.theme.AllConnectColors as C
import kotlin.math.round

// Layout settings
private val CalibrationPadding = 16.dp
private val CalibrationGap = 10.dp

/** Manual reference/cue controls and optional selectable engine diagnostics. No acoustic estimate is inferred.
 * @param state Immutable room and playback packets.
 * @param onAction User intents; calibration starts only on an explicit button press.
 */
@Composable
fun CalibrationPanel(state: MainUiState, onAction: (MainAction) -> Unit) {
    val room = state.selectedSetup ?: return
    var expanded by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    var showTimings by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(PanelCorner), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(CalibrationPadding), verticalArrangement = Arrangement.spacedBy(CalibrationGap)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Manual calibration", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton({ showControls = !showControls }) { Text(if (showControls) "Hide controls" else "Configure") }
            }
            if (!showControls) return@Column
            if (state.demoMode) {
                Text("Calibration and engine timing use real Windows outputs. Turn Demo mode off.", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                return@Column
            }
            val reference = room.calibrationReference
            Box {
                OutlinedButton({ expanded = true }, enabled = room.speakers.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Calibration reference speaker" }) {
                    Text("Reference: ${reference?.name?.ifBlank { "Unnamed speaker" } ?: "Add speakers first"}")
                }
                DropdownMenu(expanded, { expanded = false }) {
                    room.speakers.filter { it.output != null }.forEach { speaker ->
                        DropdownMenuItem(text = { Text("${speaker.name.ifBlank { "Unnamed speaker" }} · ${speaker.channel.label}") },
                            onClick = { onAction(MainAction.SelectCalibrationReference(speaker.id)); expanded = false })
                    }
                }
            }
            Text("Choose the slower speaker as reference (0 ms), then delay the faster speaker in its inspector. Changes stop playback. Canvas position does not set delay.",
                style = MaterialTheme.typography.bodySmall, color = C.Muted)
            val busy = state.playback.phase == PlaybackPhase.Loading || state.playback.phase == PlaybackPhase.Starting
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(CalibrationGap)) {
                WorkspaceButton("Play calibration cues", { onAction(MainAction.PlayCalibration) },
                    enabled = room.speakers.size == 2 && !busy && state.playback.phase != PlaybackPhase.Unsupported)
                WorkspaceButton("Stop", { onAction(MainAction.Stop) }, enabled = state.playback.activeEndpointIds.isNotEmpty())
                WorkspaceButton("Reset delays", { onAction(MainAction.ResetDelays) }, enabled = room.speakers.any { it.delayMillis != 0 })
            }
            Text("Cues: 3-second warmup, then 12 matching pulses. Cues/tests use app gain, EQ and manual delay; drift correction is off. Bypass EQ for initial calibration. Engine timing does not measure acoustic/Bluetooth latency.",
                style = MaterialTheme.typography.bodySmall, color = C.Muted)
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CalibrationGap)) {
                Text("Experimental drift correction · file playback", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Switch(room.driftCorrectionEnabled, { onAction(MainAction.SetDriftCorrection(it)) },
                    enabled = !state.liveInputMode && state.playback.phase != PlaybackPhase.Unsupported && (room.speakers.size == 2 || room.driftCorrectionEnabled),
                    modifier = Modifier.semantics { contentDescription = "Experimental two-output clock drift correction" })
            }
            Text("Requires two outputs and the same zero-delay reference. Changes stop playback; press Play to apply. Bounded rate correction may affect sound quality. Hardware/acoustic validation is pending.",
                style = MaterialTheme.typography.bodySmall, color = C.Muted)
            if (state.liveInputMode) Text("Live input uses independent queue/source rate control on each output. This file-only option does not apply.",
                style = MaterialTheme.typography.bodySmall, color = C.Teal)
            TextButton({ showTimings = !showTimings }) { Text(if (showTimings) "Hide engine timing" else "Show engine timing") }
            if (showTimings) SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(CalibrationGap)) {
                    room.speakers.forEach { speaker ->
                        val packet = state.playback.outputTimings.find { it.endpointId == speaker.output?.endpointId }
                        TimingDetails(speaker.name.ifBlank { "Unnamed speaker" }, packet)
                    }
                    if (room.speakers.isEmpty()) Text("Add an output to inspect its timing.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Shows raw clock units separately from source frames and stream latency.
 * @param name Custom card label. @param packet Live/last packet, or null before playback.
 */
@Composable
private fun TimingDetails(name: String, packet: OutputTiming?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.labelLarge)
        if (packet == null) {
            Text("Timing: Unknown — press Play or Play calibration cues.", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            return@Column
        }
        Text(if (packet.isLive) "Live engine measurements" else "Last session / stale measurements", style = MaterialTheme.typography.bodySmall)
        Text("Source/output format rate: ${packet.sampleRate} frames/s · prepared delay: ${packet.delayMillis} ms (${packet.delayFrames} output frames)\n" +
            "Submitted: ${packet.submittedFrames} stream frames · buffer: ${packet.capacityFrames} output frames\n" +
            "Queued: ${packet.queuedFrames ?: "Unknown"} output frames (${packet.queuedMillis.ms()} nominal)\n" +
            "Source position: ${packet.sourcePositionFrames ?: "Unknown"} input frames (${packet.sourcePositionFrames?.let { it * 1000.0 / packet.sampleRate }.ms()})\n" +
            "Engine clock: ${packet.clock?.positionMillis.ms()}${packet.clockMessage?.let { " · $it" } ?: ""}\n" +
            "Clock ticks/frequency: ${packet.clock?.positionTicks ?: "Unknown"} / ${packet.clock?.frequencyTicksPerSecond ?: "Unknown"} units/s\n" +
            "Clock QPC timestamp: ${packet.clock?.qpcPosition100Nanos ?: "Unknown"} (100-ns units)\n" +
            "Windows stream latency: ${packet.streamLatencyMillis.ms()} · underruns: ${packet.underruns}",
            style = MaterialTheme.typography.bodySmall, color = C.Muted)
        DriftDetails(packet.drift)
    }
}

/** @param timing Experimental per-output estimates, or null for disabled/cue/test sessions. */
@Composable
private fun DriftDetails(timing: OutputDriftTiming?) {
    if (timing == null) {
        Text("Drift correction: Off for calibration cues and speaker tests; gain, EQ and manual delay still apply.", style = MaterialTheme.typography.bodySmall, color = C.Muted)
        return
    }
    Text("Experimental drift: ${timing.phase}${if (timing.isReference) " · Reference" else " · Follower"}\n" +
        "Relative clock rate: ${timing.relativeRatePpm.ppm()} · correction: ${timing.correctionPpm.ppm()}\n" +
        "Source skew since baseline: ${timing.skewMillis.ms()} · estimated engine uncertainty: ${timing.uncertaintyMillis.preciseMs()}\n" +
        "Initial engine offset: ${timing.initialEngineOffsetMillis.ms()} (not acoustic latency)\n${timing.message}",
        style = MaterialTheme.typography.bodySmall, color = C.Muted)
}

/** @return One-decimal parts-per-million label, or Unknown. Positive correction advances source faster. */
private fun Double?.ppm(): String = if (this == null || !isFinite()) "Unknown" else "${round(this * 10) / 10} ppm"

/** @return Sub-frame uncertainty with three decimals so supported quantization never rounds to zero. */
private fun Double?.preciseMs(): String = if (this == null || !isFinite()) "Unknown" else "${round(this * 1000) / 1000} ms"

/** @return One-decimal millisecond label, or explicit Unknown for absent/non-finite data. */
private fun Double?.ms(): String = if (this == null || !isFinite()) "Unknown" else "${round(this * 10) / 10} ms"
