package dev.blamspot.jcode.feature.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.core.agent.AgentEvent
import dev.blamspot.jcode.core.agent.AgentMessage
import dev.blamspot.jcode.core.agent.AgentModel
import dev.blamspot.jcode.core.agent.AgentRole
import dev.blamspot.jcode.core.agent.AgentToolCall
import dev.blamspot.jcode.core.agent.AgentToolStatus

/** Stateful entry point: binds an [AgentController] to the stateless pane. */
@Composable
fun AgentPane(controller: AgentController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    AgentPane(
        state = state,
        onSend = controller::send,
        onStop = controller::stop,
        onApprove = controller::approvePending,
        onSelectModel = controller::selectModel,
        onNewChat = controller::clearChat,
        modifier = modifier,
    )
}

@Composable
fun AgentPane(
    state: AgentUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApprove: (Boolean) -> Unit,
    onSelectModel: (String) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text, state.messages.lastOrNull()?.thinking) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(modifier.fillMaxSize()) {
        AgentHeader(state = state, onSelectModel = onSelectModel, onNewChat = onNewChat)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.messages.isEmpty()) {
                item("empty") { EmptyHint() }
            }
            items(state.messages, key = { it.id }) { message ->
                when (message.role) {
                    AgentRole.USER -> UserMessage(message)
                    AgentRole.ASSISTANT -> AssistantMessage(message)
                    AgentRole.TOOL -> message.toolCall?.let { ToolCard(it) }
                }
            }
            state.error?.let { error ->
                item("error") {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        state.pendingApproval?.let { approval -> ApprovalCard(approval, onApprove) }

        AgentComposer(
            streaming = state.isStreaming,
            onSend = onSend,
            onStop = onStop,
        )
    }
}

@Composable
private fun AgentHeader(
    state: AgentUiState,
    onSelectModel: (String) -> Unit,
    onNewChat: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Agent",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = state.kind.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (state.inputTokens > 0 || state.outputTokens > 0) {
            Text(
                text = "${compact(state.inputTokens)} in · ${compact(state.outputTokens)} out",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
        }
        if (state.messages.isNotEmpty() && !state.isStreaming) {
            TextButton(onClick = onNewChat) { Text("New") }
        }
        ModelPicker(state.models, state.model, onSelectModel)
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
        TextButton(onClick = { expanded = true }) { Text(label) }
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

@Composable
private fun EmptyHint() {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Text(
            text = "Ask the agent to explore, edit, or run something.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Runs inside the Linux environment. Each tool that changes your files asks first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UserMessage(message: AgentMessage) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AssistantMessage(message: AgentMessage) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (message.thinking.isNotBlank()) {
            Text(
                text = message.thinking,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        when {
            message.text.isNotBlank() -> Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
            )
            message.isStreaming && message.thinking.isBlank() -> Text(
                text = "Thinking…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolCard(call: AgentToolCall) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = call.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = call.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(call.status),
                )
            }
            if (call.inputJson.isNotBlank() && call.inputJson != "{}") {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = clip(call.inputJson, 600),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            call.output?.takeIf { it.isNotBlank() }?.let { output ->
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = clip(output, 1500),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
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
private fun ApprovalCard(approval: AgentEvent.PermissionRequest, onApprove: (Boolean) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Allow ${approval.call.name}?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (approval.call.inputJson.isNotBlank() && approval.call.inputJson != "{}") {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = clip(approval.call.inputJson, 500),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { onApprove(false) }) { Text("Deny") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = { onApprove(true) }) { Text("Allow") }
            }
        }
    }
}

@Composable
private fun AgentComposer(streaming: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var input by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message the agent…") },
            maxLines = 5,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.width(8.dp))
        if (streaming) {
            Button(onClick = onStop) { Text("Stop") }
        } else {
            Button(
                onClick = {
                    val text = input.trim()
                    if (text.isNotEmpty()) {
                        onSend(text)
                        input = ""
                    }
                },
                enabled = input.isNotBlank(),
            ) { Text("Send") }
        }
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
