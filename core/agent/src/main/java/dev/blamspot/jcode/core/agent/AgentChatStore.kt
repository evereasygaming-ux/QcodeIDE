package dev.blamspot.jcode.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** The persisted half of a chat: the feed, the CLI session to resume, and the chosen model. */
@Serializable
data class AgentSavedChat(
    val messages: List<AgentMessage> = emptyList(),
    val sessionId: String? = null,
    val model: String = "default",
)

/**
 * Saves one chat as JSON so closing the drawer or restarting the app keeps the conversation and the
 * CLI session it belongs to. Failures are swallowed: a chat that cannot be saved is still usable.
 */
class AgentChatStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(): AgentSavedChat = runCatching {
        if (!file.exists()) AgentSavedChat() else json.decodeFromString<AgentSavedChat>(file.readText())
    }.getOrDefault(AgentSavedChat())

    fun save(chat: AgentSavedChat) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(chat))
        }
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
