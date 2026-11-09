package dev.blamspot.jcode.core.agent

/**
 * Stateful stream-json parser for one CLI turn.
 *
 * The CLI is run with `--include-partial-messages`, so text arrives twice: once as token deltas and
 * again in the completed `assistant` message. This tracks which part of the current message was
 * already streamed and drops the duplicate when the completed message arrives — while still letting
 * a CLI that emits no deltas deliver the full text.
 */
class ClaudeStreamSession {

    private var textStreamed = false
    private var thinkingStreamed = false

    fun parse(line: String): List<AgentEvent> {
        val obj = ClaudeStreamJson.objectOf(line) ?: return emptyList()
        return when (ClaudeStreamJson.typeOf(obj)) {
            "stream_event" -> {
                if (ClaudeStreamJson.streamEventTypeOf(obj) == "message_start") {
                    textStreamed = false
                    thinkingStreamed = false
                }
                val events = ClaudeStreamJson.parseObject(obj)
                if (events.any { it is AgentEvent.AssistantText }) textStreamed = true
                if (events.any { it is AgentEvent.Thinking }) thinkingStreamed = true
                events
            }

            "assistant" -> {
                val events = ClaudeStreamJson.parseObject(obj)
                val filtered = events.filterNot {
                    (it is AgentEvent.AssistantText && textStreamed) ||
                        (it is AgentEvent.Thinking && thinkingStreamed)
                }
                // The completed message closes this block; the next message_start also resets, but
                // resetting here keeps a turn that never sends message_start from suppressing text.
                textStreamed = false
                thinkingStreamed = false
                filtered
            }

            else -> ClaudeStreamJson.parseObject(obj)
        }
    }
}
