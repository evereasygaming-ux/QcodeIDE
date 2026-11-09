package dev.blamspot.jcode.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * The subset of Claude Code's `stream-json` protocol QCode consumes.
 *
 * Deliberately tolerant: every field is optional, unknown event types are ignored, and a malformed
 * line costs one event rather than the turn. The CLI is a moving target, so parsing is by shape and
 * name rather than by a fixed serializable schema.
 */
object ClaudeStreamJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parse one stdout line into zero or more UI events, without any cross-line state. */
    fun parse(line: String): List<AgentEvent> = objectOf(line)?.let(::parseObject).orEmpty()

    internal fun objectOf(line: String): JsonObject? {
        if (line.isBlank()) return null
        return runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
    }

    internal fun typeOf(obj: JsonObject): String? = obj.str("type")

    internal fun streamEventTypeOf(obj: JsonObject): String? =
        (obj["event"] as? JsonObject)?.str("type")

    internal fun parseObject(obj: JsonObject): List<AgentEvent> = when (obj.str("type")) {
        "system" -> if (obj.str("subtype") == "init") {
            listOf(AgentEvent.Session(obj.str("session_id").orEmpty(), obj.str("model")))
        } else {
            emptyList()
        }
        "assistant" -> parseAssistant(obj)
        "user" -> parseUser(obj)
        "stream_event" -> parseStreamEvent(obj)
        "result" -> parseResult(obj)
        "control_request" -> parsePermission(obj)
        else -> emptyList()
    }

    /** The `user` frame the CLI's `--input-format stream-json` mode expects. */
    fun userMessage(prompt: String, uuid: String = UUID.randomUUID().toString()): String = buildJsonObject {
        put("type", "user")
        put("uuid", uuid)
        put("session_id", "")
        putJsonObject("message") {
            put("role", "user")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", prompt)
                }
            }
        }
    }.toString()

    /** The answer to a `can_use_tool` control request. */
    fun controlResponse(
        requestId: String,
        allow: Boolean,
        toolUseId: String? = null,
        inputJson: String? = null,
    ): String = buildJsonObject {
        put("type", "control_response")
        putJsonObject("response") {
            put("request_id", requestId)
            put("subtype", "success")
            putJsonObject("response") {
                put("behavior", if (allow) "allow" else "deny")
                if (allow) {
                    val input = inputJson?.let {
                        runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
                    }
                    if (input != null) put("updatedInput", input)
                } else {
                    put("message", "Denied by the user")
                }
                if (toolUseId != null) put("toolUseID", toolUseId)
            }
        }
    }.toString()

    private fun parseAssistant(obj: JsonObject): List<AgentEvent> {
        val message = obj["message"] as? JsonObject ?: return emptyList()
        val content = message["content"] as? JsonArray ?: return emptyList()
        val out = ArrayList<AgentEvent>()
        val text = StringBuilder()
        for (element in content) {
            val block = element as? JsonObject ?: continue
            when (block.str("type")) {
                "text" -> block.str("text")?.let { text.append(it) }
                "thinking" -> block.str("thinking")?.takeIf { it.isNotBlank() }?.let {
                    out += AgentEvent.Thinking(it, replace = false)
                }
                "tool_use" -> {
                    val id = block.str("id") ?: continue
                    val name = block.str("name") ?: "tool"
                    val input = (block["input"] as? JsonObject)?.toString() ?: "{}"
                    out += AgentEvent.ToolUse(AgentToolCall(id = id, name = name, inputJson = input))
                }
            }
        }
        // Blocks are appended rather than replacing: a turn can contain several assistant messages
        // (text, tool call, more text), and each one is new content for the same bubble.
        if (text.isNotEmpty()) out += AgentEvent.AssistantText(text.toString(), replace = false)
        (message["usage"] as? JsonObject)?.let { usage ->
            val input = usage.long("input_tokens") + usage.long("cache_read_input_tokens")
            val output = usage.long("output_tokens")
            if (input > 0 || output > 0) out += AgentEvent.Usage(input, output)
        }
        return out
    }

    private fun parseUser(obj: JsonObject): List<AgentEvent> {
        val message = obj["message"] as? JsonObject ?: return emptyList()
        val content = message["content"] as? JsonArray ?: return emptyList()
        val out = ArrayList<AgentEvent>()
        for (element in content) {
            val block = element as? JsonObject ?: continue
            if (block.str("type") != "tool_result") continue
            val id = block.str("tool_use_id") ?: continue
            val isError = (block["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false
            out += AgentEvent.ToolResult(id, contentToString(block["content"]), isError)
        }
        return out
    }

    private fun parseStreamEvent(obj: JsonObject): List<AgentEvent> {
        val event = obj["event"] as? JsonObject ?: return emptyList()
        if (event.str("type") != "content_block_delta") return emptyList()
        val delta = event["delta"] as? JsonObject ?: return emptyList()
        return when (delta.str("type")) {
            "text_delta" -> delta.str("text")?.let {
                listOf(AgentEvent.AssistantText(it, replace = false))
            }.orEmpty()
            "thinking_delta" -> delta.str("thinking")?.let {
                listOf(AgentEvent.Thinking(it, replace = false))
            }.orEmpty()
            else -> emptyList()
        }
    }

    private fun parseResult(obj: JsonObject): List<AgentEvent> {
        val out = ArrayList<AgentEvent>(2)
        (obj["usage"] as? JsonObject)?.let { usage ->
            val input = usage.long("input_tokens") + usage.long("cache_read_input_tokens")
            val output = usage.long("output_tokens")
            if (input > 0 || output > 0) out += AgentEvent.Usage(input, output)
        }
        val isError = (obj["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (isError) out += AgentEvent.Error(obj.str("result") ?: obj.str("subtype") ?: "Agent turn failed")
        out += AgentEvent.TurnComplete
        return out
    }

    private fun parsePermission(obj: JsonObject): List<AgentEvent> {
        val request = obj["request"] as? JsonObject ?: return emptyList()
        if (request.str("subtype") != "can_use_tool") return emptyList()
        val requestId = obj.str("request_id") ?: return emptyList()
        val name = request.str("tool_name") ?: "tool"
        val input = (request["input"] as? JsonObject)?.toString() ?: "{}"
        val callId = request.str("tool_use_id") ?: requestId
        return listOf(
            AgentEvent.PermissionRequest(
                requestId = requestId,
                call = AgentToolCall(id = callId, name = name, inputJson = input),
            ),
        )
    }

    private fun contentToString(element: JsonElement?): String = when (element) {
        null, JsonNull -> ""
        is JsonPrimitive -> element.contentOrNull ?: element.toString()
        is JsonArray -> element.joinToString("\n") { contentToString(it) }
        is JsonObject -> element["text"]?.let { contentToString(it) } ?: element.toString()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
}
