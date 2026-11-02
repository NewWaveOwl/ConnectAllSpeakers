package com.example.connectallspeakers.View.main.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.connectallspeakers.ui.theme.AllConnectColors as C

// Layout settings
private val SettingsWidth = 420.dp
private val SettingsPadding = 24.dp
private val SettingsSpacing = 16.dp
private val SettingsFooterGap = 56.dp

/** Small Settings container with a readable, discreet author credit in its footer.
 * @param onDismiss Closes the dialog from Close, Escape/back or the platform dismiss gesture.
 */
@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.widthIn(max = SettingsWidth).fillMaxWidth(),
            shape = RoundedCornerShape(PanelCorner), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(SettingsPadding),
                verticalArrangement = Arrangement.spacedBy(SettingsSpacing)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Settings", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text("More settings will be available in a future update.",
                    color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(SettingsFooterGap))
                HorizontalDivider(color = C.Line)
                Text("Made by Artiom Sova", Modifier.fillMaxWidth(), color = C.Muted,
                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}
