package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.model.audio.MAX_OUTPUT_DELAY_MILLIS
import com.example.connectallspeakers.ui.theme.AllConnectColors as C
import kotlin.math.roundToInt

// Layout settings
private val DelayControlGap = 4.dp
private val DelayRailHeight = 18.dp
private val DelayTouchHeight = 48.dp
private val DelayRailCorner = 3.dp
private val DelayFocusStroke = 2.dp

/** Keyboard-accessible whole-millisecond delay control; changes require an explicit playback restart.
 * @param delayMillis Current saved-in-session delay.
 * @param isReference Whether this is the zero-delay reference speaker.
 * @param onDelay Requested bounded value callback.
 */
@Composable
fun DelayControl(delayMillis: Int, isReference: Boolean, onDelay: (Int) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(DelayRailCorner)
    Column(verticalArrangement = Arrangement.spacedBy(DelayControlGap)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Manual delay", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text("$delayMillis ms", style = MaterialTheme.typography.labelMedium)
            TextButton({ onDelay(0) }, enabled = !isReference && delayMillis != 0) { Text("Reset") }
        }
        // Reuse the existing New Slder_ v2a rail treatment while retaining native range/keyboard behavior.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides DelayTouchHeight) {
            Slider(delayMillis.toFloat(), { onDelay(it.roundToInt()) }, enabled = !isReference,
                valueRange = 0f..MAX_OUTPUT_DELAY_MILLIS.toFloat(), steps = MAX_OUTPUT_DELAY_MILLIS - 1,
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth().height(DelayTouchHeight)
                    .semantics { contentDescription = "Manual speaker delay in milliseconds" },
                thumb = { Spacer(Modifier.size(0.dp)) },
                track = { slider ->
                    Box(Modifier.fillMaxWidth().height(DelayRailHeight).clip(shape)
                        .background(if (isReference) C.Line else C.Sage)
                        .then(if (focused) Modifier.border(DelayFocusStroke, C.Teal, shape) else Modifier)) {
                        Box(Modifier.fillMaxWidth((slider.value / MAX_OUTPUT_DELAY_MILLIS).coerceIn(0f, 1f))
                            .fillMaxHeight().clip(shape).background(C.Sky))
                    }
                })
        }
        Text(if (isReference) "Reference speaker: 0 ms. Choose the slower speaker as reference in Manual calibration."
            else "0–$MAX_OUTPUT_DELAY_MILLIS ms. Changing delay stops audio; restart to apply.",
            style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}
