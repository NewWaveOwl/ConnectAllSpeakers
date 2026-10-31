package com.example.connectallspeakers.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import connectallspeakers.shared.generated.resources.Res
import connectallspeakers.shared.generated.resources.lato_bold
import connectallspeakers.shared.generated.resources.lato_regular
import org.jetbrains.compose.resources.Font

/** Palette from the Main setup workspace Figma frame. */
object AllConnectColors {
    val Teal = Color(0xFF51A3A3)
    val Sky = Color(0xFF60A1C5)
    val Plum = Color(0xFF75485E)
    val Sand = Color(0xFFE8D5B7)
    val Sage = Color(0xFFC3E991)
    val Ink = Color(0xFF2D2830)
    val Muted = Color(0xFF736B72)
    val Paper = Color(0xFFFAF8F5)
    val Soft = Color(0xFFF1EBE5)
    val Line = Color(0xFFE8E2DD)
}

/**
 * Applies locally bundled Lato and the shared Material 3 palette.
 *
 * @param content Application content rendered within this theme.
 */
@Composable
fun ConnectAllSpeakersTheme(content: @Composable () -> Unit) {
    val lato = FontFamily(Font(Res.font.lato_regular), Font(Res.font.lato_bold, FontWeight.Bold))
    val regular = TextStyle(fontFamily = lato, fontSize = 14.sp, lineHeight = 19.sp)
    val bold = regular.copy(fontWeight = FontWeight.Bold)
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = AllConnectColors.Teal, onPrimary = Color.White,
            secondary = AllConnectColors.Sky, background = AllConnectColors.Paper,
            surface = Color.White, surfaceContainer = AllConnectColors.Paper,
            surfaceContainerHigh = Color.White, onSurface = AllConnectColors.Ink,
            onSurfaceVariant = AllConnectColors.Muted, outline = AllConnectColors.Line,
        ),
        typography = Typography(
            bodyLarge = regular, bodyMedium = regular, bodySmall = regular.copy(fontSize = 12.sp, lineHeight = 16.sp),
            labelLarge = bold, labelMedium = bold.copy(fontSize = 12.sp, lineHeight = 16.sp),
            labelSmall = bold.copy(fontSize = 11.sp, lineHeight = 15.sp),
            titleLarge = bold.copy(fontSize = 22.sp, lineHeight = 30.sp),
            titleMedium = bold.copy(fontSize = 18.sp, lineHeight = 24.sp),
            titleSmall = bold.copy(fontSize = 17.sp, lineHeight = 23.sp),
            headlineSmall = bold.copy(fontSize = 28.sp, lineHeight = 38.sp),
        ), content = content,
    )
}
