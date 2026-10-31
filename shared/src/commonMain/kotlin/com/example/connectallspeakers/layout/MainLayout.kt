package com.example.connectallspeakers.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Layout settings
private val ContentPadding = 24.dp

/**
 * Owns shared scaffold insets and the content area's outer padding.
 *
 * @param content Screen content; the scaffold applies system insets once.
 */
@Composable
fun MainLayout(content: @Composable () -> Unit) {
    Scaffold { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets).padding(ContentPadding),
        ) {
            content()
        }
    }
}
