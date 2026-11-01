package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.ui.theme.AllConnectColors as C
import kotlin.math.roundToInt

// Layout settings — Figma “New Slder_ v2a”, node 17:891.
private val VolumeRailHeight = 18.dp
private val VolumeTouchHeight = 48.dp
private val VolumeRailCorner = 3.dp
private val VolumeValueWidth = 36.dp
private val VolumeValueGap = 8.dp
private val VolumeFocusStroke = 2.dp

/** Native, keyboard-accessible slider using the thumb-free New Slder_ v2a visual.
 * The full-width row shares the field/button edges; the percentage is right-aligned.
 * @param percent App volume setting from 0 to 100; presentation clamps invalid bounds.
 * @param onPercentChange Immediate integer percentage callback, with no OS-volume side effect.
 * @param modifier Parent sizing and semantics.
 */
@Composable
fun VolumeSlider(percent: Int, onPercentChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(VolumeRailCorner)
    Row(modifier.fillMaxWidth().height(VolumeTouchHeight), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(VolumeValueGap)) {
        // Keep the native gesture, range semantics and keyboard behavior. A zero-width thumb
        // removes Material's visual thumb and its horizontal inset without shrinking the hit area.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides VolumeTouchHeight) {
            Slider(value = percent.coerceIn(0, 100).toFloat(),
                onValueChange = { onPercentChange(it.roundToInt().coerceIn(0, 100)) },
                valueRange = 0f..100f, steps = 99, interactionSource = interaction,
                modifier = Modifier.weight(1f).height(VolumeTouchHeight)
                    .semantics { contentDescription = "App volume setting; playback not implemented" },
                thumb = { Spacer(Modifier.size(0.dp)) },
                track = { slider ->
                    Box(Modifier.fillMaxWidth().height(VolumeRailHeight).clip(shape)
                        .background(C.Sage).then(if (focused) Modifier.border(VolumeFocusStroke, C.Teal, shape) else Modifier)) {
                        Box(Modifier.fillMaxWidth((slider.value / 100f).coerceIn(0f, 1f))
                            .fillMaxHeight().clip(shape).background(C.Sky))
                    }
                },
            )
        }
        androidx.compose.material3.Text("${percent.coerceIn(0, 100)}%", Modifier.width(VolumeValueWidth),
            textAlign = TextAlign.End, style = MaterialTheme.typography.labelMedium)
    }
}
