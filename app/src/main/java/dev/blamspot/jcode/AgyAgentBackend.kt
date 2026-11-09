package dev.blamspot.jcode

import dev.blamspot.jcode.core.agent.AgentBackend
import dev.blamspot.jcode.core.agent.AgentEvent
import dev.blamspot.jcode.core.agent.AgentKind
import dev.blamspot.jcode.core.agent.AgentModel
import dev.blamspot.jcode.core.agent.AgentRequest
import dev.blamspot.jcode.core.agent.agy.AgyBridgeClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [AgentBackend] for Google Antigravity, driven through `agy_ide_bridge` over its WebSocket API.
 *
 * Unlike the Claude CLI, Antigravity runs tools itself: there is no permission round-trip, so
 * [resolvePermission] is a no-op and tool cards are purely narrative. The bridge manages the
 * conversation id and the `agy` hub; this class only starts the bridge lazily and maps frames.
 */
class AgyAgentBackend(private val bridge: AgyBridgeRuntime) : AgentBackend {

    override val kind: AgentKind = AgentKind.AGY

    override fun availableModels(): List<AgentModel> = listOf(AgentModel("default", "Default"))

    private val clientLock = Mutex()

    @Volatile
    private var client: AgyBridgeClient? = null

    @Volatile
    private var activeClient: AgyBridgeClient? = null

    @Volatile
    private var conversationId: String? = null

    override fun stream(request: AgentRequest): Flow<AgentEvent> = flow {
        emit(AgentEvent.Thinking("Starting Antigravity bridge…", replace = true))
        val connection = ensureClient(request.workdir)
        emit(AgentEvent.Thinking("", replace = true))
        activeClient = connection
        try {
            connection.stream(
                prompt = request.prompt,
                model = request.model,
                workspaceDir = request.workdir,
                resumeConversationId = request.resumeSessionId ?: conversationId,
            ).collect { event ->
                if (event is AgentEvent.Session) conversationId = event.id
                emit(event)
            }
        } finally {
            activeClient = null
        }
    }.catch { failure ->
        emit(AgentEvent.Error(failure.message ?: "Antigravity turn failed"))
        emit(AgentEvent.TurnComplete)
    }

    override fun cancel() {
        activeClient?.abort()
    }

    override fun resolvePermission(requestId: String, allow: Boolean) = Unit

    override fun close() {
        client?.close()
        client = null
        bridge.stop()
    }

    private suspend fun ensureClient(workdir: String?): AgyBridgeClient = clientLock.withLock {
        client ?: run {
            bridge.ensureStarted(workdir).getOrElse { throw it }
            AgyBridgeClient(bridge.httpUrl, bridge.wsUrl, bridge.framedHeader).also { client = it }
        }
    }
}
