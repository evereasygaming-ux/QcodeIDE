package dev.blamspot.jcode.core.agent.agy

import dev.blamspot.jcode.core.agent.AgentEvent
import dev.blamspot.jcode.core.agent.AgentToolCall
import dev.blamspot.jcode.core.agent.AgentToolStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Wire helpers for the `agy acp` JSON-RPC stream (Agent Client Protocol v1).
 *
 * Only the notifications the chat pane needs are mapped here: assistant/thought chunks, tool calls,
 * plans, and permission requests. Everything else (commands list, mode/title updates) is ignored.
 */
internal object AgyAcpProtocol {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    data class PermissionOption(val id: String, val kind: String)

    data class PermissionInfo(
        val requestId: String,
        val jsonId: JsonElement,
        val sessionId: String,
        val call: AgentToolCall,
        val options: List<PermissionOption>,
    )

    fun parseLine(line: String): JsonObject? =
        runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()

    /** JSON-RPC ids are strings or numbers; use one key type for both. */
    fun idKey(id: JsonElement?): String? = when (id) {
        null -> null
        is JsonPrimitive -> id.contentOrNull
        else -> id.toString()
    }

    fun sessionUpdate(message: JsonObject): List<AgentEvent> {
        val params = message.acpObj("params") ?: return emptyList()
        val update = params.acpObj("update") ?: return emptyList()
        return when (update.acpStr("sessionUpdate")) {
            "agent_message_chunk" -> {
                val text = extractText(update["content"])
                if (text.isEmpty()) emptyList() else listOf(AgentEvent.AssistantText(text))
            }

            "agent_thought_chunk" -> {
                val text = extractText(update["content"])
                if (text.isEmpty()) emptyList() else listOf(AgentEvent.Thinking(text))
            }

            "tool_call" -> listOf(AgentEvent.ToolUse(toolCall(update)))

            "tool_call_update" -> {
                val call = toolCall(update)
                if (call.status == AgentToolStatus.COMPLETED || call.status == AgentToolStatus.FAILED) {
                    listOf(
                        AgentEvent.ToolUse(call),
                        AgentEvent.ToolResult(
                            toolUseId = call.id,
                            output = toolOutput(update),
                            isError = call.status == AgentToolStatus.FAILED,
                        ),
                    )
                } else {
                    listOf(AgentEvent.ToolUse(call))
                }
            }

            "plan" -> {
                val text = update.acpArr("entries").orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .map { it.acpStr("content").ifBlank { it.acpStr("title") } }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                if (text.isBlank()) emptyList() else listOf(AgentEvent.Thinking(text))
            }

            else -> emptyList()
        }
    }

    fun permission(message: JsonObject): PermissionInfo? {
        val jsonId = message["id"] ?: return null
        val requestId = idKey(jsonId) ?: return null
        val params = message.acpObj("params") ?: return null
        val toolCallJson = params.acpObj("toolCall") ?: return null
        val call = toolCall(toolCallJson).let {
            if (it.id.isBlank()) it.copy(id = "permission_$requestId") else it
        }
        val options = params.acpArr("options").orEmpty()
            .mapNotNull { it as? JsonObject }
            .map { PermissionOption(it.acpStr("optionId"), it.acpStr("kind")) }
            .filter { it.id.isNotBlank() }
        return PermissionInfo(
            requestId = requestId,
            jsonId = jsonId,
            sessionId = params.acpStr("sessionId"),
            call = call,
            options = options,
        )
    }

    fun chooseOption(options: List<PermissionOption>, allow: Boolean): String? {
        val preferred = if (allow) {
            listOf("allow_once", "allow_always", "allow")
        } else {
            listOf("reject_once", "reject_always", "reject", "deny")
        }
        for (kind in preferred) {
            options.firstOrNull { it.kind == kind }?.let { return it.id }
        }
        val prefix = if (allow) "allow" else "reject"
        options.firstOrNull { it.kind.startsWith(prefix) }?.let { return it.id }
        return if (allow) options.firstOrNull()?.id else options.lastOrNull()?.id
    }

    fun toolCall(update: JsonObject): AgentToolCall {
        val id = update.acpStr("toolCallId").ifBlank { update.acpStr("tool_call_id") }
        val name = update.acpStr("name")
            .ifBlank { update.acpStr("title") }
            .ifBlank { update.acpStr("kind") }
            .ifBlank { "tool" }
        val input = update["rawInput"] ?: update["raw_input"] ?: update["args"] ?: update["input"]
        val status = statusOf(update.acpStr("status"))
        return AgentToolCall(
            id = id,
            name = name,
            inputJson = input?.toString() ?: "{}",
            status = status,
            output = toolOutput(update).takeIf { it.isNotBlank() },
            isError = status == AgentToolStatus.FAILED,
        )
    }

    private fun toolOutput(update: JsonObject): String {
        val raw = update["rawOutput"] ?: update["raw_output"]
        val fromRaw = when (raw) {
            is JsonObject -> raw.acpStr("output").ifBlank { raw.acpStr("Output") }
            is JsonPrimitive -> raw.contentOrNull.orEmpty()
            else -> ""
        }
        if (fromRaw.isNotBlank()) return fromRaw
        val fromContent = extractText(update["content"])
        if (fromContent.isNotBlank()) return fromContent
        return when (raw) {
            is JsonObject -> raw.toString()
            is JsonPrimitive -> raw.contentOrNull.orEmpty()
            else -> ""
        }
    }

    private fun extractText(element: JsonElement?): String = when (element) {
        null -> ""
        is JsonArray -> element.joinToString("") { extractText(it) }
        is JsonObject -> when {
            element.acpStr("type") == "text" -> element.acpStr("text")
            element.containsKey("content") -> extractText(element["content"])
            element.acpStr("type") == "diff" -> element.acpStr("newText")
            else -> ""
        }

        is JsonPrimitive -> element.contentOrNull.orEmpty()
        else -> ""
    }

    private fun statusOf(raw: String): AgentToolStatus = when (raw.lowercase()) {
        "completed", "complete", "success", "done" -> AgentToolStatus.COMPLETED
        "failed", "error" -> AgentToolStatus.FAILED
        "in_progress", "running", "active" -> AgentToolStatus.RUNNING
        "pending" -> AgentToolStatus.PENDING
        else -> AgentToolStatus.PENDING
    }
}

internal fun JsonObject.acpStr(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.acpObj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.acpArr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.acpLong(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
