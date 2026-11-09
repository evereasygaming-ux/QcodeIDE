package dev.blamspot.jcode.core.agent.agy

import dev.blamspot.jcode.core.agent.AgentEvent
import dev.blamspot.jcode.core.agent.AgentToolCall
import dev.blamspot.jcode.core.agent.AgentToolStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for the vendored `agy_ide_bridge` Go server (see `agent/bridge`).
 *
 * The bridge exposes the Antigravity CLI over `ws://127.0.0.1:<port>/ws`. The handshake carries the
 * framed security header (`x-ag<12-char-token>_9qx`); everything else is JSON frames. This client
 * only speaks the subset the chat pane needs: send a prompt, stream text/thinking/tool updates,
 * abort, and read token usage.
 */
class AgyBridgeClient(
    private val httpUrl: String,
    private val wsUrl: String,
    framedHeader: String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header(framedHeader, "true").build())
        }
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    var conversationId: String? = null
        private set

    fun stream(
        prompt: String,
        model: String? = null,
        workspaceDir: String? = null,
        resumeConversationId: String? = null,
    ): Flow<AgentEvent> = callbackFlow {
        val state = TurnState()
        val request = Request.Builder().url(wsUrl).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                val payload = buildJsonObject {
                    put("type", "send_prompt")
                    put("prompt", prompt)
                    if (!model.isNullOrBlank() && model != "default") put("model", model)
                    val conversation = resumeConversationId ?: conversationId
                    if (!conversation.isNullOrBlank()) put("conversationId", conversation)
                    if (!workspaceDir.isNullOrBlank()) put("workspaceDir", workspaceDir)
                }
                webSocket.send(payload.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text, state) { trySend(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!state.finished) {
                    trySend(AgentEvent.Error(t.message ?: "Antigravity bridge connection failed"))
                    trySend(AgentEvent.TurnComplete)
                }
                close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!state.finished) {
                    trySend(AgentEvent.Error("Antigravity bridge closed the connection"))
                    trySend(AgentEvent.TurnComplete)
                }
                close()
            }
        }
        val webSocket = client.newWebSocket(request, listener)
        awaitClose {
            socket = null
            webSocket.cancel()
        }
    }

    fun abort() {
        val conversation = conversationId
        socket?.send(
            buildJsonObject {
                put("type", "abort")
                if (!conversation.isNullOrBlank()) put("conversationId", conversation)
            }.toString(),
        )
        val body = buildJsonObject {
            if (!conversation.isNullOrBlank()) put("conversationId", conversation)
        }.toString()
        runCatching {
            client.newCall(
                Request.Builder()
                    .url("$httpUrl/api/abort")
                    .post(body.toRequestBody(jsonMedia))
                    .build(),
            ).enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) = Unit
                    override fun onResponse(call: Call, response: Response) {
                        response.close()
                    }
                },
            )
        }
    }

    fun close() {
        runCatching { socket?.cancel() }
        socket = null
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    private fun handle(text: String, state: TurnState, emit: (AgentEvent) -> Unit) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (root.str("type")) {
            "agy_event" -> root.obj("data")?.let { handleAgyEvent(it, state, emit) }
            "auth_login_url" -> {
                val url = root.str("url")
                if (url.isNotBlank()) {
                    emit(AgentEvent.Error("Antigravity login required. Open: $url"))
                }
            }
            "stream_snapshot" -> {
                val id = root.str("conversationId").ifBlank { root.str("conversation_id") }
                if (id.isNotBlank()) conversationId = id
                val content = root.str("content")
                if (content.isNotBlank()) {
                    state.sawText = true
                    emit(AgentEvent.AssistantText(content, replace = true))
                }
                val thought = root.str("thought")
                if (thought.isNotBlank()) emit(AgentEvent.Thinking(thought, replace = true))
                root.array("activeTools")?.forEach { item ->
                    val tool = item as? JsonObject ?: return@forEach
                    val toolId = tool.str("id")
                    val name = tool.str("name").ifBlank { "tool" }
                    val status = tool.str("status")
                    emit(AgentEvent.ToolUse(AgentToolCall(toolId, name, status = AgentToolStatus.RUNNING)))
                    if (status == "DONE" || status == "COMPLETED" || status == "FAILED") {
                        emit(AgentEvent.ToolResult(toolId, tool.str("output"), status == "FAILED"))
                    }
                }
            }
            "instance_status" -> {
                val id = root.str("conversationId")
                if (id.isNotBlank()) conversationId = id
                if (root.str("status") == "error") {
                    emit(AgentEvent.Error(root.str("message").ifBlank { "Antigravity instance failed" }))
                }
            }
            "raw_chunk" -> {
                val chunk = root.str("text")
                if (chunk.isNotBlank()) {
                    state.sawText = true
                    emit(AgentEvent.AssistantText(chunk))
                }
            }
            "quota_update" -> Unit
            "done" -> {
                state.finished = true
                emit(AgentEvent.TurnComplete)
            }
            "error" -> {
                state.finished = true
                emit(AgentEvent.Error(root.str("message").ifBlank { "Antigravity bridge error" }))
                emit(AgentEvent.TurnComplete)
            }
        }
    }

    private fun handleAgyEvent(data: JsonObject, state: TurnState, emit: (AgentEvent) -> Unit) {
        when (data.str("event")) {
            "init" -> {
                val id = data.str("conversation_id").ifBlank { data.str("conversationId") }
                if (id.isNotBlank()) {
                    conversationId = id
                    emit(AgentEvent.Session(id, data.str("model").takeIf { it.isNotBlank() }))
                }
            }
            "step_update" -> {
                val step = data.obj("step_update") ?: return
                val thought = step.str("thought").ifBlank { step.str("thinking") }
                if (thought.isNotBlank()) emit(AgentEvent.Thinking(thought))

                val info = step.obj("tool_info")
                val stepType = step.str("step_type")
                if (stepType == "tool" || info != null || step.containsKey("tool_name")) {
                    val name = info?.str("name").orEmpty()
                        .ifBlank { step.str("tool_name").ifBlank { "tool" } }
                    val params = info?.get("parameters")?.toString() ?: step.str("tool_args")
                    val output = info?.str("output") ?: step.str("tool_output")
                    val rawState = step.str("state")
                    val index = if (step.containsKey("step_index")) step.int("step_index") else state.nextTool++
                    val toolId = "tool_${conversationId ?: "live"}_$index"
                    val done = rawState == "DONE" || rawState == "COMPLETED" || rawState == "FAILED"
                    emit(
                        AgentEvent.ToolUse(
                            AgentToolCall(
                                id = toolId,
                                name = name,
                                inputJson = params,
                                status = if (done) AgentToolStatus.COMPLETED else AgentToolStatus.RUNNING,
                            ),
                        ),
                    )
                    if (done) emit(AgentEvent.ToolResult(toolId, output, rawState == "FAILED"))
                }

                if (stepType == "agent_response") {
                    val delta = step.str("text_delta")
                    if (delta.isNotEmpty()) {
                        state.sawText = true
                        emit(AgentEvent.AssistantText(delta))
                    }
                }
            }
            "result" -> {
                val result = data.obj("result") ?: return
                result.obj("usage")?.let { usage ->
                    emit(AgentEvent.Usage(usage.long("input_tokens"), usage.long("output_tokens")))
                }
                val error = result.str("error")
                if (error.isNotBlank()) {
                    state.finished = true
                    emit(AgentEvent.Error(error))
                } else if (!state.sawText) {
                    val full = result.str("response")
                    if (full.isNotBlank()) {
                        state.sawText = true
                        emit(AgentEvent.AssistantText(full, replace = true))
                    }
                }
                state.finished = true
                emit(AgentEvent.TurnComplete)
            }
        }
    }

    private class TurnState {
        var sawText = false
        var finished = false
        var nextTool = 0
    }

    companion object {
        /** `/api/health` is the only unauthenticated endpoint; `true` means the bridge is listening. */
        fun isHealthy(httpUrl: String, timeoutMs: Int = 1_000): Boolean = try {
            val connection = URL("${httpUrl.trimEnd('/')}/api/health").openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "GET"
            connection.useCaches = false
            try {
                connection.responseCode == HttpURLConnection.HTTP_OK
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }
}

private fun JsonObject.str(key: String): String = when (val element = this[key]) {
    null, is JsonNull -> ""
    is JsonPrimitive -> element.contentOrNull ?: element.content
    else -> element.toString()
}

private fun JsonObject.int(key: String): Int = str(key).toIntOrNull() ?: 0

private fun JsonObject.long(key: String): Long = str(key).toDoubleOrNull()?.toLong() ?: 0L

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.array(key: String): List<JsonElement>? = (this[key] as? JsonArray)?.toList()
