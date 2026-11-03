package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.connectallspeakers.ViewModels.main.MainAction
import com.example.connectallspeakers.state.MainDialog
import com.example.connectallspeakers.state.MainUiState
import com.example.connectallspeakers.state.ProfileStorageStatus
import com.example.connectallspeakers.ui.theme.AllConnectColors as C

// Layout settings
private val StatusHorizontalPadding = 24.dp
private val StatusVerticalPadding = 8.dp

/** Truthful save/restore status; error recovery is explicit and never destroys unsaved edits silently.
 * @param state Storage readiness and selected-room dirty state.
 * @param onAction Reload-dialog request callback; no storage I/O occurs in rendering.
 */
@Composable
fun ProfileStorageStatus(state: MainUiState, onAction: (MainAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = StatusHorizontalPadding, vertical = StatusVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val status = when {
            state.savingProfiles -> "Saving setups to this PC…"
            state.profileStorage == ProfileStorageStatus.SessionOnly -> "Session only — saved setups cannot be restored on this platform."
            state.profileStorage == ProfileStorageStatus.LoadFailed -> "Saved setups could not be loaded. Your existing file is protected."
            state.hasUnsavedSetup -> "Unsaved changes — Save setup to keep this room and selection after reopening."
            state.selectedSetup != null -> "Saved room — further edits and selection changes need Save setup."
            else -> "No room selected. Add a setup to begin."
        }
        Text(status, color = C.Muted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        state.profileError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton({ onAction(MainAction.OpenDialog(MainDialog.ReloadSetups)) }, enabled = !state.savingProfiles) {
                Text("Retry loading saved setups…")
            }
        }
    }
}
