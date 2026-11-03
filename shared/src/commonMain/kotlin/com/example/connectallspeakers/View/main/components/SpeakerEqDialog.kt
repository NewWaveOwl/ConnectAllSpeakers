package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.model.audio.*
import com.example.connectallspeakers.state.MainUiState
import com.example.connectallspeakers.ui.theme.AllConnectColors as C
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.roundToInt

// Layout settings
private val EqContentMaximumHeight = 500.dp
private val EqControlHeight = 48.dp
private val EqRailHeight = 18.dp
private val EqRailCorner = 3.dp
private val EqFocusStroke = 2.dp
private val EqBandSpacing = 12.dp

/** Stateless, scrollable per-speaker EQ controls; all edits flow through validation in the owner.
 * @param state Selected card, actual source rate and copied runtime guard feedback.
 * @param onAction Immediate selected-card intent callback; Close retains edits, Save setup makes them durable.
 */
@Composable
fun SpeakerEqDialog(state: MainUiState, onAction: (MainAction) -> Unit) {
    val speaker = state.selectedSpeaker ?: return
    val eq = speaker.eq
    val timing = state.playback.outputTimings.find { it.endpointId == speaker.output?.endpointId && it.isLive }?.eq
    AlertDialog(onDismissRequest = { onAction(MainAction.DismissDialog) },
        title = { Text("EQ · ${speaker.name.ifBlank { "Unnamed speaker" }}", maxLines = 2) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = EqContentMaximumHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(EqBandSpacing)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable EQ", Modifier.weight(1f))
                    Switch(eq.enabled, { onAction(MainAction.SetEqEnabled(it)) },
                        modifier = Modifier.semantics { contentDescription = "Enable EQ for ${speaker.name}" })
                }
                Text("Three bell bands · changes blend over 100 ms. Bypass keeps your band settings. Save setup to retain edits after reopening.",
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
                if (speaker.isSample) Text("Demo sample — silent; these controls do not play audio.", color = C.Plum, style = MaterialTheme.typography.bodySmall)
                state.playback.source?.sampleRate?.let { rate ->
                    Text("Processing source: $rate Hz · maximum center ${maximumEqFrequency(rate).toInt()} Hz.", color = C.Muted,
                        style = MaterialTheme.typography.bodySmall)
                } ?: Text("Frequency limits are checked again when a source is loaded/played.", color = C.Muted, style = MaterialTheme.typography.bodySmall)
                state.dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.playback.routeUpdateError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Headroom (target): −${eq.headroomDb} dB. Boosts lower the overall level to reserve gain. A final safety clamp prevents overflow.",
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
                timing?.let {
                    Text("Safety clamp: ${it.limitedFrames} frames${if (it.transitioning) " · blending" else ""}.",
                        color = if (it.limitedFrames > 0) MaterialTheme.colorScheme.error else C.Muted, style = MaterialTheme.typography.bodySmall)
                    it.message?.let { message -> Text(message, color = C.Muted, style = MaterialTheme.typography.bodySmall) }
                }
                for (index in 0 until EQ_BAND_COUNT) {
                    val band = eq.band(index)
                    HorizontalDivider()
                    Text("Band ${index + 1} · bell", style = MaterialTheme.typography.titleSmall)
                    fun change(frequency: Double = band.frequencyHz, gain: Double = band.gainDb, q: Double = band.q) {
                        onAction(MainAction.SetEqBand(index, frequency, gain, q))
                    }
                    EqSlider("Band ${index + 1} gain", "${band.gainDb} dB", band.gainDb.toFloat(), -12f..12f, 47) {
                        change(gain = round(it * 2.0) / 2)
                    }
                    val logMinimum = ln(EQ_MIN_FREQUENCY_HZ)
                    val logMaximum = ln(EQ_MAX_FREQUENCY_HZ)
                    EqSlider("Band ${index + 1} frequency", "${band.frequencyHz.roundToInt()} Hz", ln(band.frequencyHz).toFloat(),
                        logMinimum.toFloat()..logMaximum.toFloat()) {
                        change(frequency = round(exp(it.toDouble())).coerceIn(EQ_MIN_FREQUENCY_HZ, EQ_MAX_FREQUENCY_HZ))
                    }
                    EqSlider("Band ${index + 1} Q", band.q.toString(), band.q.toFloat(), .3f..2f, 16) {
                        change(q = (round(it * 10.0) / 10).coerceIn(EQ_MIN_Q, EQ_MAX_Q))
                    }
                }
            }
        },
        confirmButton = { TextButton({ onAction(MainAction.DismissDialog) }) { Text("Close") } },
        dismissButton = { TextButton({ onAction(MainAction.ResetEq) }) { Text("Reset EQ") } },
    )
}

/** Native focus/keyboard/range behavior with the established thumb-free slider rail.
 * @param label Accessible parameter label, including band identity.
 * @param valueLabel Readable value and units.
 * @param value Current slider-space value (log Hz for frequency).
 * @param range Bounded slider-space range.
 * @param steps Interior native steps; zero allows continuous frequency gestures.
 * @param onChange Owner callback; never performs DSP or storage in rendering.
 */
@Composable
private fun EqSlider(label: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int = 0, onChange: (Float) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(EqRailCorner)
    Column {
        Text("$label · $valueLabel", style = MaterialTheme.typography.bodySmall)
        Slider(value.coerceIn(range), onChange, valueRange = range, steps = steps, interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().height(EqControlHeight).semantics { contentDescription = "$label: $valueLabel" },
            thumb = { Spacer(Modifier.size(0.dp)) }, track = { slider ->
                val fraction = (slider.value - range.start) / (range.endInclusive - range.start)
                Box(Modifier.fillMaxWidth().height(EqRailHeight).clip(shape).background(C.Sage)
                    .then(if (focused) Modifier.border(EqFocusStroke, C.Teal, shape) else Modifier)) {
                    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(shape).background(C.Sky))
                }
            })
    }
}
