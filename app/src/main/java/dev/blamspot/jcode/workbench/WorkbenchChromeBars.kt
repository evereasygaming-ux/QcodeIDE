package dev.blamspot.jcode.workbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.blamspot.jcode.MainViewModel
import dev.blamspot.jcode.design.CompactContextMenu
import dev.blamspot.jcode.design.ContextAction
import dev.blamspot.jcode.core.debug.DebugState
import dev.blamspot.jcode.design.HeaderActionButton
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.QCodeIcon
import dev.blamspot.jcode.design.LocalCommandPaletteLauncher
import dev.blamspot.jcode.design.LocalEditorSaveActions
import dev.blamspot.jcode.design.LocalHeaderActionSetting
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.jcIcon
import dev.blamspot.jcode.feature.editor.pane.EditorTab
import dev.blamspot.jcode.fs.Project
import dev.blamspot.jcode.fs.Workspace

/**
 * The workbench header bars peeled out of QCodeShell: the left-panel [WorkspaceHeader] (workspace
 * title + tool row) and the editor-area [WorkbenchTopBar] (nav + title + Save/Run/Terminal quick
 * actions). Stateless / param-driven; the buttons they use live in WorkbenchChrome.kt.
 */

private fun contributedActionIcon(id: String): QCodeIcon = when (id) {
    "clone" -> QCodeIcon.Scm
    "remoteRepo" -> QCodeIcon.Browser
    else -> QCodeIcon.Code
}

