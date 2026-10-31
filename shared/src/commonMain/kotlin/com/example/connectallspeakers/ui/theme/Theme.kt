package com.example.connectallspeakers.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Applies the shared Material 3 theme.
 *
 * @param content Application content rendered within this theme.
 */
@Composable
fun ConnectAllSpeakersTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
