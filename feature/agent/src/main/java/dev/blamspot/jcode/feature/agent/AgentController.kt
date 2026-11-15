package dev.blamspot.jcode.feature.agent

import dev.blamspot.jcode.core.agent.AgentBackend
import dev.blamspot.jcode.core.agent.AgentChatStore
import dev.blamspot.jcode.core.agent.AgentEvent
import dev.blamspot.jcode.core.agent.AgentKind
import dev.blamspot.jcode.core.agent.AgentMessage
import dev.blamspot.jcode.core.agent.AgentModel
import dev.blamspot.jcode.core.agent.AgentRequest
import dev.blamspot.jcode.core.agent.AgentRole
import dev.blamspot.jcode.core.agent.AgentSetupRequirement
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

/** How much of an interactive setup's output to keep; the agy URL alone is around 1 KB. */
private const val SETUP_OUTPUT_LIMIT = 6000

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
    /** Set when the selected backend cannot run until the user installs or signs in to its CLI. */
    val setupRequirement: AgentSetupRequirement? = null,
    val setupBusy: Boolean = false,
    /** The setup command is waiting for a line from the user (the agy login code). */
    val setupNeedsInput: Boolean = false,
    val setupOutput: String? = null,
    val sessionId: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
)

/**
 * A running setup command that may ask for input.
 *
 * Installers are one-shot and finish on their own; `agy acp --login` prints an authorization URL
 * and then reads the code from stdin, so the pane has to keep the process alive between the two.
 */
interface AgentSetupSession {
    /** Send one line to the command's stdin. */
    fun submit(text: String)

