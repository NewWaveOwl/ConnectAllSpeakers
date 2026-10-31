package com.example.connectallspeakers.View.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Layout settings
private val MainContentMaxWidth = 720.dp
private val MainContentSpacing = 16.dp

/**
 * Presents the foundation's current capability without simulating device discovery.
 *
 * @param modifier Parent-supplied sizing and accessibility modifiers.
 */
@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.widthIn(max = MainContentMaxWidth).fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MainContentSpacing),
    ) {
        Text("ConnectAllSpeakers", style = MaterialTheme.typography.headlineLarge)
        Text("Windows audio setup", style = MaterialTheme.typography.titleLarge)
        Text("Speaker discovery and playback are not available yet.")
        Text("The first playback step will connect two speakers and assign left and right audio.")
    }
}
