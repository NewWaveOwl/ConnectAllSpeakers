package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.connectallspeakers.ui.theme.AllConnectColors as C
import connectallspeakers.shared.generated.resources.Res
import connectallspeakers.shared.generated.resources.material_icons_round
import org.jetbrains.compose.resources.Font

// Layout settings
val ControlHeight = 42.dp
val PanelCorner = 14.dp
val SectionGap = 16.dp

/** Bundled Material Icons Round codepoints used in the design. */
object Glyph {
    const val Speaker = "\uE32D"
    const val Settings = "\uE8B8"
    const val Add = "\uE145"
    const val Edit = "\uE3C9"
    const val Delete = "\uE872"
    const val Save = "\uE161"
    const val Play = "\uE037"
    const val Expand = "\uE5CF"
    const val Headphones = "\uE310"
    const val Drag = "\uE945"
    const val Reset = "\uE166"
}

/** Renders a decorative font icon matching the Figma glyphs.
 * @param glyph Material codepoint.
 * @param color Foreground color.
 * @param size Icon box and font size in density-independent units.
 */
@Composable
fun DesignIcon(glyph: String, color: Color = C.Teal, size: Dp = 20.dp) {
    Text(glyph, fontFamily = FontFamily(Font(Res.font.material_icons_round)), color = color,
        fontSize = size.value.sp, lineHeight = size.value.sp, modifier = Modifier.size(size).clearAndSetSemantics {})
}

/** Native Material button with the reference shape and dimensions.
 * @param text Visible accessible action label.
 * @param onClick User action callback.
 * @param modifier Parent sizing and semantics.
 * @param glyph Optional decorative icon.
 * @param primary Whether to fill with teal.
 * @param enabled Whether the action is currently available.
 */
@Composable
fun WorkspaceButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    glyph: String? = null, primary: Boolean = false, enabled: Boolean = true) {
    Button(onClick, modifier.height(ControlHeight), enabled = enabled,
        shape = RoundedCornerShape(10.dp), border = if (primary) null else BorderStroke(1.dp, C.Line),
        contentPadding = PaddingValues(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) C.Teal else Color.White,
            contentColor = if (primary) Color.White else C.Teal,
            disabledContainerColor = Color.White, disabledContentColor = C.Muted)) {
        if (glyph != null) { DesignIcon(glyph, LocalContentColor.current); Spacer(Modifier.width(8.dp)) }
        Text(text, maxLines = 1, style = MaterialTheme.typography.labelLarge)
    }
}

/** Small reference badge.
 * @param text Badge label.
 * @param color Foreground.
 * @param background Fill color.
 * @param modifier Parent sizing.
 */
@Composable
fun WorkspaceBadge(text: String, color: Color = C.Teal, background: Color = C.Soft, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(13.dp)).background(background).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Compact native editable field with an explicit focus outline.
 * @param value Current text, owned by screen state.
 * @param onValueChange Immediate edit callback.
 * @param label Accessible field label.
 * @param modifier Parent sizing.
 */
@Composable
fun WorkspaceTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(8.dp), color = C.Paper,
        border = if (focused) BorderStroke(2.dp, C.Teal) else null) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value, onValueChange, Modifier.weight(1f).semantics { contentDescription = label },
                singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = C.Ink),
                cursorBrush = SolidColor(C.Teal), interactionSource = interaction)
            DesignIcon(Glyph.Edit, C.Muted, 18.dp)
        }
    }
}
