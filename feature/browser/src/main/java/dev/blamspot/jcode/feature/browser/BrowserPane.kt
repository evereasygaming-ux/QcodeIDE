package dev.blamspot.jcode.feature.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.core.browserext.BundledBrowserExtensions

/**
 * Placeholder for the Phase 4 browser surface.
 *
 * The WebView host and extension manager move here once the DeepX runtime is ported; until then the
 * app keeps using its existing editor-tab browser and this pane only exposes the bundled extension
 * catalog.
 */
@Composable
fun BrowserPane(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("QCODE Browser", style = MaterialTheme.typography.titleMedium)
        Text(
            "${BundledBrowserExtensions.all.size} bundled extension",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
