package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.state.MainDialog
import com.example.connectallspeakers.state.MainUiState

// Layout settings
private val DialogSpacing = 12.dp

/** Small screen-local dialogs with validation and visibility owned by the ViewModel.
 * @param state Current modal and draft values.
 * @param onAction Screen intent callback.
 */
@Composable
fun SetupDialogs(state: MainUiState, onAction: (MainAction) -> Unit) {
    val dialog = state.dialog ?: return
    val title = when (dialog) { MainDialog.AddSetup -> "Add setup"; MainDialog.RenameSetup -> "Rename setup"
        MainDialog.DeleteSetup -> "Delete setup?"; MainDialog.AddSpeaker -> if (state.demoMode) "Add sample speaker" else "Add speaker"
        MainDialog.Settings -> {
            SettingsDialog { onAction(MainAction.DismissDialog) }
            return
        }
        MainDialog.ReloadSetups -> "Reload saved setups?"
    }
    AlertDialog(onDismissRequest = { onAction(MainAction.DismissDialog) }, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DialogSpacing)) {
                when (dialog) {
                    MainDialog.AddSpeaker -> {
                        if (!state.demoMode) {
                            AudioOutputPicker(state, onAction)
                        } else {
                            Text("Demo samples only — no devices are discovered or connected.")
                            listOf("studio" to "Studio Speaker", "portable" to "Portable Mini", "bookshelf" to "Bookshelf One").forEach { (id, label) ->
                                WorkspaceButton(label, { onAction(MainAction.AddSpeaker(id)) }, Modifier.fillMaxWidth(), Glyph.Speaker)
                            }
                        }
                    }
                    MainDialog.DeleteSetup -> Text("Remove “${state.selectedSetup?.name.orEmpty()}” and its edits? If saved, it will also be deleted from this PC. This cannot be undone.")
                    MainDialog.ReloadSetups -> Text("Stop playback and reload saved setups from this PC? Unsaved edits in both real and demo rooms will be discarded only if loading succeeds. For a damaged or unsupported file, move it aside first; the app will not overwrite it.")
                    else -> {
                        WorkspaceTextField(state.dialogName, { onAction(MainAction.DialogName(it)) }, "Setup name")
                        state.dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {
            if (dialog != MainDialog.AddSpeaker) TextButton({ onAction(MainAction.ConfirmDialog) }, enabled = !state.savingProfiles) {
                Text(when (dialog) { MainDialog.DeleteSetup -> "Delete"; MainDialog.ReloadSetups -> "Reload"; MainDialog.AddSetup -> "Create"; else -> "Rename" })
            }
        }, dismissButton = { TextButton({ onAction(MainAction.DismissDialog) }) { Text("Cancel") } },
    )
}
