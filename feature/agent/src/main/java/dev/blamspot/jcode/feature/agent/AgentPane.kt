package dev.blamspot.jcode.feature.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import dev.blamspot.jcode.core.agent.AgentEvent
import dev.blamspot.jcode.core.agent.AgentKind
import dev.blamspot.jcode.core.agent.AgentMessage
import dev.blamspot.jcode.core.agent.AgentModel
import dev.blamspot.jcode.core.agent.AgentSetupRequirement
import dev.blamspot.jcode.core.agent.AgentRole
import dev.blamspot.jcode.core.agent.AgentToolCall
import dev.blamspot.jcode.core.agent.AgentToolStatus

/** Stateful entry point: binds an [AgentController] to the stateless pane. */
@Composable
fun AgentPane(controller: AgentController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    AgentPane(
        state = state,
        onSend = { prompt -> controller.send(prompt) },
        onStop = controller::stop,
        onApprove = controller::approvePending,
        onApproveAlways = controller::approvePendingAlways,
        onToggleAutoApprove = controller::setAutoApproveTools,
        onSelectModel = controller::selectModel,
        onSelectKind = controller::selectKind,
        onNewChat = controller::clearChat,
        modifier = modifier,
        onRunSetup = controller::runSetup,
        onDismissSetup = controller::dismissSetup,
        onSubmitSetupInput = controller::submitSetupInput,
        onAddAttachments = { uris -> controller.addAttachments(uris) },
        onRemoveAttachment = controller::removeAttachment,
    )
}

@Composable
fun AgentPane(
    state: AgentUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApprove: (Boolean) -> Unit,
    onApproveAlways: (() -> Unit)? = null,
    onToggleAutoApprove: ((Boolean) -> Unit)? = null,
    onSelectModel: (String) -> Unit,
    onSelectKind: (AgentKind) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
    onRunSetup: (() -> Unit)? = null,
    onDismissSetup: (() -> Unit)? = null,
    onSubmitSetupInput: ((String) -> Unit)? = null,
    onAddAttachments: (List<Uri>) -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text, state.messages.lastOrNull()?.thinking) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
    ) {
        AgentHeader(
            state = state,
            onSelectKind = onSelectKind,
            onSelectModel = onSelectModel,
            onNewChat = onNewChat,
            onToggleAutoApprove = onToggleAutoApprove,
        )
        GradientRule()

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.messages.isEmpty()) {
                item("empty") { EmptyHint(onSend = onSend) }
            }
            items(state.messages, key = { it.id }) { message ->
                when (message.role) {
                    AgentRole.USER -> UserMessage(message)
                    AgentRole.ASSISTANT -> AssistantMessage(message)
                    AgentRole.TOOL -> message.toolCall?.let { ToolCard(it) }
                }
            }
            state.error?.let { error ->
                item("error") { ErrorNotice(error) }
            }
        }

        state.pendingApproval?.let { approval -> ApprovalCard(approval, onApprove, onApproveAlways) }

        // A backend that cannot run yet says so here, with the button that fixes it, rather than
        // letting every prompt fail with a protocol error the user cannot act on.
        state.setupRequirement?.let { requirement ->
            SetupCard(
                requirement = requirement,
                busy = state.setupBusy,
                needsInput = state.setupNeedsInput,
                output = state.setupOutput,
                canRun = onRunSetup != null && requirement.installCommand != null,
                onRun = onRunSetup ?: {},
                onSubmitInput = onSubmitSetupInput,
                onDismiss = onDismissSetup ?: {},
            )
        }

        AgentComposer(
            streaming = state.isStreaming,
            attachments = state.pendingAttachments,
            autoApprove = state.autoApproveTools,
            onSend = onSend,
            onStop = onStop,
            onNewChat = onNewChat,
            onToggleAutoApprove = onToggleAutoApprove,
            onAddAttachments = onAddAttachments,
            onRemoveAttachment = onRemoveAttachment,
        )
    }
}

