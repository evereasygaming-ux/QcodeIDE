package dev.blamspot.jcode.feature.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import dev.blamspot.jcode.design.jcIcon
import dev.blamspot.jcode.design.QCodeIcon
import dev.blamspot.jcode.design.IconSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.CompactDestructiveButton
import dev.blamspot.jcode.design.CompactFilledButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.blamspot.jcode.design.QCodeTheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.blamspot.jcode.core.distro.Arch
import dev.blamspot.jcode.core.distro.DistroEnvironmentState
import dev.blamspot.jcode.core.distro.DistroProfile
import dev.blamspot.jcode.core.distro.DistroWizardProgress
import dev.blamspot.jcode.core.distro.EnvironmentInfo
import dev.blamspot.jcode.core.distro.WizardStepId
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space

object OnboardingFeature {

    @Composable
    fun FirstRunEnvironmentScreen(
        environmentState: DistroEnvironmentState,
        autoSetupProgress: DistroWizardProgress,
        onRefresh: () -> Unit,
        onSelectDistro: (DistroProfile) -> Unit,
        onAutoSetup: () -> Unit,
        onStorageAccessGranted: () -> Unit,
        onDismiss: (() -> Unit)? = null,
        onRestoreEnvironment: (() -> Unit)? = null,
        onImportMigration: (() -> Unit)? = null,
        migrationSummary: String? = null,
    ) {
        // Full-bleed backdrop first, insets padding inside: otherwise the workbench behind the
        // onboarding shows through the status/navigation-bar strips (visible in landscape).
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding(),
                contentAlignment = Alignment.TopCenter,
            ) {
                StepperScreen(
                    environmentState = environmentState,
                    autoSetupProgress = autoSetupProgress,
                    onSelectDistro = onSelectDistro,
                    onAutoSetup = onAutoSetup,
                    onRefresh = onRefresh,
                    onDismiss = onDismiss,
                    modifier = Modifier
                        .widthIn(max = 840.dp)
                        .fillMaxSize(),
                    shape = RoundedCornerShape(Radius.none),
                    showStorageStep = true,
                    onStorageAccessGranted = onStorageAccessGranted,
                    onRestoreEnvironment = onRestoreEnvironment,
                    onImportMigration = onImportMigration,
                    migrationSummary = migrationSummary,
                )
            }
        }
    }

    /**
     * Held over the workbench until the environment is usable.
     *
     * Editors, terminals and language servers are all backed by the distro, so letting them render
     * first means work can start against a rootfs nobody has resolved yet — that is how a terminal
     * opened during startup ended up bound to the catalog default instead of the active environment.
     * Blocking is the point: there is nothing useful to do here until the runtime answers.
     */
    @Composable
    fun StartupSplash(
        distroLabel: String,
        progress: DistroWizardProgress,
        modifier: Modifier = Modifier,
    ) {
        val running = progress as? DistroWizardProgress.Running
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = Space.xxl),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SetupBrandMark(size = 120.dp)
                Spacer(Modifier.height(Space.lg))
                Text(
                    text = "QCODE",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.height(Space.xxs))
                Text(
                    text = "IDE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 4.sp,
                )
                Spacer(Modifier.height(Space.xxl))
                // A running setup step is the more specific thing to say; otherwise the wait is the
                // environment being probed and started.
                Text(
                    text = running?.label ?: "Starting $distroLabel…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Space.md))
                SetupProgressBar(
                    percent = running?.progressPercent,
                    modifier = Modifier.fillMaxWidth(0.78f),
                )
                running?.progressDetail?.let { detail ->
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    /** Full-width environment setup, rendered as an in-editor page tab (replaces the cramped dialog). */
    @Composable
    fun EnvironmentSetupPage(
        environmentState: DistroEnvironmentState,
        autoSetupProgress: DistroWizardProgress,
        onRefresh: () -> Unit,
        onSelectDistro: (DistroProfile) -> Unit,
        onAutoSetup: () -> Unit,
    ) {
        val manager = LocalEnvironmentManager.current
        val context = LocalContext.current
        StepperScreen(
            environmentState = environmentState,
            autoSetupProgress = autoSetupProgress,
            onSelectDistro = onSelectDistro,
            onAutoSetup = onAutoSetup,
            onRefresh = onRefresh,
            onDismiss = null,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(Radius.none),
            // Surface the storage step here too so existing installs (which never see the
            // first-run screen again) still get a path to grant shared-storage access.
            showStorageStep = remember { !hasStorageAccess() },
            onStorageAccessGranted = manager.onStorageAccessGranted,
            installedEnvironments = manager.environments,
            onSwitchEnvironment = manager.onSwitch,
            onDeleteEnvironment = manager.onDelete,
        )
    }
}

@Composable
private fun StepperScreen(
    environmentState: DistroEnvironmentState,
    autoSetupProgress: DistroWizardProgress,
    onSelectDistro: (DistroProfile) -> Unit,
    onAutoSetup: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier,
    shape: RoundedCornerShape,
    showStorageStep: Boolean = false,
    onStorageAccessGranted: () -> Unit = {},
    onRestoreEnvironment: (() -> Unit)? = null,
    onImportMigration: (() -> Unit)? = null,
    migrationSummary: String? = null,
    installedEnvironments: List<EnvironmentInfo> = emptyList(),
    onSwitchEnvironment: (String) -> Unit = {},
    onDeleteEnvironment: (String) -> Unit = {},
) {
    val running = autoSetupProgress is DistroWizardProgress.Running
    val completed = autoSetupProgress is DistroWizardProgress.AllDone
    var logsExpanded by rememberSaveable { mutableStateOf(true) }

    // [autoSetupProgress] tracks a setup run in THIS session, not whether an environment exists — on
    // an ordinary launch it is Idle. Keying the wizard off it left the first-run steps on screen
    // forever, so a configured device showed "Choose the Linux distro QCODE IDE should prepare" and
    // "Waiting for you to choose a distro" directly under a card reporting one already active: two
    // pickers for one setting, disagreeing. Ask the environment itself instead, and once it is ready
    // leave [InstalledEnvironmentsCard] as the only control — the wizard returns on request, for
    // installing a distro that is not here yet.
    val configured = environmentState.smokeTestPassed == true && installedEnvironments.isNotEmpty()
    var addingEnvironment by rememberSaveable { mutableStateOf(false) }
    val showWizard = !configured || addingEnvironment || running || completed

    LaunchedEffect(running) {
        if (running) logsExpanded = true
    }

    val context = LocalContext.current
    // Storage grant gates distro selection: nothing can install into /QCODE IDE until it's granted.
    // When the storage step isn't shown (existing installs), there is nothing to gate on.
    var storageGranted by remember { mutableStateOf(hasStorageAccess()) }
    // The grant can also happen OUTSIDE the in-app dialog — the user flips File access in Android
    // Settings (before or during onboarding) and comes back. That path never fires the permission
    // launcher's callback, so re-check on every resume; otherwise Step 1 stays unchecked forever.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !storageGranted && hasStorageAccess()) {
                storageGranted = true
                onStorageAccessGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val distroStepEnabled = !showStorageStep || storageGranted

    val distroStepNumber = if (showStorageStep) 2 else 1
    // A bundle left by a previous install answers the same question this step asks — which
    // environment to set up — with the user's own, so it takes the step's place rather than adding
    // a fourth button to it. Choosing a fresh distro is still one tap away.
    var setUpFresh by remember { mutableStateOf(false) }
    val importing = onImportMigration != null && migrationSummary != null && !setUpFresh
    val idleLabel = if (importing) {
        "Waiting for you to start the import."
    } else {
        "Waiting for you to choose a distro."
    }
    val selectionSteps: LazyListScope.() -> Unit = {
        if (installedEnvironments.isNotEmpty()) {
            item {
                InstalledEnvironmentsCard(
                    environments = installedEnvironments,
                    enabled = !running,
                    onSwitch = onSwitchEnvironment,
                    onDelete = onDeleteEnvironment,
                )
            }
        }
        if (showStorageStep) {
            item {
                StorageAccessCard(
                    number = 1,
                    enabled = !running,
                    granted = storageGranted,
                    onGranted = {
                        storageGranted = true
                        onStorageAccessGranted()
                    },
                )
            }
        }
        if (showWizard) {
            item {
                if (importing) {
                    MigrationImportCard(
                        number = distroStepNumber,
                        summary = migrationSummary.orEmpty(),
                        running = running,
                        completed = completed,
                        enabled = distroStepEnabled,
                        onImport = { onImportMigration?.invoke() },
                        onSetUpFresh = { setUpFresh = true },
                    )
                } else {
                    DistroSelectionCard(
                        number = distroStepNumber,
                        environmentState = environmentState,
                        running = running,
                        completed = completed,
                        enabled = distroStepEnabled,
                        onSelectDistro = onSelectDistro,
                        onAutoSetup = onAutoSetup,
                        onRefresh = onRefresh,
                        onRestoreEnvironment = onRestoreEnvironment,
                    )
                }
            }
        } else {
            item {
                AddEnvironmentCard(onAdd = { addingEnvironment = true })
            }
        }
        item {
            WebEngineHintCard()
        }
    }

    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header()
            // Portrait: one scrolling column of steps. Landscape (wide + short): selection steps
            // on the left, the configure/log card as a full-height pane on the right, so the log
            // stays visible without scrolling past the other cards.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                // The right pane exists to keep the setup log visible; with no wizard there is no log,
                // and a half-empty split just strands the switcher in a narrow column.
                val twoPane = maxWidth > maxHeight && maxWidth >= 600.dp && showWizard
                if (twoPane) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentPadding = PaddingValues(Space.lg),
                            verticalArrangement = Arrangement.spacedBy(Space.lg),
                            content = selectionSteps,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(top = Space.lg, end = Space.lg, bottom = Space.lg),
                        ) {
                            ConfigureStepCard(
                                number = distroStepNumber + 1,
                                environmentState = environmentState,
                                autoSetupProgress = autoSetupProgress,
                                running = running,
                                completed = completed,
                                logsExpanded = logsExpanded,
                                onToggleLogs = { logsExpanded = !logsExpanded },
                                onDismiss = onDismiss,
                                idleLabel = idleLabel,
                                modifier = Modifier.weight(1f),
                                fillLog = true,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Space.lg),
                        verticalArrangement = Arrangement.spacedBy(Space.lg),
                    ) {
                        selectionSteps()
                        if (showWizard) {
                            item {
                                ConfigureStepCard(
                                    number = distroStepNumber + 1,
                                    environmentState = environmentState,
                                    autoSetupProgress = autoSetupProgress,
                                    running = running,
                                    completed = completed,
                                    logsExpanded = logsExpanded,
                                    onToggleLogs = { logsExpanded = !logsExpanded },
                                    onDismiss = onDismiss,
                                    idleLabel = idleLabel,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Stands in for the distro step when the previous install left a bundle in shared storage: it
 *  already holds an environment, so downloading a fresh one would throw the user's away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MigrationImportCard(
    number: Int,
    summary: String,
    running: Boolean,
    completed: Boolean,
    enabled: Boolean,
    onImport: () -> Unit,
    onSetUpFresh: () -> Unit,
) {
    val interactive = enabled && !running && !completed
    StepCard(
        number = number,
        active = interactive,
    ) {
        Text(
            text = when {
                completed -> "Your previous environment is set up."
                enabled -> "Your previous install left its Linux environment, projects, extensions " +
                    "and settings behind. Importing them puts QCODE IDE back where it was."
                else -> "Allow storage access above to continue."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            SetupButton(
                label = "Import",
                onClick = onImport,
                enabled = interactive,
                primary = true,
            )
            SetupButton(
                label = "Set up fresh instead",
                onClick = onSetUpFresh,
                enabled = interactive,
            )
        }
        if (interactive) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DistroSelectionCard(
    number: Int,
    environmentState: DistroEnvironmentState,
    running: Boolean,
    completed: Boolean,
    enabled: Boolean,
    onSelectDistro: (DistroProfile) -> Unit,
    onAutoSetup: () -> Unit,
    onRefresh: () -> Unit,
    onRestoreEnvironment: (() -> Unit)? = null,
) {
    // Interactive until the previous step (storage) is done; the whole card also locks while a setup
    // runs AND stays locked once it has succeeded (Step 3 done) — re-selecting/re-running from here
    // then makes no sense. A failed run leaves it interactive so the user can retry.
    val interactive = enabled && !running && !completed
    StepCard(
        number = number,
        active = interactive,
    ) {
        Text(
            text = when {
                completed -> "Environment ready — ${environmentState.runtime.selectedDistro.label} is set up."
                enabled -> "Choose the Linux distro QCODE IDE should prepare for your embedded environment."
                else -> "Allow storage access above to continue."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val availableDistros = environmentState.availableDistros.ifEmpty { DistroProfile.defaults() }
        // The distro picker is a deck of tiles, not a list of radio rows: each option shows what it
        // costs on disk, and the chosen one carries the accent so the choice survives a glance.
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            availableDistros.forEach { profile ->
                val selected = environmentState.runtime.selectedDistro == profile
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.xxl))
                        .clickable(enabled = interactive) { onSelectDistro(profile) },
                    shape = RoundedCornerShape(Radius.xxl),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.md, vertical = Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        SelectionDot(selected = selected, enabled = interactive)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(
                                text = profile.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${profile.arch.name.lowercase()} · ${profile.approxFootprint}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        // FlowRow so the "Install <distro>" + Refresh buttons wrap instead of squishing on portrait.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            SetupButton(
                label = "Install ${environmentState.runtime.selectedDistro.label}",
                onClick = onAutoSetup,
                enabled = interactive,
                primary = true,
            )
            if (onRestoreEnvironment != null) {
                SetupButton(
                    label = "Restore from backup…",
                    onClick = onRestoreEnvironment,
                    enabled = interactive,
                )
            }
            SetupButton(
                label = "Refresh",
                onClick = onRefresh,
                enabled = interactive,
            )
        }
        if (onRestoreEnvironment != null && interactive) {
            Text(
                text = "Or restore a .tar.gz backup into the selected distro — brings back its toolchains, VMs and files instead of a fresh download. Pick the distro that matches your backup first.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConfigureStepCard(
    number: Int,
    environmentState: DistroEnvironmentState,
    autoSetupProgress: DistroWizardProgress,
    running: Boolean,
    completed: Boolean,
    logsExpanded: Boolean,
    onToggleLogs: () -> Unit,
    onDismiss: (() -> Unit)?,
    idleLabel: String,
    modifier: Modifier = Modifier,
    fillLog: Boolean = false,
) {
    val failed = autoSetupProgress is DistroWizardProgress.Failed
    StepCard(
        number = number,
        active = running || completed,
        modifier = modifier,
        fillHeight = fillLog,
    ) {
        val progressText = when (autoSetupProgress) {
            is DistroWizardProgress.Running -> autoSetupProgress.label
            is DistroWizardProgress.Completed -> autoSetupProgress.detail
            is DistroWizardProgress.Failed -> autoSetupProgress.error
            is DistroWizardProgress.AllDone -> autoSetupProgress.summary
            DistroWizardProgress.Idle -> idleLabel
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SetupStatusGlyph(
                number = number,
                running = running,
                completed = completed,
                failed = failed,
            )
            Text(
                text = progressText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            SetupStatusChip(
                label = when {
                    running -> "LIVE"
                    completed -> "READY"
                    failed -> "FAILED"
                    else -> "IDLE"
                },
                accent = running || completed,
                failed = failed,
            )
        }

        val runningProgress = autoSetupProgress as? DistroWizardProgress.Running
        if (runningProgress != null &&
            (runningProgress.progressPercent != null || runningProgress.progressDetail != null)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                SetupProgressBar(
                    percent = runningProgress.progressPercent,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = runningProgress.progressDetail ?: "Working…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    runningProgress.progressPercent?.let { percent ->
                        Text(
                            text = "$percent%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        ActivityLogCard(
            activityLog = environmentState.activityLog,
            runningStep = environmentState.runningStep,
            expanded = logsExpanded,
            onToggle = onToggleLogs,
            modifier = if (fillLog && logsExpanded) Modifier.weight(1f) else Modifier,
            fixedLogHeight = !fillLog,
        )

        if (completed && onDismiss != null) {
            SetupButton(
                label = "Open the workbench",
                onClick = onDismiss,
                primary = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A hint, not a step: shown only when the device's WebView — the Chromium engine behind QCODE IDE's
 * browser and web previews — is old enough to break modern sites. Nothing here blocks setup, and
 * QCODE IDE works either way by falling back to the ROM's engine; the card exists because onboarding
 * is the one moment the user is already granting things, and a provider switch made now saves a
 * blank-page mystery later. Threshold and actions mirror the Settings → Web engine card.
 */
@Composable
private fun WebEngineHintCard() {
    val ctx = LocalContext.current
    val engineVersion = remember {
        runCatching { android.webkit.WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
    }
    val major = engineVersion?.substringBefore('.')?.toIntOrNull() ?: 0
    // Chromium 108 shipped dvh; below ~110 whole sites render blank rather than merely dated.
    if (major !in 1 until 110) return
    Surface(
        shape = RoundedCornerShape(Radius.sheet),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.ms),
        ) {
            Text("Browser engine is outdated", fontWeight = FontWeight.SemiBold)
            Text(
                text = "This device's WebView is Chromium $engineVersion, which modern sites can " +
                    "render blank or broken. QCODE IDE's built-in browser and web previews use it. " +
                    "Recommended: install the latest Android System WebView, then pick it under " +
                    "Developer options → WebView implementation. If your device doesn't allow the " +
                    "switch, QCODE IDE keeps working on the ROM's engine.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SetupButton(
                label = "Get latest WebView",
                primary = true,
                onClick = {
                    val id = "com.google.android.webview"
                    val play = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
                    runCatching { ctx.startActivity(play) }.onFailure {
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/store/apps/details?id=$id"),
                                ),
                            )
                        }
                    }
                },
            )
        }
    }
}

/** Reveals the setup wizard on a device that is already configured, for adding a second distro. */
@Composable
private fun AddEnvironmentCard(onAdd: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.sheet),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.ms),
        ) {
            Text("Add an environment", fontWeight = FontWeight.SemiBold)
            Text(
                text = "Install another Linux distro alongside the ones above. Each keeps its own SDKs " +
                    "and language servers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SetupButton(label = "Install another distro", onClick = onAdd, primary = true)
        }
    }
}

@Composable
private fun InstalledEnvironmentsCard(
    environments: List<EnvironmentInfo>,
    enabled: Boolean,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val hostArch = Arch.host()
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(
        shape = RoundedCornerShape(Radius.sheet),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.ms),
        ) {
            Text("Installed environments", fontWeight = FontWeight.SemiBold)
            Text(
                text = "Switch which environment terminals and builds target. SDKs and language servers stay " +
                    "installed per environment. Open terminals keep their original environment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                environments.forEach { env ->
                    EnvironmentRow(
                        env = env,
                        emulated = env.requiresEmulation(hostArch),
                        enabled = enabled,
                        canDelete = enabled && environments.size > 1,
                        onSwitch = { onSwitch(env.id) },
                        onDelete = { pendingDelete = env.id },
                    )
                }
            }
        }
    }

    val deleteId = pendingDelete
    if (deleteId != null) {
        val target = environments.firstOrNull { it.id == deleteId }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove environment") },
            text = {
                Text(
                    "Remove ${target?.label ?: deleteId}? Its rootfs and everything installed inside it " +
                        "(SDKs, language servers, packages) will be deleted. This cannot be undone.",
                )
            },
            // Destructive "Remove" sits away from the rightmost (reflexive-tap) slot, which Material
            // gives to the dismiss button.
            confirmButton = {
                CompactDestructiveButton(text = "Remove", onClick = {
                    onDelete(deleteId)
                    pendingDelete = null
                })
            },
            dismissButton = {
                CompactFilledButton(text = "Cancel", onClick = { pendingDelete = null })
            },
        )
    }
}

@Composable
private fun EnvironmentRow(
    env: EnvironmentInfo,
    emulated: Boolean,
    enabled: Boolean,
    canDelete: Boolean,
    onSwitch: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Radius.xxl),
        color = if (env.isActive) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            Color.Transparent
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.xxl))
                .clickable(enabled = enabled && !env.isActive, onClick = onSwitch)
                .padding(horizontal = Space.sm, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            SelectionDot(
                selected = env.isActive,
                enabled = enabled && !env.isActive,
                onClick = onSwitch,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
            ) {
                Text(
                    env.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(if (emulated) "Emulated (QEMU)" else "Native")
                        if (env.isActive) append(" · active")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (env.isActive) {
                        QCodeTheme.semanticColors.success
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.pill))
                    .clickable(enabled = canDelete, onClick = onDelete),
                shape = RoundedCornerShape(Radius.pill),
                color = if (canDelete) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
                } else {
                    Color.Transparent
                },
            ) {
                Text(
                    text = "Remove",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = if (canDelete) 1f else 0.4f),
                    modifier = Modifier.padding(horizontal = Space.ms, vertical = Space.xs),
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.lg),
        horizontalArrangement = Arrangement.spacedBy(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(Radius.xxl),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                SetupBrandMark(size = 46.dp)
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Space.xxs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Text(
                    text = "QCODE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                )
                Text(
                    text = "IDE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                )
            }
            Text(
                text = "Set up this device",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "One Linux environment powers the editor, terminal and agents. " +
                    "Pick a distro and QCODE prepares the rest.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// /QCODE IDE lives on shared storage; at targetSdk 33 the only grant that gives raw file-path access
// to it is "All files access" (MANAGE_EXTERNAL_STORAGE) — a system-settings toggle, not a runtime
// permission dialog.
private fun hasStorageAccess(): Boolean = android.os.Environment.isExternalStorageManager()

/** Runtime storage grant used to read older shared-storage projects (one-time migration). */
@Composable
private fun StorageAccessCard(
    number: Int,
    enabled: Boolean,
    granted: Boolean,
    onGranted: () -> Unit,
) {
    val context = LocalContext.current
    var deniedOnce by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val now = hasStorageAccess()
        if (now && !granted) onGranted()
        if (!now) deniedOnce = true
    }
    StepCard(
        number = number,
        active = !granted,
    ) {
        Text(
            text = "Lets QCODE IDE migrate projects created by older versions from the shared /QCODE IDE " +
                "folder, and share your phone storage with the Linux environment — the guest mounts it " +
                "at /sdcard, so agents and the terminal can read and write files outside the workspace. " +
                "Projects now live in app storage — browse them via the \"QCODE IDE Projects\" " +
                "entry in your Files app, or export them from the workspace menu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (granted) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.ms),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✓", color = QCodeTheme.semanticColors.success, fontWeight = FontWeight.Bold)
                Text("Storage access granted", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            SetupButton(
                label = "Allow \"All files access\"",
                enabled = enabled,
                primary = true,
                onClick = {
                    val packageUri = Uri.fromParts("package", context.packageName, null)
                    try {
                        launcher.launch(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
                        )
                    } catch (_: ActivityNotFoundException) {
                        launcher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                },
            )
            if (deniedOnce) {
                Text(
                    text = "Without it, projects fall back to app-private storage and are removed " +
                        "when the app is uninstalled. In the settings screen that opens, turn on " +
                        "\"Allow access to manage all files\" for QCODE IDE, then return here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    active: Boolean,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.sheet),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (active) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
                .padding(Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.md),
            verticalAlignment = Alignment.Top,
        ) {
            // The step badge: a filled accent disc only while the step is the one in play.
            Surface(
                shape = CircleShape,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                },
                border = if (active) {
                    null
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
                },
            ) {
                Box(
                    modifier = Modifier.size(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (active) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
                content = content,
            )
        }
    }
}

@Composable
private fun ActivityLogCard(
    activityLog: List<String>,
    runningStep: WizardStepId?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    fixedLogHeight: Boolean = true,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.xxl),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!fixedLogHeight && expanded) Modifier.fillMaxHeight() else Modifier),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.md, vertical = Space.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    ConsoleDots()
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "SETUP LOG",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp,
                        )
                        Text(
                            text = runningStep?.key ?: "waiting for progress",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.pill))
                        .clickable(onClick = onToggle),
                    shape = RoundedCornerShape(Radius.pill),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
                ) {
                    Text(
                        text = if (expanded) "Hide" else "Show",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.ms, vertical = Space.xs),
                    )
                }
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f))
                val listState = rememberLazyListState()
                LaunchedEffect(activityLog.size) {
                    if (activityLog.isNotEmpty()) {
                        listState.animateScrollToItem(activityLog.size - 1)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (fixedLogHeight) Modifier.height(320.dp) else Modifier.weight(1f))
                        .padding(horizontal = Space.md, vertical = Space.sm),
                    verticalArrangement = Arrangement.spacedBy(Space.xxs),
                ) {
                    if (activityLog.isEmpty()) {
                        item {
                            LogLine("setup has not started yet")
                        }
                    } else {
                        items(activityLog) { line ->
                            LogLine(line)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsoleDots() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.outlineVariant,
        ).forEach { dot ->
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
        }
    }
}

@Composable
private fun LogLine(text: String) {
    val failed = text.contains("failed", ignoreCase = true) || text.contains("error", ignoreCase = true)
    val succeeded = !failed && (
        text.contains("done", ignoreCase = true) ||
            text.contains("ready", ignoreCase = true) ||
            text.contains("installed", ignoreCase = true) ||
            text.contains("success", ignoreCase = true)
        )
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(
            text = "\$",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = when {
                failed -> MaterialTheme.colorScheme.error
                succeeded -> QCodeTheme.semanticColors.success
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f)
            },
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- Setup deck primitives -------------------------------------------------------------------

/**
 * The app's own mark for the setup screens: the launcher icon's foreground layer at [size], so
 * onboarding shows the same `<Q/>` the launcher does. Decoding is keyed on the size and cached, and
 * a missing icon simply draws nothing rather than failing the first-run gate.
 */
@Composable
private fun SetupBrandMark(size: Dp) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val mark = remember(context, px) {
        runCatching {
            val icon = context.packageManager.getApplicationIcon(context.packageName)
            val layer = (icon as? android.graphics.drawable.AdaptiveIconDrawable)?.foreground ?: icon
            val bitmap = android.graphics.Bitmap.createBitmap(
                px,
                px,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            layer.setBounds(0, 0, px, px)
            layer.draw(android.graphics.Canvas(bitmap))
            bitmap.asImageBitmap()
        }.getOrNull()
    }
    if (mark != null) {
        Image(bitmap = mark, contentDescription = null, modifier = Modifier.size(size))
    }
}

/**
 * A setup action as a pill: the accent-filled primary choice, or a quiet secondary one. Built here
 * rather than from the Material buttons so every action on the first-run deck shares one height and
 * one corner.
 */
@Composable
private fun SetupButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    icon: QCodeIcon? = null,
) {
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f)
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
    }
    val content = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(Radius.pill),
        color = container,
        contentColor = content,
        border = if (!primary && enabled) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier
                .height(42.dp)
                .padding(horizontal = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            if (icon != null) {
                Icon(
                    painter = jcIcon(icon),
                    contentDescription = null,
                    modifier = Modifier.size(IconSize.sm),
                    tint = content,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The install bar: 6dp, rounded, accent-filled, with a static track for the indeterminate case. */
@Composable
private fun SetupProgressBar(percent: Int?, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val color = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(Radius.pill)
    if (percent != null) {
        LinearProgressIndicator(
            progress = { (percent / 100f).coerceIn(0f, 1f) },
            modifier = modifier
                .height(6.dp)
                .clip(shape),
            color = color,
            trackColor = track,
            strokeCap = StrokeCap.Round,
        )
    } else {
        LinearProgressIndicator(
            modifier = modifier
                .height(6.dp)
                .clip(shape),
            color = color,
            trackColor = track,
            strokeCap = StrokeCap.Round,
        )
    }
}

/** The leading glyph of the configure step's status row. */
@Composable
private fun SetupStatusGlyph(
    number: Int,
    running: Boolean,
    completed: Boolean,
    failed: Boolean,
) {
    Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        when {
            running -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            completed -> Text(
                text = "✓",
                color = QCodeTheme.semanticColors.success,
                fontWeight = FontWeight.Bold,
            )
            failed -> Text(
                text = "✕",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
            else -> Text(
                text = number.toString(),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** LIVE / READY / FAILED / IDLE, right-aligned in the configure step's status row. */
@Composable
private fun SetupStatusChip(label: String, accent: Boolean, failed: Boolean) {
    val container = when {
        failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        accent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
    }
    val content = when {
        failed -> MaterialTheme.colorScheme.error
        accent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(Radius.pill), color = container) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(horizontal = Space.s, vertical = 2.dp),
        )
    }
}

/** A radio dot without Material's control size: the distro and environment rows draw their own. */
@Composable
private fun SelectionDot(
    selected: Boolean,
    enabled: Boolean,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .border(
                1.5.dp,
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                },
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
