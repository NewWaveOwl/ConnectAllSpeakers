package com.example.connectallspeakers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.connectallspeakers.ViewModels.main.MainViewModel
import com.example.connectallspeakers.View.main.MainScreen
import com.example.connectallspeakers.layout.MainLayout
import com.example.connectallspeakers.ui.theme.ConnectAllSpeakersTheme
import com.example.connectallspeakers.data.audio.AudioOutputRepository

/** Assembles shared rendering; the platform owner closes discovery independently of screen disposal.
 * @param outputRepository Native discovery supplied by Windows, or null on unsupported platforms.
 */
@Composable
fun App(outputRepository: AudioOutputRepository? = null) {
    val owner = viewModel { MainViewModel(outputRepository = outputRepository) }
    val state by owner.state.collectAsStateWithLifecycle()
    ConnectAllSpeakersTheme {
        MainLayout {
            MainScreen(state, owner::onAction)
        }
    }
}