@Composable
private fun AgentHeader(
    state: AgentUiState,
    onSelectKind: (AgentKind) -> Unit,
    onSelectModel: (String) -> Unit,
    onNewChat: () -> Unit,
    onToggleAutoApprove: ((Boolean) -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Text(
                    text = "AGENT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                )
            }
            Spacer(Modifier.weight(1f))
            if (onToggleAutoApprove != null) {
                AutoApproveChip(
                    enabled = state.autoApproveTools,
                    onToggle = { onToggleAutoApprove(!state.autoApproveTools) },
                )
            }
            if (state.inputTokens > 0 || state.outputTokens > 0) {
                Text(
                    text = "${compact(state.inputTokens)} in · ${compact(state.outputTokens)} out",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.messages.isNotEmpty() && !state.isStreaming) {
                TextButton(onClick = onNewChat) { Text("New") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderPicker(state.providers, state.kind, onSelectKind)
            Spacer(Modifier.weight(1f))
            ModelPicker(state.models, state.model, onSelectModel)
        }
    }
}

/**
 * The "run tools without asking" switch.
 *
 * Long agent runs (a video pipeline, a wide refactor) ask for the same tool over and over; this
 * badge turns the prompting off for the session, and stays visible while it is on so nothing runs
 * unattended by accident.
 */
@Composable
private fun AutoApproveChip(enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            )
            .border(
                1.dp,
                if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    CircleShape,
                ),
        )
        Text(
            text = "AUTO",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.9.sp,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A hairline that fades out from the CoreX accent instead of a flat divider. */
@Composable
private fun GradientRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

@Composable
private fun ProviderPicker(
    providers: List<AgentKind>,
    selected: AgentKind,
    onSelect: (AgentKind) -> Unit,
) {
    if (providers.size <= 1) {
        Text(
            text = selected.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    RoundedCornerShape(999.dp),
                )
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = selected.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "▾",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            providers.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.label) },
                    onClick = {
                        onSelect(provider)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelPicker(
    models: List<AgentModel>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = models.firstOrNull { it.id == selected }?.label ?: selected
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    RoundedCornerShape(999.dp),
                )
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "▾",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (models.ifEmpty { listOf(AgentModel("default", "Default")) }).forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.label) },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * The "this backend cannot run yet" card: what is missing, the command that fixes it, and the
 * terminal output while it runs. Kept distinct from an error — nothing failed, the CLI is absent.
 *
 * The agy sign-in is interactive: the CLI prints a long authorization URL and then reads a code
 * from stdin. The output is selectable and the URL also gets explicit Open/Copy actions, because
 * selecting a wrapped URL on a phone is exactly the moment it stops being possible.
 */
@Composable
private fun SetupCard(
    requirement: AgentSetupRequirement,
    busy: Boolean,
    needsInput: Boolean,
    output: String?,
    canRun: Boolean,
    onRun: () -> Unit,
    onSubmitInput: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var code by remember { mutableStateOf("") }
    val link = remember(output) {
        output?.let { LOG_URL.find(it)?.value }
            ?.trimEnd('.', ',', ')', '"', '\'')
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        border = BorderStroke(
            1.dp,
            if (busy) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = requirement.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    Text(
                        text = if (needsInput) "Waiting for code" else "Working…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = requirement.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            requirement.hint?.let { hint ->
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            output?.takeIf { it.isNotBlank() }?.let { log ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.75f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                ) {
                    SelectionContainer {
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .heightIn(max = 170.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                        )
                    }
                }
            }
            if (link != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { uriHandler.openUri(link) }) { Text("Open link") }
                    TextButton(
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("Sign-in link", link))
                        },
                    ) { Text("Copy link") }
                }
            }
            if (needsInput && onSubmitInput != null) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(requirement.inputLabel) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 1,
                    maxLines = 3,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { onSubmitInput(code); code = "" }, enabled = code.isNotBlank()) {
                    Text("Submit code")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRun) {
                    Button(onClick = onRun, enabled = !busy) {
                        Text(if (busy) "Running…" else requirement.installLabel)
                    }
                }
                if (!busy || needsInput) {
                    TextButton(onClick = onDismiss) {
                        Text(if (busy) "Cancel" else if (canRun) "Dismiss" else "Close")
                    }
                }
            }
        }
    }
}

/** The first http(s) URL in a setup log; the agy authorization link is the only one it prints. */
private val LOG_URL = Regex("https?://\\S+")

