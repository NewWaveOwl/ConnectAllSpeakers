package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.model.audio.PlaybackPhase
import com.example.connectallspeakers.state.MainUiState

// Layout settings
private val AppListHeight = 160.dp
private val AppPanelGap = 8.dp

/** Explicit process picker. Rendering this control never activates capture.
 * @param state Immutable session-only app list and engine status.
 * @param onAction Explicit source/transport intents.
 */
@Composable
fun AppCaptureControls(state: MainUiState, onAction: (MainAction) -> Unit) {
    val active = state.playback.phase in listOf(PlaybackPhase.Starting, PlaybackPhase.Playing)
    Column(verticalArrangement = Arrangement.spacedBy(AppPanelGap)) {
        Text("Capture a selected app · experimental", style = MaterialTheme.typography.titleSmall)
        Text("Select the app’s main process to include its child processes. Requires Windows build 20348 or newer. No virtual driver needed.", style = MaterialTheme.typography.bodySmall)
        Text("The app’s original audio output keeps playing. Capture does not mute or reroute it, so you may hear both paths.", style = MaterialTheme.typography.bodySmall)
        when {
            !state.appCaptureSupported -> Text("App audio capture is unavailable on this platform.")
            state.demoMode -> Text("Switch Demo mode off and choose physical outputs.")
            state.captureAppsLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            state.captureAppsError != null -> Text(state.captureAppsError, color = MaterialTheme.colorScheme.error)
            state.captureApps.isEmpty() -> Text("No readable running apps. Open an app, then refresh.")
        }
        Column(Modifier.heightIn(max = AppListHeight).verticalScroll(rememberScrollState())) {
            state.captureApps.forEach { app ->
                Row {
                    RadioButton(selected = state.selectedCaptureApp == app,
                        onClick = { onAction(MainAction.SelectCaptureApp(app)) }, enabled = !state.demoMode && !state.captureAppsLoading,
                        modifier = Modifier.semantics { contentDescription = "Capture ${app.name}, process ${app.processId}" })
                    TextButton(onClick = { onAction(MainAction.SelectCaptureApp(app)) }, enabled = !state.demoMode && !state.captureAppsLoading) {
                        Text("${app.name} · PID ${app.processId}")
                    }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppPanelGap)) {
            WorkspaceButton("Refresh apps", { onAction(MainAction.RefreshCaptureApps) }, enabled = state.appCaptureSupported && !state.captureAppsLoading)
            WorkspaceButton("Start app audio", { onAction(MainAction.StartAppCapture) }, primary = true,
                enabled = state.appCaptureSupported && state.selectedCaptureApp != null && !state.demoMode && !active && !state.captureAppsLoading && state.captureAppsError == null)
            WorkspaceButton("Stop", { onAction(MainAction.Stop) }, enabled = active)
        }
        Text(state.playback.message ?: "Stopped · starts only when you press Start app audio.",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = if (state.playback.phase == PlaybackPhase.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall)
        Text("Stop, source changes and app exit discard captured audio. Nothing is recorded. Automatic restart and acoustic alignment are unavailable.", style = MaterialTheme.typography.bodySmall)
    }
}
