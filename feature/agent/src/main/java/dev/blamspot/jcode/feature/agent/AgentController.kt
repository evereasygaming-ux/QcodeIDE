package dev.blamspot.jcode.feature.agent

import dev.blamspot.jcode.core.agent.AgentBackend
import dev.blamspot.jcode.core.agent.AgentEvent
import dev.blamspot.jcode.core.agent.AgentKind
import dev.blamspot.jcode.core.agent.AgentMessage
import dev.blamspot.jcode.core.agent.AgentModel
import dev.blamspot.jcode.core.agent.AgentRequest
import dev.blamspot.jcode.core.agent.AgentRole
import dev.blamspot.jcode.core.agent.AgentToolCall
import dev.blamspot.jcode.core.agent.AgentToolStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Everything the Agent pane renders. Immutable so recomposition is driven by one value. */
data class AgentUiState(
    val kind: AgentKind = AgentKind.CLAUDE,
    val models: List<AgentModel> = emptyList(),
    val model: String = "default",
    val messages: List<AgentMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val error: String? = null,
    val pendingApproval: AgentEvent.PermissionRequest? = null,
    val sessionId: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
)

/**
 * Drives one chat over an [AgentBackend]. Owns the message list, the in-flight turn, the approval
 * request currently waiting on the user, and the running token counts.
 */
class AgentController(
    private val backend: AgentBackend,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(
        AgentUiState(kind = backend.kind, models = backend.availableModels()),
    )
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var turn: Job? = null

    fun selectModel(id: String) {
        _state.update { it.copy(model = id) }
    }

    /** Start a turn. Ignored while another turn is running or when [prompt] is blank. */
    fun send(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || _state.value.isStreaming) return

        val assistantId = UUID.randomUUID().toString()
        _state.update {
            it.copy(
                messages = it.messages +
                    AgentMessage(UUID.randomUUID().toString(), AgentRole.USER, text = text) +
                    AgentMessage(assistantId, AgentRole.ASSISTANT, isStreaming = true),
                isStreaming = true,
                error = null,
                pendingApproval = null,
            )
        }

        val request = AgentRequest(
            prompt = text,
            model = _state.value.model,
            resumeSessionId = _state.value.sessionId,
        )
        turn = scope.launch {
            try {
                backend.stream(request).collect { event -> handle(event, assistantId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(error = failure.message ?: "Agent turn failed", isStreaming = false)
                }
            } finally {
                finishStreaming(assistantId)
                _state.update { it.copy(isStreaming = false) }
            }
        }
    }

    /** Stop the running turn. The conversation stays; the next send resumes the CLI session. */
    fun stop() {
        backend.cancel()
        turn?.cancel()
        turn = null
        _state.value.messages.filter { it.isStreaming }.forEach { finishStreaming(it.id) }
        _state.update { it.copy(isStreaming = false, pendingApproval = null) }
    }

    /** Answer the pending tool-permission prompt. */
    fun approvePending(allow: Boolean) {
        val pending = _state.value.pendingApproval ?: return
        backend.resolvePermission(pending.requestId, allow)
        updateTool(pending.call.id) {
            it.copy(status = if (allow) AgentToolStatus.APPROVED else AgentToolStatus.DENIED)
        }
        _state.update { it.copy(pendingApproval = null) }
    }

    fun close() {
        stop()
        backend.close()
    }

    private fun handle(event: AgentEvent, assistantId: String) {
        when (event) {
            is AgentEvent.AssistantText -> updateMessage(assistantId) { message ->
                message.copy(text = if (event.replace) event.text else message.text + event.text)
            }

            is AgentEvent.Thinking -> updateMessage(assistantId) { message ->
                message.copy(thinking = if (event.replace) event.text else message.thinking + event.text)
            }

            is AgentEvent.ToolUse -> {
                if (_state.value.messages.none { it.toolCall?.id == event.call.id }) {
                    _state.update {
                        it.copy(
                            messages = it.messages + AgentMessage(
                                id = event.call.id,
                                role = AgentRole.TOOL,
                                toolCall = event.call.copy(status = AgentToolStatus.RUNNING),
                            ),
                        )
                    }
                }
            }

            is AgentEvent.ToolResult -> updateTool(event.toolUseId) { call ->
                call.copy(
                    status = if (event.isError) AgentToolStatus.FAILED else AgentToolStatus.COMPLETED,
                    output = event.output,
                    isError = event.isError,
                )
            }

            is AgentEvent.PermissionRequest -> {
                if (_state.value.messages.none { it.toolCall?.id == event.call.id }) {
                    _state.update {
                        it.copy(
                            messages = it.messages + AgentMessage(
                                id = event.call.id,
                                role = AgentRole.TOOL,
                                toolCall = event.call,
                            ),
                        )
                    }
                }
                updateTool(event.call.id) { it.copy(status = AgentToolStatus.PENDING) }
                _state.update { it.copy(pendingApproval = event) }
            }

            is AgentEvent.Session -> _state.update { it.copy(sessionId = event.id) }

            is AgentEvent.Usage -> _state.update {
                it.copy(
                    inputTokens = it.inputTokens + event.inputTokens,
                    outputTokens = it.outputTokens + event.outputTokens,
                )
            }

            is AgentEvent.Error -> _state.update { it.copy(error = event.message) }

            AgentEvent.TurnComplete -> finishStreaming(assistantId)
        }
    }

    private fun updateMessage(id: String, transform: (AgentMessage) -> AgentMessage) {
        _state.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun updateTool(callId: String, transform: (AgentToolCall) -> AgentToolCall) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    val call = message.toolCall
                    if (call != null && call.id == callId) message.copy(toolCall = transform(call)) else message
                },
            )
        }
    }

    private fun finishStreaming(id: String) {
        updateMessage(id) { it.copy(isStreaming = false) }
    }
}