@Composable
internal fun WorkspaceHeader(
    selectedTool: WorkbenchTool,
    workspace: Workspace?,
    selectedProject: Project?,
    inUserWorkspace: Boolean,
    dbManagerAvailable: Boolean,
    scmAvailable: Boolean,
    vmManagerAvailable: Boolean,
    onSelectTool: (WorkbenchTool) -> Unit,
    onCreateProject: () -> Unit,
    onOpenExternalFolder: () -> Unit,
    contributedDrawerActions: List<MainViewModel.ShellContribution>,
    onDrawerAction: (MainViewModel.ShellContribution) -> Unit,
    onCloseWorkspace: () -> Unit,
    onCloseProject: () -> Unit,
    onCollapseSidebar: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.ms, vertical = Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            // In the persistent (landscape) layout, the editor top-bar's sidebar toggle sits under
            // this panel, so surface a collapse control in the panel header itself.
            onCollapseSidebar?.let { collapse ->
                WorkbenchIconActionButton(
                    icon = jcIcon(QCodeIcon.MenuToggle),
                    contentDescription = "Hide left sidebar",
                    onClick = collapse,
                    active = true,
                )
            }
            // The title doubles as a menu anchor: a User Workspace can be closed (Close workspace),
            // and the Default Workspace can close its open project (Close project). With nothing open
            // in the Default Workspace there is nothing to close, so it stays a plain label.
            val canCloseProject = !inUserWorkspace && selectedProject != null
            val hasCloseAction = inUserWorkspace || canCloseProject
            Box(modifier = Modifier.weight(1f)) {
                var menuExpanded by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.lg))
                        .then(
                            if (hasCloseAction) Modifier.clickable { menuExpanded = true } else Modifier,
                        )
                        .padding(vertical = Space.xxs),
                    verticalArrangement = Arrangement.spacedBy(Space.hairline),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
                    ) {
                        Text(
                            text = workspace?.name ?: "Default Workspace",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (hasCloseAction) {
                            Icon(
                                painter = jcIcon(QCodeIcon.DropDown),
                                contentDescription = null,
                                modifier = Modifier.size(IconSize.sm),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        text = selectedProject?.distroBindTarget ?: "No bind target yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CompactContextMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    listActions = listOf(
                        ContextAction(
                            QCodeIcon.Close,
                            if (inUserWorkspace) "Close workspace" else "Close project",
                        ) { if (inUserWorkspace) onCloseWorkspace() else onCloseProject() },
                    ),
                )
            }

            WorkbenchIconActionButton(
                icon = jcIcon(QCodeIcon.Add),
                contentDescription = "Add project",
                onClick = onCreateProject,
            )
            Box {
                var openFolderMenu by remember { mutableStateOf(false) }
                WorkbenchIconActionButton(
                    icon = jcIcon(QCodeIcon.OpenFolder),
                    contentDescription = "Open folder",
                    onClick = { if (contributedDrawerActions.isEmpty()) onOpenExternalFolder() else openFolderMenu = true },
                )
                if (contributedDrawerActions.isNotEmpty()) {
                    CompactContextMenu(
                        expanded = openFolderMenu,
                        onDismissRequest = { openFolderMenu = false },
                        listActions = buildList {
                            add(ContextAction(QCodeIcon.OpenFolder, "Open Folder") { onOpenExternalFolder() })
                            contributedDrawerActions.forEach { a ->
                                add(ContextAction(contributedActionIcon(a.id), a.label) { onDrawerAction(a) })
                            }
                        },
                    )
                }
            }
        }

        // The tool dock: a compact tile grid instead of a scrolling row of labels. Every destination
        // is visible at once, and the selected one reads as a filled, bordered card.
        val tools = WorkbenchTool.entries
            .filter {
                it.available &&
                    (it != WorkbenchTool.DbManager || dbManagerAvailable) &&
                    (it != WorkbenchTool.Scm || scmAvailable) &&
                    (it != WorkbenchTool.VmManager || vmManagerAvailable)
            }
        val columns = 4
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            tools.chunked(columns).forEach { rowTools ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    rowTools.forEach { tool ->
                        SidebarToolTile(
                            tool = tool,
                            selected = selectedTool == tool,
                            onClick = { onSelectTool(tool) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowTools.size) {
                        Box(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkbenchTopBar(
    workspace: Workspace?,
    selectedProject: Project?,
    activeTab: EditorTab?,
    leftSidebarExpanded: Boolean,
    canShowRightSidebar: Boolean,
    rightSidebarVisible: Boolean,
    rightSidebarDocked: Boolean,
    onToggleLeftSidebar: () -> Unit,
    onToggleRightSidebar: () -> Unit,
    onShowTerminal: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onRerun: () -> Unit,
    runConfigNames: List<String>,
    onRunConfig: (Int) -> Unit,
    isRunning: Boolean,
    terminalBusy: Boolean,
    terminalHasUnseen: Boolean,
    terminalSessions: List<TerminalInstance>,
    onOpenTerminalSession: (String) -> Unit,
    onSave: () -> Unit,
) {
    // The command deck: a floating card of brand, a palette search field and the quick actions, with
    // a breadcrumb ribbon under it. It deliberately does not repeat a title the active tab shows.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.sm, vertical = Space.xs),
        shape = RoundedCornerShape(Radius.sheet),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
        ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Phones get the monogram alone; the wordmark only appears where it does not crowd the
        // palette field out of the row.
        val showWordmark = maxWidth >= 420.dp
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = Space.sm, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            WorkbenchIconActionButton(
                icon = jcIcon(QCodeIcon.MenuToggle),
                contentDescription = if (leftSidebarExpanded) "Hide left sidebar" else "Show left sidebar",
                onClick = onToggleLeftSidebar,
                active = leftSidebarExpanded,
            )

            // Brand chip: the launcher mark in miniature, then the wordmark.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Surface(
                    modifier = Modifier.size(24.dp),
                    shape = RoundedCornerShape(Radius.lg),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CorexMark(modifier = Modifier.size(15.dp))
                    }
                }
                if (showWordmark) {
                    Text(
                        text = "QCODE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.4.sp,
                        maxLines = 1,
                    )
                }
            }

            // The palette's front door: one tap anywhere on this field opens it. The hint disappears
            // before the field gets too narrow to carry it, rather than being crushed to an ellipsis.
            val palette = LocalCommandPaletteLauncher.current
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(Radius.pill))
                        .clickable { palette.onOpen() },
                    shape = RoundedCornerShape(Radius.pill),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Space.ms),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        Icon(
                            painter = jcIcon(QCodeIcon.Search),
                            contentDescription = null,
                            modifier = Modifier.size(IconSize.sm),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (maxWidth > 150.dp) {
                            Text(
                                text = "Search files and commands",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            if (activeTab?.editorState != null) {
                val saveActions = LocalEditorSaveActions.current
                var saveMenuOpen by remember { mutableStateOf(false) }
                Box {
                    WorkbenchIconActionButton(
                        icon = jcIcon(QCodeIcon.Save),
                        contentDescription = if (activeTab.isDirty) "Save (unsaved changes)" else "Save",
                        onClick = onSave,
                        active = activeTab.isDirty,
                        onLongClick = { saveMenuOpen = true },
                    )
                    CompactContextMenu(
                        expanded = saveMenuOpen,
                        onDismissRequest = { saveMenuOpen = false },
                        quickActions = listOf(
                            ContextAction(QCodeIcon.Undo, "Undo") { saveActions.onUndo() },
                            ContextAction(QCodeIcon.Redo, "Redo") { saveActions.onRedo() },
                            ContextAction(QCodeIcon.Discard, "Discard", destructive = true) { saveActions.onDiscard() },
                            ContextAction(QCodeIcon.Save, "Save all") { saveActions.onSaveAll() },
                        ),
                    )
                }
            }
            // Run toggles to Stop while a run is active. A tap runs the single/first config; with more
            // than one config it opens a picker instead. Long-press always opens the menu — the list of
            // run configs to launch, plus the debug/session controls.
            // Only shown when a project is open and focused — there's nothing to run otherwise.
            if (selectedProject != null) {
                var runMenuOpen by remember { mutableStateOf(false) }
                val hasMultipleRuns = runConfigNames.size > 1
                // A debug session owns this button while it lasts, but it stays a three-state control:
                // Run to start, Continue at a breakpoint, Stop while anything is live. Pause is
                // deliberately not here — it belongs with the stepping controls in this button's
                // long-press menu and the Run panel, and putting it in the header meant the one
                // obvious control could not end a session. Without the Continue state the header kept
                // offering "Run" at a breakpoint, the one thing you cannot do from there.
                val debug = LocalDebugSession.current
                val paused = debug.state == DebugState.STOPPED
                val debugging = paused || debug.state == DebugState.RUNNING
                Box {
                    WorkbenchIconActionButton(
                        icon = when {
                            paused -> jcIcon(QCodeIcon.Continue)
                            debugging || isRunning -> jcIcon(QCodeIcon.Stop)
                            else -> jcIcon(QCodeIcon.Run)
                        },
                        contentDescription = when {
                            paused -> "Continue"
                            debugging -> "Stop debugging"
                            isRunning -> "Stop"
                            else -> "Run"
                        },
                        onClick = {
                            when {
                                paused -> debug.onContinue()
                                debugging -> debug.onStop()
                                isRunning -> onStop()
                                hasMultipleRuns -> runMenuOpen = true
                                else -> onRun()
                            }
                        },
                        active = isRunning || debugging,
                        onLongClick = { runMenuOpen = true },
                    )
                    CompactContextMenu(
                        expanded = runMenuOpen,
                        onDismissRequest = { runMenuOpen = false },
                        quickActions = listOf(
                            // The session controls, live only while it is paused — stepping a running
                            // debuggee is not a thing, and these were dead placeholders before the
                            // debugger existed.
                            ContextAction(QCodeIcon.Continue, "Continue", enabled = paused) { debug.onContinue() },
                            ContextAction(QCodeIcon.Pause, "Pause", enabled = debug.state == DebugState.RUNNING) {
                                debug.onPause()
                            },
                            ContextAction(QCodeIcon.StepOver, "Step Over", enabled = paused) { debug.onStepOver() },
                            ContextAction(QCodeIcon.StepInto, "Step Into", enabled = paused) { debug.onStepInto() },
                            ContextAction(QCodeIcon.StepOut, "Step Out", enabled = paused) { debug.onStepOut() },
                            if (debugging) {
                                ContextAction(QCodeIcon.Stop, "Stop debugging") { debug.onStop() }
                            } else {
                                ContextAction(QCodeIcon.Rerun, "Rerun") { onRerun() }
                            },
                        ),
                        // Each run config as its own row — tap to launch that one.
                        listActions = runConfigNames.mapIndexed { index, name ->
                            ContextAction(QCodeIcon.Run, name) { onRunConfig(index) }
                        },
                    )
                }
            }
            // What this slot does is a setting (Settings → Appearance → Header): the terminal it has
            // always been, the Command Palette for anyone who reaches for that instead, or nothing
            // at all. The terminal stays reachable from the right drawer either way.
            when (LocalHeaderActionSetting.current.button) {
                HeaderActionButton.Terminal -> {
                    // Shimmers while any session is busy; a dot badge flags new background instances;
                    // long-press lists the live instances and opens the right drawer on the chosen one.
                    var terminalMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        WorkbenchIconActionButton(
                            icon = jcIcon(QCodeIcon.Terminal),
                            contentDescription = "Terminal",
                            onClick = onShowTerminal,
                            shimmer = terminalBusy,
                            badge = terminalHasUnseen,
                            onLongClick = { terminalMenuOpen = true },
                        )
                        CompactContextMenu(
                            expanded = terminalMenuOpen && terminalSessions.isNotEmpty(),
                            onDismissRequest = { terminalMenuOpen = false },
                            listActions = terminalSessions.map { session ->
                                ContextAction(QCodeIcon.Terminal, session.label) { onOpenTerminalSession(session.id) }
                            },
                        )
                    }
                }

                HeaderActionButton.CommandPalette -> {
                    val launcher = LocalCommandPaletteLauncher.current
                    WorkbenchIconActionButton(
                        icon = jcIcon(QCodeIcon.CommandPalette),
                        contentDescription = "Command Palette",
                        onClick = launcher.onOpen,
                    )
                }

                HeaderActionButton.Hidden -> Unit
            }
            // A docked panel is already on screen beside the editor with its own Close button, so
            // this toggle would only duplicate it. It returns as soon as the panel is hidden, and
            // stays put when the panel overlays the workspace instead of splitting it.
            if (!(rightSidebarDocked && canShowRightSidebar && rightSidebarVisible)) {
                WorkbenchIconActionButton(
                    icon = jcIcon(QCodeIcon.Logs),
                    contentDescription = "Toggle right sidebar",
                    onClick = onToggleRightSidebar,
                    active = canShowRightSidebar && rightSidebarVisible,
                )
            }
        }
        WorkbenchBreadcrumbRibbon(
            workspace = workspace,
            selectedProject = selectedProject,
            activeTab = activeTab,
        )
        }
        }
    }
}

