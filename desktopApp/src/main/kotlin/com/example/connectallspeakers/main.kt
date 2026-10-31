package com.example.connectallspeakers

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/** Opens the desktop window; the application owner will manage native audio lifetime. */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ConnectAllSpeakers",
    ) {
        App()
    }
}