    /** Stop the command; safe to call more than once. */
    fun close()
}

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
    /**
     * Runs a setup command (install / sign-in) in the Linux environment. Null leaves the pane with
     * nothing but the hint text, which is the right behaviour for a backend that ships no command.
     */
    private val setupRunner: (suspend (String, (String) -> Unit) -> Unit)? = null,
    /**
     * Starts an interactive setup command and returns the handle that can feed it stdin.
     * [onExit] reports the process exit code (null when it was killed) so the pane can update.
     */
    private val setupSessionStarter: (
        (String, (String) -> Unit, (Int?) -> Unit) -> AgentSetupSession?
    )? = null,
    /** Called with the command that just finished, so a backend can update its setup state. */
    private val onSetupComplete: ((String) -> Unit)? = null,
) {
    init {
        require(backends.isNotEmpty()) { "AgentController needs at least one backend" }
    }

    private val _state = MutableStateFlow(loadState(if (backends.containsKey(initialKind)) initialKind else backends.keys.first()))
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var turn: Job? = null
    /** The install/sign-in command, tracked apart from [turn] so a chat message cannot orphan it. */
    private var setupJob: Job? = null

    /** The live stdin of an interactive setup command, if any. */
    private var setupSession: AgentSetupSession? = null

    private fun backend(): AgentBackend = backends.getValue(_state.value.kind)

    private fun loadState(kind: AgentKind): AgentUiState {
        val backend = backends.getValue(kind)
        val saved = stores[kind]?.load()
        return AgentUiState(
            kind = kind,
            setupRequirement = backend.setupRequirement(),
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
                    // A turn can be the first thing to discover that the CLI needs credentials;
                    // re-read the requirement so the pane can offer the sign-in card immediately.
                    _state.update {
                        it.copy(
                            isStreaming = false,
                            setupRequirement = turnBackend.setupRequirement(),
                        )
                    }
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

    /**
     * Install or sign in to the selected backend's CLI, streaming its output into [AgentUiState.setupOutput].
     *
     * The command runs in the Linux environment exactly as written; its output streams into the
     * card, so an installer's progress and the agy sign-in link are visible without leaving the pane.
     */
    fun runSetup() {
        val state = _state.value
        val requirement = state.setupRequirement ?: return
        val command = requirement.installCommand ?: return
        if (state.setupBusy) return

        if (requirement.interactive) {
            runInteractiveSetup(command)
            return
        }

        val runner = setupRunner ?: return

        _state.update { it.copy(setupBusy = true, setupOutput = null, error = null) }
        setupJob = scope.launch {
            val lines = mutableListOf<String>()
            try {
                runner(command) { line ->
                    synchronized(lines) { lines += line }
                    // The tail is what fits on a phone; an installer's last lines are the useful ones.
                    val tail = synchronized(lines) { lines.takeLast(20) }.joinToString("\n")
                    _state.update { it.copy(setupOutput = tail) }
                }
                onSetupComplete?.invoke(command)
                _state.update {
                    it.copy(
                        setupBusy = false,
                        setupOutput = "Setup finished.",
                        // Install → the card becomes "Sign in"; sign-in → it goes away.
                        setupRequirement = backend().setupRequirement(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(
                        setupBusy = false,
                        setupOutput = synchronized(lines) { lines.takeLast(20) }.joinToString("\n"),
                        error = failure.message ?: "Setup failed",
                    )
                }
            }
        }
    }

    private fun runInteractiveSetup(command: String) {
        val starter = setupSessionStarter ?: return
        val kind = _state.value.kind
        _state.update {
            it.copy(setupBusy = true, setupNeedsInput = false, setupOutput = null, error = null)
        }

        fun append(line: String) {
            if (_state.value.kind != kind) return
            _state.update { current ->
                val merged = listOfNotNull(current.setupOutput?.takeIf { it.isNotBlank() }, line)
                    .joinToString("\n")
                current.copy(setupOutput = merged.takeLast(SETUP_OUTPUT_LIMIT))
            }
        }

        val session = starter(
            command,
            ::append,
        ) exit@{ code ->
            if (_state.value.kind != kind) return@exit
            val succeeded = code == 0
            if (succeeded) onSetupComplete?.invoke(command)
            val nextRequirement = if (succeeded) backend().setupRequirement() else _state.value.setupRequirement
            _state.update {
                it.copy(
                    setupBusy = false,
                    setupNeedsInput = false,
                    setupOutput = when {
                        succeeded -> "Sign-in finished."
                        code == null -> "Sign-in command stopped."
                        else -> "Sign-in command exited with code $code."
                    },
                    setupRequirement = nextRequirement,
                    error = if (succeeded) it.error else "Sign-in did not finish. Try again.",
                )
            }
            setupSession = null
        }
        setupSession = session
        if (session == null) {
            _state.update {
                it.copy(setupBusy = false, error = "Could not start the sign-in command.")
            }
        } else {
            _state.update { it.copy(setupNeedsInput = true) }
        }
    }

    /** Feed the authorization code (or callback URL) the agy login prompt asked for. */
    fun submitSetupInput(text: String) {
        val session = setupSession ?: return
        val line = text.trim()
        if (line.isEmpty()) return
        session.submit(line)
        _state.update {
            val log = listOfNotNull(it.setupOutput?.takeIf { out -> out.isNotBlank() }, "✓ Code submitted.")
                .joinToString("\n")
            it.copy(setupOutput = log.takeLast(SETUP_OUTPUT_LIMIT))
        }
    }

    /** The pane's "I've finished signing in" / dismiss: drop the card and let the next turn try. */
    fun dismissSetup() {
        // A login flow can sit waiting for a browser callback forever; closing the card must stop it.
        setupSession?.close()
        setupSession = null
        setupJob?.cancel()
        setupJob = null
        _state.update {
            it.copy(
                setupRequirement = null,
                setupBusy = false,
                setupNeedsInput = false,
                setupOutput = null,
            )
        }
    }

    /** Re-read the backend's requirement (e.g. after the environment wizard provisioned the runtime). */
    fun refreshSetup() {
        val requirement = backend().setupRequirement()
        _state.update { it.copy(setupRequirement = requirement) }
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