@Composable
private fun EmptyHint(onSend: (String) -> Unit) {
    val suggestions = listOf(
        "Explain this project" to "Explain this project and how it is structured.",
        "Fix the failing build" to "Find why the build fails and fix it.",
        "Write a test" to "Write a test for the code in the editor.",
        "Review my changes" to "Review my uncommitted changes and point out risks.",
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoreXMark(size = 46.dp)
        Spacer(Modifier.height(14.dp))
        Text(
            text = "How can I help?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = "CoreX runs inside your Linux environment. File edits and commands ask first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        Spacer(Modifier.height(20.dp))
        suggestions.forEach { (label, prompt) ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSend(prompt) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "↗",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun UserMessage(message: AgentMessage) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 5.dp, bottomStart = 18.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary,
                        ),
                    ),
                )
                .padding(horizontal = 13.dp, vertical = 9.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun AssistantMessage(message: AgentMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        CoreXMark(size = 22.dp, modifier = Modifier.padding(top = 3.dp))
        Column(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
                border = BorderStroke(
                    1.dp,
                    if (message.isStreaming) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    if (message.thinking.isNotBlank()) {
                        ReasoningBlock(message.thinking)
                    }
                    when {
                        message.text.isNotBlank() -> SelectionContainer {
                            MarkdownBody(message.text)
                        }
                        message.isStreaming -> ThinkingDots()
                    }
                }
            }
        }
    }
}

/** The CoreX diamond, drawn rather than shipped as a bitmap so it stays crisp at any size. */
@Composable
private fun CoreXMark(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val cross = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f)
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val diamond = Path().apply {
            moveTo(w / 2f, 0f)
            lineTo(w, h / 2f)
            lineTo(w / 2f, h)
            lineTo(0f, h / 2f)
            close()
        }
        drawPath(diamond, color = accent, style = Stroke(width = 1.4.dp.toPx()))
        val cx = w / 2f
        val cy = h / 2f
        val r = w * 0.17f
        val stroke = 1.8.dp.toPx()
        drawLine(cross, Offset(cx - r, cy - r), Offset(cx + r, cy + r), stroke, StrokeCap.Round)
        drawLine(cross, Offset(cx + r, cy - r), Offset(cx - r, cy + r), stroke, StrokeCap.Round)
    }
}

@Composable
private fun ReasoningBlock(thinking: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Text(
                text = "REASONING",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = thinking,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "thinking-alpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .alpha(if (index == 2) pulse else 0.55f)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        Text(
            text = "Thinking…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Markdown ---------------------------------------------------------------------------------

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val text: String, val ordered: Boolean, val number: Int?) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Table(val rows: List<List<String>>) : MdBlock
}

private val HEADING = Regex("^(#{1,4})\\s+(.*)$")
private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
private val ORDERED = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
private val TABLE_SEPARATOR_CELL = Regex("^:?-{2,}:?$")

/**
 * Small, dependency-free markdown reader for agent output. It covers the blocks the CLI actually
 * emits — headings, bullets, fenced code and pipes-and-dashes tables — and leaves anything else as
 * a paragraph, so no syntax ever gets eaten.
 */
private fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.isBlank()) {
            i++
            continue
        }
        if (line.trimStart().startsWith("```")) {
            val language = line.trim().removePrefix("```").trim()
            val body = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                body += lines[i]
                i++
            }
            if (i < lines.size) i++
            blocks += MdBlock.Code(language, body.joinToString("\n"))
            continue
        }
        HEADING.find(line)?.let { match ->
            blocks += MdBlock.Heading(match.groupValues[1].length, match.groupValues[2].trim())
            i++
            continue
        }
        if (line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            val rows = mutableListOf(splitTableRow(line))
            i += 2
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                rows += splitTableRow(lines[i])
                i++
            }
            blocks += MdBlock.Table(rows)
            continue
        }
        BULLET.find(line)?.let { match ->
            blocks += MdBlock.Bullet(match.groupValues[1].trim(), ordered = false, number = null)
            i++
            continue
        }
        ORDERED.find(line)?.let { match ->
            blocks += MdBlock.Bullet(
                text = match.groupValues[2].trim(),
                ordered = true,
                number = match.groupValues[1].toIntOrNull(),
            )
            i++
            continue
        }
        val paragraph = mutableListOf(line.trim())
        i++
        while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines[i])) {
            paragraph += lines[i].trim()
            i++
        }
        blocks += MdBlock.Paragraph(paragraph.joinToString(" "))
    }
    return blocks
}

private fun startsBlock(line: String): Boolean =
    line.trimStart().startsWith("```") ||
        HEADING.matches(line) ||
        BULLET.matches(line) ||
        ORDERED.matches(line) ||
        (line.contains('|') && isTableSeparator(line))

private fun splitTableRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

private fun isTableSeparator(line: String): Boolean {
    val cells = splitTableRow(line)
    return cells.isNotEmpty() && cells.all { TABLE_SEPARATOR_CELL.matches(it) }
}

