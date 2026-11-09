package dev.blamspot.jcode.core.agent

import dev.blamspot.jcode.core.agent.agy.AgyAcpProtocol
import dev.blamspot.jcode.core.agent.agy.acpArr
import dev.blamspot.jcode.core.agent.agy.acpLong
import dev.blamspot.jcode.core.agent.agy.acpObj
import dev.blamspot.jcode.core.agent.agy.acpStr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Antigravity (agy) over its Agent Client Protocol stdio server.
 *
 * One long-lived `agy acp` process serves the whole chat: `initialize` once, then a `session/new`
 * (or `session/resume`) per workspace and a `session/prompt` per turn. Streaming updates arrive as
 * `session/update` notifications; a tool that needs consent arrives as `session/request_permission`
 * and is answered by [resolvePermission].
 */
class AgyAcpBackend(
    private val launcher: AgentProcessLauncher,
    private val defaultWorkdir: String = "/root",
    private val cliPath: String = "agy",
) : AgentBackend {

    override val kind: AgentKind = AgentKind.AGY

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectLock = Mutex()
    private val writeLock = Mutex()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val incoming = Channel<JsonObject>(Channel.UNLIMITED)
    private val approvals = ConcurrentHashMap<String, AgyAcpProtocol.PermissionInfo>()
    private val stderrTail = StringBuilder()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var readerJob: Job? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var sessionCwd: String? = null

    @Volatile
    private var sessionModel: String? = null

    @Volatile
    private var sessionMode: String? = null

    @Volatile
    private var modelOptions: List<AgentModel> = DEFAULT_MODELS

    /** The prompt request id currently in flight; its response is queued as [PROMPT_RESULT_METHOD]. */
    @Volatile
    private var promptKey: String? = null

    override fun availableModels(): List<AgentModel> = modelOptions

    override fun resolvePermission(requestId: String, allow: Boolean) {
        val info = approvals.remove(requestId) ?: return
        val option = AgyAcpProtocol.chooseOption(info.options, allow)
        scope.launch {
            runCatching {
                val outcome = buildJsonObject {
                    if (option != null) {
                        put("outcome", "selected")
                        put("optionId", option)
                    } else {
                        put("outcome", "cancelled")
                    }
                }
                send(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", info.jsonId)
                        put("result", buildJsonObject { put("outcome", outcome) })
                    },
                )
            }
        }
    }

    override fun stream(request: AgentRequest): Flow<AgentEvent> = channelFlow {
        val cwd = request.workdir?.takeIf { it.isNotBlank() } ?: defaultWorkdir
        try {
            val session = ensureSession(cwd, request.resumeSessionId)
            send(AgentEvent.Session(session, sessionModel ?: request.model))

            request.permissionMode
                .takeIf { it.isNotBlank() && it != "default" }
                ?.let { setMode(session, it) }
            request.model
                ?.takeIf { it.isNotBlank() && it != "default" }
                ?.let { setModel(session, it) }

            drainIncoming()

            val id = JsonPrimitive(nextId.getAndIncrement())
            promptKey = AgyAcpProtocol.idKey(id)

            send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", "session/prompt")
                    put("params", buildJsonObject {
                        put("sessionId", session)
                        put("prompt", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", request.prompt)
                            })
                        })
                    })
                },
            )

            while (true) {
                val message = incoming.receive()
                if (message.acpStr("method") == PROMPT_RESULT_METHOD) {
                    handlePromptResponse(message.acpObj("response") ?: JsonObject(emptyMap())) { send(it) }
                    break
                }
                if (handleIncoming(message, session) { send(it) }) break
            }
        } catch (timeout: TimeoutCancellationException) {
            send(AgentEvent.Error("Antigravity did not respond in time. Try again."))
            send(AgentEvent.TurnComplete)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            send(AgentEvent.Error(failure.message ?: "Antigravity turn failed"))
            send(AgentEvent.TurnComplete)
        } finally {
            promptKey = null
        }
    }

    override fun cancel() {
        val session = sessionId
        val waiting = approvals.values.toList()
        approvals.clear()
        scope.launch {
            for (info in waiting) {
                runCatching { sendPermissionOutcome(info.jsonId, null, info.options) }
            }
            if (!session.isNullOrBlank()) {
                runCatching {
                    send(
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("method", "session/cancel")
                            put("params", buildJsonObject { put("sessionId", session) })
                        },
                    )
                }
            }
        }
    }

    override fun close() {
        closeProcessLocked()
        approvals.clear()
        incoming.close()
        scope.cancel()
    }

    private fun closeProcessLocked() {
        initialized = false
        sessionId = null
        sessionCwd = null
        sessionModel = null
        sessionMode = null
        val current = process
        process = null
        readerJob?.cancel()
        readerJob = null
        current?.let { runCatching { it.destroy() } }
        val failure = processExitResponse()
        pending.values.forEach { it.complete(failure) }
        pending.clear()
    }

    private suspend fun ensureSession(cwd: String, resume: String?): String = connectLock.withLock {
        ensureProcessLocked(cwd)
        val current = sessionId
        if (current != null && sessionCwd == cwd) return@withLock current

        if (!resume.isNullOrBlank()) {
            val resumed = runCatching {
                request("session/resume", sessionParams(resume, cwd), SESSION_TIMEOUT_MS)
            }.getOrNull()
            if (resumed != null) {
                sessionId = resumed.acpStr("sessionId").ifBlank { resume }
                sessionCwd = cwd
                captureSession(resumed)
                return@withLock sessionId!!
            }
        }

        val created = request("session/new", sessionParams(null, cwd), SESSION_TIMEOUT_MS)
        val id = created.acpStr("sessionId")
        if (id.isBlank()) throw AgentRpcException("Antigravity did not return a session id")
        sessionId = id
        sessionCwd = cwd
        captureSession(created)
        id
    }

    private suspend fun ensureProcessLocked(cwd: String) {
        if (process?.isAlive == true && initialized) return
        closeProcessLocked()

        val started = runCatching { launcher.launch(command(), cwd) }.getOrNull()
            ?: throw AgentRpcException(
                "Could not start the Antigravity CLI (agy). Make sure it is installed and signed " +
                    "in inside the Linux environment.",
            )
        process = started
        startReader(started)
        try {
            request(
                method = "initialize",
                params = buildJsonObject {
                    put("protocolVersion", PROTOCOL_VERSION)
                    put("clientInfo", buildJsonObject {
                        put("name", "QCode")
                        put("version", "1.0")
                    })
                    put("clientCapabilities", buildJsonObject {})
                },
                timeoutMs = INITIALIZE_TIMEOUT_MS,
            )
            initialized = true
        } catch (failure: Throwable) {
            closeProcessLocked()
            throw failure
        }
    }

    private fun startReader(started: Process) {
        synchronized(stderrTail) { stderrTail.setLength(0) }
        readerJob = scope.launch {
            try {
                started.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val message = AgyAcpProtocol.parseLine(line) ?: continue
                        if (message.acpStr("method").isNotBlank()) {
                            incoming.trySend(message)
                        } else {
                            val key = AgyAcpProtocol.idKey(message["id"])
                            when {
                                key == null -> Unit
                                key == promptKey -> incoming.trySend(
                                    buildJsonObject {
                                        put("method", PROMPT_RESULT_METHOD)
                                        put("response", message)
                                    },
                                )
                                else -> pending.remove(key)?.complete(message)
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // Process killed or the pipe closed; [onProcessGone] reports it to the turn.
            } finally {
                onProcessGone(started)
            }
        }
        scope.launch {
            runCatching {
                started.errorStream.bufferedReader().forEachLine(::appendStderr)
            }
        }
    }

    private fun onProcessGone(started: Process) {
        if (process !== started) return
        process = null
        initialized = false
        sessionId = null
        sessionCwd = null
        val failure = processExitResponse()
        pending.values.forEach { it.complete(failure) }
        pending.clear()
        incoming.trySend(
            buildJsonObject {
                put("method", PROCESS_EXIT_METHOD)
                put("message", stderrMessage())
            },
        )
    }

    private suspend fun request(method: String, params: JsonObject, timeoutMs: Long): JsonObject {
        val id = JsonPrimitive(nextId.getAndIncrement())
        val key = AgyAcpProtocol.idKey(id)
            ?: throw AgentRpcException("Could not allocate an Antigravity request id")
        val deferred = CompletableDeferred<JsonObject>()
        pending[key] = deferred
        try {
            send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    put("params", params)
                },
            )
            val response = withTimeout(timeoutMs) { deferred.await() }
            if (response.acpObj("error") != null) throw rpcError(response)
            return response.acpObj("result") ?: JsonObject(emptyMap())
        } finally {
            pending.remove(key)
        }
    }

    private suspend fun handleIncoming(
        message: JsonObject,
        session: String,
        emit: suspend (AgentEvent) -> Unit,
    ): Boolean {
        val method = message.acpStr("method")
        val params = message.acpObj("params")
        val messageSession = params?.acpStr("sessionId").orEmpty()
        if (messageSession.isNotBlank() && messageSession != session) return false

        return when (method) {
            "session/update" -> {
                for (event in AgyAcpProtocol.sessionUpdate(message)) emit(event)
                false
            }

            "session/request_permission" -> {
                val info = AgyAcpProtocol.permission(message)
                if (info == null) {
                    message["id"]?.let { id -> scope.launch { runCatching { sendPermissionOutcome(id, null, emptyList()) } } }
                } else {
                    emit(AgentEvent.ToolUse(info.call))
                    approvals[info.requestId] = info
                    emit(AgentEvent.PermissionRequest(info.requestId, info.call))
                }
                false
            }

            PROCESS_EXIT_METHOD -> {
                val detail = message.acpStr("message")
                emit(
                    AgentEvent.Error(
                        detail.ifBlank { "Antigravity CLI exited before the turn finished." },
                    ),
                )
                emit(AgentEvent.TurnComplete)
                true
            }

            else -> {
                message["id"]?.let { id ->
                    scope.launch {
                        runCatching {
                            send(
                                buildJsonObject {
                                    put("jsonrpc", "2.0")
                                    put("id", id)
                                    put("error", buildJsonObject {
                                        put("code", -32601)
                                        put("message", "Unsupported ACP client method: $method")
                                    })
                                },
                            )
                        }
                    }
                }
                false
            }
        }
    }

    private suspend fun handlePromptResponse(
        response: JsonObject,
        emit: suspend (AgentEvent) -> Unit,
    ) {
        if (response.acpObj("error") != null) {
            emit(AgentEvent.Error(rpcError(response).message ?: "Antigravity turn failed"))
            emit(AgentEvent.TurnComplete)
            return
        }
        val result = response.acpObj("result")
        if (result != null) {
            result.acpObj("usage")?.let { usage ->
                val input = usage.acpLong("inputTokens").takeIf { it > 0 }
                    ?: usage.acpLong("input_tokens")
                val output = usage.acpLong("outputTokens").takeIf { it > 0 }
                    ?: usage.acpLong("output_tokens")
                if (input > 0 || output > 0) emit(AgentEvent.Usage(input, output))
            }
            val stop = result.acpStr("stopReason").ifBlank { result.acpStr("stop_reason") }
            if (stop == "refusal" || stop == "max_tokens" || stop == "max_turn_requests") {
                emit(AgentEvent.Error("Antigravity stopped: $stop"))
            }
        }
        emit(AgentEvent.TurnComplete)
    }

    private suspend fun setModel(session: String, model: String) {
        if (model == sessionModel) return
        runCatching {
            request(
                method = "session/set_config_option",
                params = buildJsonObject {
                    put("sessionId", session)
                    put("configId", "model")
                    put("value", model)
                },
                timeoutMs = CONFIG_TIMEOUT_MS,
            )
            sessionModel = model
        }
    }

    private suspend fun setMode(session: String, mode: String) {
        if (mode == sessionMode) return
        runCatching {
            request(
                method = "session/set_mode",
                params = buildJsonObject {
                    put("sessionId", session)
                    put("modeId", mode)
                },
                timeoutMs = CONFIG_TIMEOUT_MS,
            )
            sessionMode = mode
        }
    }

    private suspend fun sendPermissionOutcome(
        id: JsonElement,
        allow: Boolean?,
        options: List<AgyAcpProtocol.PermissionOption>,
    ) {
        val option = allow?.let { AgyAcpProtocol.chooseOption(options, it) }
        val outcome = buildJsonObject {
            if (option != null) {
                put("outcome", "selected")
                put("optionId", option)
            } else {
                put("outcome", "cancelled")
            }
        }
        send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", buildJsonObject { put("outcome", outcome) })
            },
        )
    }

    private suspend fun send(message: JsonObject) = writeLock.withLock {
        val current = process ?: throw AgentRpcException("Antigravity CLI is not running")
        withContext(Dispatchers.IO) {
            current.outputStream.write((message.toString() + "\n").toByteArray())
            current.outputStream.flush()
        }
    }

    private fun drainIncoming() {
        while (incoming.tryReceive().isSuccess) {
            // Setup notifications (commands list, title) are not part of the next turn.
        }
    }

    private fun captureSession(result: JsonObject) {
        sessionModel = null
        sessionMode = null
        val configs = result.acpArr("configOptions") ?: return
        val modelConfig = configs.mapNotNull { it as? JsonObject }
            .firstOrNull { it.acpStr("id") == "model" } ?: return
        modelConfig.acpStr("currentValue").takeIf { it.isNotBlank() }?.let { sessionModel = it }
        val parsed = modelConfig.acpArr("options").orEmpty()
            .mapNotNull { it as? JsonObject }
            .map {
                AgentModel(
                    id = it.acpStr("value"),
                    label = it.acpStr("name").ifBlank { it.acpStr("value") },
                )
            }
            .filter { it.id.isNotBlank() }
        if (parsed.isNotEmpty()) {
            modelOptions = listOf(AgentModel("default", "Default")) + parsed
        }
    }

    private fun sessionParams(resume: String?, cwd: String): JsonObject = buildJsonObject {
        if (resume != null) put("sessionId", resume)
        put("cwd", cwd)
        put("mcpServers", buildJsonArray {})
    }

    private fun rpcError(response: JsonObject): AgentRpcException {
        val error = response.acpObj("error")
        val code = (error?.get("code") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
        val message = error?.acpStr("message").orEmpty().ifBlank { "Antigravity request failed" }
        return if (code == AUTH_REQUIRED) {
            AgentRpcException(
                "Antigravity authentication required. Sign in with the agy CLI inside the Linux environment.",
            )
        } else {
            AgentRpcException(message)
        }
    }

    private fun processExitResponse(): JsonObject = buildJsonObject {
        put("error", buildJsonObject {
            put("code", -32000)
            put("message", "Antigravity CLI exited")
        })
    }

    private fun command(): String {
        if (cliPath != "agy") return "exec ${shellQuote(cliPath)} acp"
        return "AGY=\"\$HOME/.local/bin/agy\"; " +
            "[ -x \"\$AGY\" ] || AGY=\$(command -v agy 2>/dev/null); " +
            "[ -n \"\$AGY\" ] || { echo 'agy CLI not found' >&2; exit 127; }; " +
            "exec \"\$AGY\" acp"
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun appendStderr(line: String) {
        synchronized(stderrTail) {
            if (stderrTail.length < STDERR_LIMIT) stderrTail.append(line).append('\n')
        }
    }

    private fun stderrMessage(): String = synchronized(stderrTail) {
        stderrTail.toString().trim().takeLast(STDERR_TAIL_CHARS)
    }

    private class AgentRpcException(message: String) : Exception(message)

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val INITIALIZE_TIMEOUT_MS = 60_000L
        const val SESSION_TIMEOUT_MS = 120_000L
        const val CONFIG_TIMEOUT_MS = 30_000L
        const val AUTH_REQUIRED = -32000L
        const val STDERR_LIMIT = 4_000
        const val STDERR_TAIL_CHARS = 600
        const val PROCESS_EXIT_METHOD = "\$/process_exit"
        const val PROMPT_RESULT_METHOD = "\$/prompt_result"

        val DEFAULT_MODELS = listOf(
            AgentModel("default", "Default"),
            AgentModel("gemini-3.8-flash", "Gemini 3.8 Flash"),
            AgentModel("claude-sonnet-4-6", "Claude Sonnet 4.6"),
            AgentModel("claude-opus-4-6-thinking", "Claude Opus 4.6"),
            AgentModel("gpt-oss-120b-medium", "GPT-OSS 120B"),
        )
    }
}
