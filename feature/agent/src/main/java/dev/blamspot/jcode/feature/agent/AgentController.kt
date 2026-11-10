package dev.blamspot.jcode.feature.agent

import dev.blamspot.jcode.core.agent.AgentBackend
import dev.blamspot.jcode.core.agent.AgentChatStore
import dev.blamspot.jcode.core.agent.AgentEvent
import dev.blamspot.jcode.core.agent.AgentKind
import dev.blamspot.jcode.core.agent.AgentMessage
import dev.blamspot.jcode.core.agent.AgentModel
import dev.blamspot.jcode.core.agent.AgentRequest
import dev.blamspot.jcode.core.agent.AgentRole
import dev.blamspot.jcode.core.agent.AgentSavedChat
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
    val providers: List<AgentKind> = emptyList(),
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
 * Drives one chat per provider. Owns the message list, the in-flight turn, the approval request
 * currently waiting on the user, and the running token counts. Switching provider saves the current
 * feed and loads the other provider's saved chat.
 */
class AgentController(
    private val backends: Map<AgentKind, AgentBackend>,
    private val scope: CoroutineScope,
    private val stores: Map<AgentKind, AgentChatStore> = emptyMap(),
    initialKind: AgentKind = backends.keys.firstOrNull() ?: AgentKind.CLAUDE,
) {
    init {
        require(backends.isNotEmpty()) { "AgentController needs at least one backend" }
    }

    private val _state = MutableStateFlow(loadState(if (backends.containsKey(initialKind)) initialKind else backends.keys.first()))
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var turn: Job? = null

    private fun backend(): AgentBackend = backends.getValue(_state.value.kind)

    private fun loadState(kind: AgentKind): AgentUiState {
        val backend = backends.getValue(kind)
        val saved = stores[kind]?.load()
        return AgentUiState(
            kind = kind,
            providers = backends.keys.toList(),
            models = backend.availableModels(),
            model = saved?.model ?: "default",
            messages = saved?.messages.orEmpty().map {
                if (it.isStreaming) it.copy(isStreaming = false) else it
            },
            sessionId = saved?.sessionId,
        )
    }

    fun selectKind(kind: AgentKind) {
        if (kind == _state.value.kind || !backends.containsKey(kind)) return
        stop()
        persist()
        _state.value = loadState(kind)
    }

    fun selectModel(id: String) {
        _state.update { it.copy(model = id) }
        persist()
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
        persist()

        val request = AgentRequest(
            prompt = text,
            model = _state.value.model,
            resumeSessionId = _state.value.sessionId,
        )
        val turnKind = _state.value.kind
        val turnBackend = backend()
        turn = scope.launch {
            try {
                turnBackend.stream(request).collect { event ->
                    if (_state.value.kind == turnKind) handle(event, assistantId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (_state.value.kind == turnKind) {
                    _state.update {
                        it.copy(error = failure.message ?: "Agent turn failed", isStreaming = false)
                    }
                }
            } finally {
                if (_state.value.kind == turnKind) {
                    finishStreaming(assistantId)
                    _state.update { it.copy(isStreaming = false) }
                    persist()
                }
            }
        }
    }

    /** Stop the running turn. The conversation stays; the next send resumes the session. */
    fun stop() {
        backend().cancel()
        turn?.cancel()
        turn = null
        _state.value.messages.filter { it.isStreaming }.forEach { finishStreaming(it.id) }
        _state.update { it.copy(isStreaming = false, pendingApproval = null) }
        persist()
    }

    /** Answer the pending tool-permission prompt. */
    fun approvePending(allow: Boolean) {
        val pending = _state.value.pendingApproval ?: return
        backend().resolvePermission(pending.requestId, allow)
        updateTool(pending.call.id) {
            it.copy(status = if (allow) AgentToolStatus.APPROVED else AgentToolStatus.DENIED)
        }
        _state.update { it.copy(pendingApproval = null) }
        persist()
    }

    /** Start over: drop the feed and the session so the next prompt begins a fresh chat. */
    fun clearChat() {
        stop()
        _state.update {
            it.copy(
                messages = emptyList(),
                sessionId = null,
                error = null,
                inputTokens = 0,
                outputTokens = 0,
            )
        }
        stores[_state.value.kind]?.clear()
    }

    fun close() {
        stop()
        persistNow()
        backends.values.forEach { runCatching { it.close() } }
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

            is AgentEvent.Session -> {
                val models = backend().availableModels()
                _state.update { state ->
                    val selected = listOfNotNull(event.model, state.model)
                        .firstOrNull { id -> models.any { it.id == id } }
                        ?: models.firstOrNull()?.id
                        ?: "default"
                    state.copy(sessionId = event.id, models = models, model = selected)
                }
                persist()
            }

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

    private fun snapshot(): AgentSavedChat = _state.value.run {
        AgentSavedChat(
            messages = messages.map { if (it.isStreaming) it.copy(isStreaming = false) else it },
            sessionId = sessionId,
            model = model,
        )
    }

    private fun persist() {
        val store = stores[_state.value.kind] ?: return
        // Small JSON file, written only at turn boundaries / model changes — synchronous keeps the
        // ordering obvious and avoids a queued write resurrecting a chat that was just cleared.
        store.save(snapshot())
    }

    private fun persistNow() = persist()
}
