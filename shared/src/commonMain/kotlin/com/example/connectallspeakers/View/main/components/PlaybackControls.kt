package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.model.audio.PlaybackPhase
import com.example.connectallspeakers.model.audio.PlaybackSnapshot
import com.example.connectallspeakers.ui.theme.AllConnectColors as C

// Layout settings
private val TransportPadding = 16.dp
private val TransportGap = 12.dp

/** Stateless file transport. No file or audio API is called from composition.
 * @param playback Immutable platform lifecycle and source metadata.
 * @param demoMode Samples cannot start native playback.
 * @param onAction Explicit transport intent callback.
 * @param modifier Parent sizing and padding.
 */
@Composable
fun PlaybackControls(playback: PlaybackSnapshot, demoMode: Boolean, onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier) {
    val supported = playback.phase != PlaybackPhase.Unsupported && !demoMode
    val busy = playback.phase == PlaybackPhase.Loading || playback.phase == PlaybackPhase.Starting
    val running = playback.phase == PlaybackPhase.Playing
    val paused = playback.phase == PlaybackPhase.Paused
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(PanelCorner))
        .padding(TransportPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("WAV / MP3 playback · experimental", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(TransportGap)) {
            WorkspaceButton("Choose audio", { onAction(MainAction.ChooseAudioFile) }, enabled = supported && !busy)
            WorkspaceButton(if (paused && !playback.isTestTone) "Resume" else "Play", { onAction(MainAction.Play) },
                glyph = Glyph.Play, primary = true, enabled = supported && !busy && !running && playback.source != null && !playback.isTestTone)
            WorkspaceButton("Pause", { onAction(MainAction.Pause) }, enabled = supported && running && !playback.isTestTone)
            WorkspaceButton("Stop", { onAction(MainAction.Stop) }, enabled = supported && (running || paused || busy))
        }
        playback.source?.let { source ->
            Text("${source.name} · ${timeLabel(playback.positionMillis)} / ${timeLabel(source.durationMillis)} · ${source.sampleRate} Hz",
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(if (demoMode) "Demo speakers cannot play audio. Switch Demo mode off to use Windows outputs."
            else playback.message.orEmpty(), color = if (playback.phase == PlaybackPhase.Error) MaterialTheme.colorScheme.error else C.Teal,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text("1–2 outputs · Left/Right isolates that source channel; Mono mixes both · Timing is not calibrated",
            color = C.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** @param millis Source position/duration in milliseconds. @return Minutes and zero-padded seconds. */
private fun timeLabel(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
