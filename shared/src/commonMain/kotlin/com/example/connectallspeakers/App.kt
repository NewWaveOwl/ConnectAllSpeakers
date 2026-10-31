package com.example.connectallspeakers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.connectallspeakers.ViewModels.main.MainViewModel
import com.example.connectallspeakers.View.main.MainScreen
import com.example.connectallspeakers.layout.MainLayout
import com.example.connectallspeakers.ui.theme.ConnectAllSpeakersTheme

/** Assembles reusable presentation; platform launchers own native dependencies. */
@Composable
fun App() {
    val owner = viewModel { MainViewModel() }
    val state by owner.state.collectAsStateWithLifecycle()
    ConnectAllSpeakersTheme {
        MainLayout {
            MainScreen(state, owner::onAction)
        }
    }
}