@Composable
private fun MarkdownBody(markdown: String) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> HeadingBlock(block)
                is MdBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                is MdBlock.Bullet -> BulletBlock(block)
                is MdBlock.Code -> CodeBlock(block)
                is MdBlock.Table -> TableBlock(block)
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (block.level <= 2) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(14.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        }
        Text(
            text = inlineMarkdown(block.text),
            style = when (block.level) {
                1 -> MaterialTheme.typography.titleMedium
                2 -> MaterialTheme.typography.titleSmall
                else -> MaterialTheme.typography.bodyLarge
            },
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun BulletBlock(block: MdBlock.Bullet) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (block.ordered) "${block.number ?: 1}." else "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = inlineMarkdown(block.text),
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.72f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f),
                RoundedCornerShape(11.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
                .padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language.ifBlank { "code" }.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 0.9.sp,
            )
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Code", block.code))
                },
            ) {
                Text("Copy", style = MaterialTheme.typography.labelSmall)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            Text(
                text = block.code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun TableBlock(block: MdBlock.Table) {
    val columns = block.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f),
                RoundedCornerShape(11.dp),
            ),
    ) {
        block.rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (rowIndex == 0) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (column in 0 until columns) {
                    Text(
                        text = inlineMarkdown(row.getOrElse(column) { "" }),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        ),
                        fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (rowIndex == 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private val INLINE = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`|\\[[^\\]]+\\]\\([^)]+\\)|\\*[^*\\n]+\\*)")

/** Inline markdown: bold, code spans, links and italics — the rest is left byte-for-byte alone. */
@Composable
private fun inlineMarkdown(raw: String): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.primary
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(raw, codeColor, linkColor) {
        buildAnnotatedString {
            var last = 0
            INLINE.findAll(raw).forEach { match ->
                append(raw.substring(last, match.range.first))
                val token = match.value
                when {
                    token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(token.removeSurrounding("**"))
                    }
                    token.startsWith("`") -> withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            color = codeColor,
                            background = codeColor.copy(alpha = 0.12f),
                        ),
                    ) {
                        append(token.trim('`'))
                    }
                    token.startsWith("[") -> {
                        val label = token.substringAfter('[').substringBefore(']')
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        ) { append(label) }
                    }
                    else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(token.trim('*'))
                    }
                }
                last = match.range.last + 1
            }
            append(raw.substring(last))
        }
    }
}

// --- Tool / approval / errors -----------------------------------------------------------------

