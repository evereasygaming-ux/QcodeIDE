package dev.blamspot.jcode.core.agent

import kotlinx.serialization.Serializable

/** Which agent backend a chat is talking to. */
@Serializable
enum class AgentKind(val label: String) {
    AGY("Antigravity"),
    CLAUDE("Claude Code"),
}

@Serializable
enum class AgentRole { USER, ASSISTANT, TOOL }

@Serializable
enum class AgentToolStatus { PENDING, APPROVED, DENIED, RUNNING, COMPLETED, FAILED }

/** One tool invocation an agent asked for, shown as a card in the chat. */
@Serializable
data class AgentToolCall(
    val id: String,
    val name: String,
    val inputJson: String = "{}",
    val status: AgentToolStatus = AgentToolStatus.PENDING,
    val output: String? = null,
    val isError: Boolean = false,
)

/** One message in the chat feed. [isStreaming] marks the assistant message currently being written. */
@Serializable
data class AgentMessage(
    val id: String,
    val role: AgentRole,
    val text: String = "",
    val thinking: String = "",
    val toolCall: AgentToolCall? = null,
    val isStreaming: Boolean = false,
)

/** A model the selected backend offers. [id] is what the CLI is given (or "default"). */
@Serializable
data class AgentModel(val id: String, val label: String)

/** One turn request. [resumeSessionId] continues an existing CLI session when the backend supports it. */
data class AgentRequest(
    val prompt: String,
    val model: String? = null,
    val permissionMode: String = "default",
    val workdir: String? = null,
    val resumeSessionId: String? = null,
)

/** Everything a backend reports while a turn runs. */
sealed interface AgentEvent {
    /** Assistant text. [replace] = the full accumulated text (final block); false = a delta to append. */
    data class AssistantText(val text: String, val replace: Boolean = false) : AgentEvent

    /** Chain-of-thought / summarized thinking; shown dimmed and never treated as tool input. */
    data class Thinking(val text: String, val replace: Boolean = false) : AgentEvent

    data class ToolUse(val call: AgentToolCall) : AgentEvent

    data class ToolResult(val toolUseId: String, val output: String, val isError: Boolean) : AgentEvent

    /** A tool the CLI wants to run and needs the user to allow or deny before it can proceed. */
    data class PermissionRequest(val requestId: String, val call: AgentToolCall) : AgentEvent

    data class Session(val id: String, val model: String?) : AgentEvent

    data class Usage(val inputTokens: Long, val outputTokens: Long) : AgentEvent

    data class Error(val message: String) : AgentEvent

    data object TurnComplete : AgentEvent
}

/**
 * Launches a long-lived CLI inside the embedded Linux runtime with stdio pipes. Implemented by the
 * app with [dev.blamspot.jcode.core.distro.DistroService.spawnStdioProcess]; kept as a small
 * functional interface so `:core:agent` has no Android/runtime dependency of its own.
 */
fun interface AgentProcessLauncher {
    /** [command] is a shell command line, run as the guest user in [workdir] (null = runtime default). */
    fun launch(command: String, workdir: String?): Process?
}

/**
 * One agent backend. [stream] is cold: each collection starts a turn and emits events until the turn
 * completes. [resolvePermission] answers a [AgentEvent.PermissionRequest] emitted by that turn.
 */
interface AgentBackend {
    val kind: AgentKind

    fun availableModels(): List<AgentModel>

    fun stream(request: AgentRequest): kotlinx.coroutines.flow.Flow<AgentEvent>

    /** Answer a pending [AgentEvent.PermissionRequest] by its [requestId]. */
    fun resolvePermission(requestId: String, allow: Boolean)

    /**
     * Remember that [toolName] may run without asking again for the rest of this session.
     *
     * A long agent run — a video pipeline that dispatches a hundred tool calls, a refactor across
     * many files — asks for the same tool over and over, and answering each one by hand is pure
     * friction. Backends that cannot pre-approve simply ignore this.
     */
    fun allowToolForSession(toolName: String) {}

    /** Stop the in-flight turn, if any. The backend stays usable for the next turn. */
    fun cancel()

    /** Release all resources; the backend must not be used afterwards. */
    fun close()

    /**
     * What the user has to do before this backend can serve a turn, or null when nothing is needed.
     *
     * A CLI that is not installed, or is installed but not signed in, cannot start a chat: the pane
     * asks for a prompt and then reports a failure from deep inside the protocol. Surfacing the
     * missing prerequisite here lets the pane offer to fix it instead of explaining an error.
     */
    fun setupRequirement(): AgentSetupRequirement? = null
}

/**
 * A prerequisite the user must satisfy before [AgentBackend.stream] can work.
 *
 * [installCommand] is run inside the Linux environment by the pane; it is null when the fix is
 * something the user has to do themselves (e.g. an interactive sign-in), in which case [hint] says
 * what to run. [refreshCommand] re-checks the requirement after an install.
 */
data class AgentSetupRequirement(
    val title: String,
    val detail: String,
    val installCommand: String? = null,
    val installLabel: String = "Install",
    val hint: String? = null,
    /**
     * True when [installCommand] stays alive and reads a line from stdin (the agy login code),
     * so the pane has to offer an input field and keep the process running between steps.
     */
    val interactive: Boolean = false,
    /** Label of the input field when [interactive]. */
    val inputLabel: String = "Authorization code or callback URL",
)