/**
 * The thin path ribbon under the command deck: workspace › project › file, with the repeated tail
 * folded away. It scrolls horizontally instead of ellipsizing, so a deep path never loses its root.
 */
@Composable
private fun WorkbenchBreadcrumbRibbon(
    workspace: Workspace?,
    selectedProject: Project?,
    activeTab: EditorTab?,
) {
    val raw = listOfNotNull(workspace?.name, selectedProject?.name, activeTab?.title)
    val parts = raw.filterIndexed { index, value -> index == 0 || value != raw[index - 1] }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        if (parts.isEmpty()) {
            Text(
                text = "Editor workspace",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            parts.forEachIndexed { index, part ->
                if (index > 0) {
                    Icon(
                        painter = jcIcon(QCodeIcon.ChevronRight),
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                }
                Text(
                    text = part,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index == parts.lastIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** The launcher mark at chip size: an orange diamond threaded by a cream X. */
@Composable
private fun CorexMark(modifier: Modifier = Modifier) {
    val diamond = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val shape = Path().apply {
            moveTo(cx, 0f)
            lineTo(w, cy)
            lineTo(cx, h)
            lineTo(0f, cy)
            close()
        }
        drawPath(shape, diamond)
        val inset = w * 0.24f
        val stroke = w * 0.16f
        drawLine(CorexCream, Offset(inset, inset), Offset(w - inset, h - inset), stroke, StrokeCap.Round)
        drawLine(CorexCream, Offset(w - inset, inset), Offset(inset, h - inset), stroke, StrokeCap.Round)
    }
}

/** The brand's cream, which is not a semantic theme colour: the mark keeps its colour in every theme. */
private val CorexCream = Color(0xFFF4F0EC)