@Composable
private fun ToolCard(call: AgentToolCall) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.20f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "›_",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = displayToolName(call.name),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(call.status)
            }
            if (call.inputJson.isNotBlank() && call.inputJson != "{}") {
                Spacer(Modifier.height(7.dp))
                Text(
                    text = clip(call.inputJson, 600),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            call.output?.takeIf { it.isNotBlank() }?.let { output ->
                Spacer(Modifier.height(7.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Spacer(Modifier.height(7.dp))
                Text(
                    text = clip(output, 1500),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                    color = if (call.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusPill(status: AgentToolStatus) {
    val color = statusColor(status)
    Text(
        text = status.name.lowercase().replaceFirstChar { it.uppercase() },
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun ErrorNotice(error: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(11.dp),
        )
    }
}

@Composable
private fun ApprovalCard(
    approval: AgentEvent.PermissionRequest,
    onApprove: (Boolean) -> Unit,
    onApproveAlways: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Allow ${displayToolName(approval.call.name)}?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (approval.call.inputJson.isNotBlank() && approval.call.inputJson != "{}") {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = clip(approval.call.inputJson, 500),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onApprove(false) }) { Text("Deny") }
                if (onApproveAlways != null) {
                    TextButton(onClick = onApproveAlways) { Text("Allow always") }
                }
                Spacer(Modifier.width(4.dp))
                Button(onClick = { onApprove(true) }) { Text("Allow") }
            }
        }
    }
}

/**
 * The name a tool is shown under.
 *
 * The Flow bridge's tools keep their `deepx_` ids — that is the extension's ABI and cannot change
 * — but the product is CoreX, so the pane shows "CoreX · generate_image" instead of leaking the
 * old name into every tool card.
 */
private fun displayToolName(raw: String): String {
    var name = raw
    if (name.startsWith("mcp_")) {
        // "mcp_<server>_<tool>" — the server segment goes away, the tool id stays.
        name = name.removePrefix("mcp_").substringAfter('_', "")
    }
    if (name.startsWith("deepx_")) {
        val verb = name.removePrefix("deepx_")
        return if (verb.isBlank()) "CoreX" else "CoreX · $verb"
    }
    return name
}

/**
 * The command list the composer suggests on a leading `/`.
 *
 * `local` commands are handled by the app (clear/stop/auto/attach); the rest are typed through to
 * the CLI, which owns their real grammar — an unknown one comes back with the CLI's own error
 * instead of the app pretending to know the protocol.
 */
private data class AgentCommand(val name: String, val summary: String, val local: Boolean = false)

private val AGENT_COMMANDS = listOf(
    AgentCommand("/help", "What the agent can do — the CLI answers"),
    AgentCommand("/init", "Scan the project and write AGENTS.md"),
    AgentCommand("/compact", "Compress the session history to free context"),
    AgentCommand("/model", "Show or switch the model"),
    AgentCommand("/stats", "Token usage for this session"),
    AgentCommand("/share", "Export this chat"),
    AgentCommand("/clear", "Start a fresh chat", local = true),
    AgentCommand("/stop", "Stop the running turn", local = true),
    AgentCommand("/auto", "Toggle tool auto-approval", local = true),
    AgentCommand("/attach", "Attach a file, image or document", local = true),
)

@Composable
private fun AgentComposer(
    streaming: Boolean,
    attachments: List<AgentAttachment>,
    autoApprove: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onToggleAutoApprove: ((Boolean) -> Unit)?,
    onAddAttachments: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val enabled = input.isNotBlank()
    // The document picker: any mime, multiple files. The controller copies each pick into the
    // agent-visible uploads folder; the chips below appear as the copies land.
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) onAddAttachments(uris) }

    // The `/` menu: shows while the input looks like a command name being typed.
    val query = input.trim().substringBefore(' ')
    val commandMatches = if (query.startsWith("/") && query.length <= 16) {
        AGENT_COMMANDS.filter { it.name.startsWith(query, ignoreCase = true) }
    } else {
        emptyList()
    }

    Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)) {
        if (attachments.isNotEmpty()) {
            // Staged attachments: one removable chip each, above the input so they read as part
            // of the next message, not as decoration.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                attachments.forEach { attachment ->
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 9.dp, end = 3.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = (if (attachment.isImage) "🖼 " else "📄 ") + attachment.name,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                modifier = Modifier.widthIn(max = 170.dp),
                            )
                            Text(
                                text = "✕",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { onRemoveAttachment(attachment.id) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            border = BorderStroke(
                1.dp,
                if (enabled || streaming) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                },
            ),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "›",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f).padding(vertical = 7.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) {
                                Text(
                                    text = "Message CoreX…  (/ commands · 📎 attach)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(6.dp))
                // Attach: the + opens the phone's document picker (files, images, docs).
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                        .clickable { pickLauncher.launch(arrayOf("*/*")) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "＋",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (streaming || enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .clickable(enabled = streaming || enabled) {
                            if (streaming) {
                                onStop()
                            } else {
                                val text = input.trim()
                                if (text.isNotEmpty()) {
                                    onSend(text)
                                    input = ""
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (streaming) "■" else "↑",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (streaming || enabled) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        if (commandMatches.isNotEmpty()) {
            // The command menu: rises above the composer while a `/name` is being typed.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    commandMatches.forEach { command ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    when {
                                        command.name == "/clear" -> {
                                            input = ""
                                            onNewChat()
                                        }
                                        command.name == "/stop" -> {
                                            input = ""
                                            onStop()
                                        }
                                        command.name == "/auto" && onToggleAutoApprove != null -> {
                                            input = ""
                                            onToggleAutoApprove(!autoApprove)
                                        }
                                        command.name == "/attach" -> {
                                            input = ""
                                            pickLauncher.launch(arrayOf("*/*"))
                                        }
                                        else -> {
                                            // Pass-through: hand the command to the CLI, which
                                            // owns its arguments and grammar.
                                            input = "${command.name} "
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = command.name,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = if (command.local) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = command.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = if (command.local) "APP" else "CLI",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                letterSpacing = 0.8.sp,
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "Enter sends · / for commands · ＋ attaches files & images",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.padding(start = 16.dp, top = 5.dp),
        )
    }
}

@Composable
private fun statusColor(status: AgentToolStatus): Color = when (status) {
    AgentToolStatus.PENDING -> MaterialTheme.colorScheme.tertiary
    AgentToolStatus.APPROVED, AgentToolStatus.RUNNING -> MaterialTheme.colorScheme.primary
    AgentToolStatus.DENIED, AgentToolStatus.FAILED -> MaterialTheme.colorScheme.error
    AgentToolStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
}

private fun clip(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max) + "…"

private fun compact(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> "${(value / 100) / 10.0}k"
    else -> "${(value / 100_000) / 10.0}M"
}
