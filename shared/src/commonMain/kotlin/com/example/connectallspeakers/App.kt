package com.example.connectallspeakers

import androidx.compose.runtime.Composable
import com.example.connectallspeakers.View.main.MainScreen
import com.example.connectallspeakers.layout.MainLayout
import com.example.connectallspeakers.ui.theme.ConnectAllSpeakersTheme

/** Assembles reusable presentation; platform launchers own native dependencies. */
@Composable
fun App() {
    ConnectAllSpeakersTheme {
        MainLayout {
            MainScreen()
        }
    }
}
