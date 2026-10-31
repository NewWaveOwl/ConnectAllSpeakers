package com.example.connectallspeakers

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.DisposableEffect
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

// Layout settings
private val ReferenceWindowWidth = 1440.dp
private val ReferenceWindowHeight = 900.dp
private val MinimumWindowWidth = 560.dp
private val MinimumWindowHeight = 480.dp

/** Opens the desktop window; the application owner will manage native audio lifetime. */
fun main() = application {
    val windowState = rememberWindowState(width = ReferenceWindowWidth, height = ReferenceWindowHeight)
    Window(
        onCloseRequest = ::exitApplication,
        title = "AllConnect — frontend prototype",
        state = windowState,
    ) {
        val density = LocalDensity.current
        DisposableEffect(window, density) {
            window.minimumSize = with(density) { Dimension(MinimumWindowWidth.roundToPx(), MinimumWindowHeight.roundToPx()) }
            // Insets are valid after the native window opens, rather than at initial composition.
            val listener = object : WindowAdapter() {
                /** Adjusts native decorations once so the client area matches the design reference. */
                override fun windowOpened(event: WindowEvent) {
                    windowState.size = with(density) { DpSize(
                        ReferenceWindowWidth + (window.insets.left + window.insets.right).toDp(),
                        ReferenceWindowHeight + (window.insets.top + window.insets.bottom).toDp(),
                    ) }
                }
            }
            window.addWindowListener(listener)
            onDispose { window.removeWindowListener(listener) }
        }
        App()
    }
}
