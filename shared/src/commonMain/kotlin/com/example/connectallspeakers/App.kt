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
import com.example.connectallspeakers.data.audio.AudioPlaybackController

/** Assembles shared rendering; the platform owner closes discovery independently of screen disposal.
 * @param outputRepository Native discovery supplied by Windows, or null on unsupported platforms.
 * @param playbackController Application-owned native playback, or null on other platforms.
 * @param chooseAudioFile Platform file chooser, invoked only by an explicit user action.
 */
@Composable
fun App(outputRepository: AudioOutputRepository? = null, playbackController: AudioPlaybackController? = null,
    chooseAudioFile: () -> Unit = {}) {
    val owner = viewModel { MainViewModel(outputRepository = outputRepository,
        playbackController = playbackController, chooseAudioFile = chooseAudioFile) }
    val state by owner.state.collectAsStateWithLifecycle()
    ConnectAllSpeakersTheme {
        MainLayout {
            MainScreen(state, owner::onAction)
        }
    }
}
