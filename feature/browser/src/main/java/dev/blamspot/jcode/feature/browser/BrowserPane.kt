package dev.blamspot.jcode.feature.browser

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.core.browserext.BundledExtensionCatalog
import dev.blamspot.jcode.core.browserext.BundledExtensionDetails
import dev.blamspot.jcode.core.browserext.BundledExtensionPreferences
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space

/**
 * The bundled-extension manager for the in-app browser.
 *
 * The browser page itself opens as an editor tab; this panel is where the extensions that drive it
 * are registered. Each entry comes from the extension's own `manifest.json` inside the APK (name,
 * version, description, icon, injected hosts, skills), so adding a bundled extension is a manifest
 * plus an entry in `BundledBrowserExtensions` — no values copied into Kotlin.
 *
 * [onOpenUrl] is how a row reaches the web: the panel owns no WebView of its own, so opening Flow
 * hands the URL to the workbench's single browser rather than starting a second one. [onInstallSkills]
 * copies the extension's `skills/*` into the embedded Linux environment; it is null when the host does
 * not have a runtime to install into.
 */
@Composable
fun BrowserPane(
    modifier: Modifier = Modifier,
    bridgeLabel: String = "Bridge idle",
    bridgeReady: Boolean = false,
    onOpenUrl: (String) -> Unit = {},
    onInstallSkills: (() -> Unit)? = null,
    onEnabledChange: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val catalog = remember(context) { BundledExtensionCatalog(context.assets) }
    val preferences = remember(context) { BundledExtensionPreferences(context) }
    val extensions = remember(catalog) { catalog.all() }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            Text(
                text = "QCODE Browser",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Extensions bundled inside this build.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.md, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            extensions.forEach { details ->
                BundledExtensionCard(
                    details = details,
                    preferences = preferences,
                    onOpenUrl = onOpenUrl,
                    onInstallSkills = onInstallSkills,
                    onEnabledChange = onEnabledChange,
                )
            }
            BridgeStatus(label = bridgeLabel, ready = bridgeReady)
        }
    }
}

@Composable
private fun BundledExtensionCard(
    details: BundledExtensionDetails,
    preferences: BundledExtensionPreferences,
    onOpenUrl: (String) -> Unit,
    onInstallSkills: (() -> Unit)?,
    onEnabledChange: ((Boolean) -> Unit)?,
) {
    val context = LocalContext.current
    var enabled by remember(details.id) { mutableStateOf(preferences.isEnabled(details.id)) }
    val icon = remember(details.iconAsset) { loadAssetBitmap(context, details.iconAsset) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.xxl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                ExtensionIcon(bitmap = icon, name = details.name)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = details.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            details.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                            "Manifest V3",
                            "Bundled",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        enabled = checked
                        preferences.setEnabled(details.id, checked)
                        onEnabledChange?.invoke(checked)
                    },
                )
            }
            if (details.description.isNotBlank()) {
                Text(
                    text = details.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (details.contentScriptMatches.isNotEmpty()) {
                Text(
                    text = "Injects on " + details.contentScriptMatches.joinToString(", ") { hostLabel(it) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Button(
                    onClick = { onOpenUrl(FLOW_URL) },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.xs),
                ) {
                    Text("Open Flow", style = MaterialTheme.typography.labelLarge)
                }
                if (onInstallSkills != null && details.skills.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onInstallSkills,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.xs),
                    ) {
                        Text(
                            text = "Install ${details.skills.size} skills",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            if (details.skills.isNotEmpty()) {
                Text(
                    text = details.skills.joinToString(" · ") { it.replace('-', ' ') },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BridgeStatus(label: String, ready: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.xxl),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (ready) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "Native bridge · MCP server deepx-flow",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExtensionIcon(bitmap: ImageBitmap?, name: String) {
    val shape = RoundedCornerShape(Radius.xl)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(shape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** Decoded once per icon path: the assets are small, and the panel is not recomposed per frame. */
private fun loadAssetBitmap(context: Context, path: String?): ImageBitmap? {
    if (path == null) return null
    return runCatching {
        context.assets.open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    }.getOrNull()
}

private fun hostLabel(pattern: String): String =
    pattern.removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("*.")
        .removeSuffix("/*")

private const val FLOW_URL = "https://flow.google.com"
