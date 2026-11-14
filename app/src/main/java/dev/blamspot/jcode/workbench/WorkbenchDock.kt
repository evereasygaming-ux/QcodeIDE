package dev.blamspot.jcode.workbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.QCodeIcon
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.jcIcon

/**
 * The phone workbench's dock: the handful of destinations worth a permanent tap, plus a direct door to
 * the console sheet.
 *
 * It replaces the "hamburger → drawer → pick a tool" walk that a desktop layout imposes on a phone.
 * The drawer still exists behind the menu button and lists every destination with room for labels;
 * this row is the shortcut layer over it. It hides while the IME is up so typing never pays for it —
 * the extra-keys row and the console sheet have that space.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkbenchBottomDock(
    selectedTool: WorkbenchTool,
    consoleVisible: Boolean,
    onOpenTool: (WorkbenchTool) -> Unit,
    onOpenConsole: () -> Unit,
) {
    if (WindowInsets.isImeVisible) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = Space.md, vertical = Space.xs),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.pill),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.26f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val destinations = listOf(
                    WorkbenchTool.Explorer,
                    WorkbenchTool.Search,
                    WorkbenchTool.RunDebug,
                    WorkbenchTool.Extensions,
                )
                destinations.forEach { tool ->
                    DockItem(
                        icon = jcIcon(tool.icon),
                        label = tool.compactLabel,
                        selected = !consoleVisible && selectedTool == tool,
                        onClick = { onOpenTool(tool) },
                        modifier = Modifier.weight(1f),
                    )
                }
                DockItem(
                    icon = jcIcon(QCodeIcon.Terminal),
                    label = "Console",
                    selected = consoleVisible,
                    onClick = onOpenConsole,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One destination in the dock: glyph over label, filled with the accent when it owns the screen. */
@Composable
private fun DockItem(
    icon: Painter,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.xl))
            .clickable(onClick = onClick)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    Color.Transparent
                },
            )
            .padding(vertical = Space.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Icon(
            painter = icon,
            contentDescription = label,
            modifier = Modifier.size(IconSize.md),
            tint = content,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
