package dev.blamspot.jcode.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaudeStreamJsonTest {

    @Test
    fun `init reports session and model`() {
        val events = ClaudeStreamJson.parse(
            """{"type":"system","subtype":"init","session_id":"abc","model":"claude-sonnet-4"}""",
        )
        val session = assertIs<AgentEvent.Session>(events.single())
        assertEquals("abc", session.id)
        assertEquals("claude-sonnet-4", session.model)
    }

    @Test
    fun `assistant text block appends to the bubble`() {
        val events = ClaudeStreamJson.parse(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"Hi there"}]}}""",
        )
        val text = assertIs<AgentEvent.AssistantText>(events.single())
        assertEquals("Hi there", text.text)
        assertEquals(false, text.replace)
    }

    @Test
    fun `tool use block becomes a tool call`() {
        val events = ClaudeStreamJson.parse(
            """
            {"type":"assistant","message":{"content":[
              {"type":"tool_use","id":"tu_1","name":"Read","input":{"file_path":"/tmp/a.txt"}}
            ]}}
            """.trimIndent(),
        )
        val call = assertIs<AgentEvent.ToolUse>(events.single()).call
        assertEquals("tu_1", call.id)
        assertEquals("Read", call.name)
        assertTrue(call.inputJson.contains("/tmp/a.txt"))
    }

    @Test
    fun `tool result maps to output and error flag`() {
        val events = ClaudeStreamJson.parse(
            """
            {"type":"user","message":{"content":[
              {"type":"tool_result","tool_use_id":"tu_1","is_error":true,"content":"boom"}
            ]}}
            """.trimIndent(),
        )
        val result = assertIs<AgentEvent.ToolResult>(events.single())
        assertEquals("tu_1", result.toolUseId)
        assertEquals("boom", result.output)
        assertTrue(result.isError)
    }

    @Test
    fun `partial text delta appends`() {
        val events = ClaudeStreamJson.parse(
            """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hel"}}}""",
        )
        val text = assertIs<AgentEvent.AssistantText>(events.single())
        assertEquals("Hel", text.text)
        assertEquals(false, text.replace)
    }

    @Test
    fun `permission control request surfaces the tool`() {
        val events = ClaudeStreamJson.parse(
            """
            {"type":"control_request","request_id":"req_1","request":{
              "subtype":"can_use_tool","tool_name":"Bash","tool_use_id":"tu_2","input":{"command":"ls"}
            }}
            """.trimIndent(),
        )
        val permission = assertIs<AgentEvent.PermissionRequest>(events.single())
        assertEquals("req_1", permission.requestId)
        assertEquals("tu_2", permission.call.id)
        assertEquals("Bash", permission.call.name)
    }

    @Test
    fun `result ends the turn and reports errors`() {
        val ok = ClaudeStreamJson.parse("""{"type":"result","subtype":"success"}""")
        assertEquals(AgentEvent.TurnComplete, ok.single())

        val failed = ClaudeStreamJson.parse(
            """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"no such tool"}""",
        )
        assertIs<AgentEvent.Error>(failed.first())
        assertEquals(AgentEvent.TurnComplete, failed.last())
    }

    @Test
    fun `malformed and unknown lines are ignored`() {
        assertEquals(emptyList(), ClaudeStreamJson.parse("not json"))
        assertEquals(emptyList(), ClaudeStreamJson.parse("""{"type":"something_new"}"""))
        assertEquals(emptyList(), ClaudeStreamJson.parse("   "))
    }

    @Test
    fun `user message escapes newlines and quotes`() {
        val encoded = ClaudeStreamJson.userMessage("say \"hi\"\nthen stop")
        assertTrue(encoded.contains("\\n"))
        assertTrue(encoded.contains("\\\"hi\\\""))
        assertTrue(encoded.contains("\"role\":\"user\""))
    }

    @Test
    fun `control response carries the verdict`() {
        assertTrue(ClaudeStreamJson.controlResponse("req_1", true).contains("\"behavior\":\"allow\""))
        assertTrue(ClaudeStreamJson.controlResponse("req_1", false).contains("\"behavior\":\"deny\""))
    }

    @Test
    fun `control allow echoes the input and tool use id`() {
        val encoded = ClaudeStreamJson.controlResponse(
            requestId = "req_1",
            allow = true,
            toolUseId = "tu_1",
            inputJson = """{"command":"ls"}""",
        )
        assertTrue(encoded.contains("\"subtype\":\"success\""))
        assertTrue(encoded.contains("\"toolUseID\":\"tu_1\""))
        assertTrue(encoded.contains("\"updatedInput\":{\"command\":\"ls\"}"))
    }

    @Test
    fun `streamed text is not repeated by the completed message`() {
        val session = ClaudeStreamSession()
        assertTrue(session.parse(MESSAGE_START).isEmpty())
        assertEquals("Hel", assertIs<AgentEvent.AssistantText>(session.parse(textDelta("Hel")).single()).text)
        assertEquals("lo", assertIs<AgentEvent.AssistantText>(session.parse(textDelta("lo")).single()).text)
        val completed = session.parse(assistantText("Hello"))
        assertTrue(completed.none { it is AgentEvent.AssistantText })
    }

    @Test
    fun `each new message streams again`() {
        val session = ClaudeStreamSession()
        session.parse(MESSAGE_START)
        session.parse(textDelta("first"))
        session.parse(assistantText("first"))
        session.parse(MESSAGE_START)
        assertEquals("sec", assertIs<AgentEvent.AssistantText>(session.parse(textDelta("sec")).single()).text)
        assertTrue(session.parse(assistantText("second")).none { it is AgentEvent.AssistantText })
    }

    @Test
    fun `completed text still arrives when the cli sends no deltas`() {
        val session = ClaudeStreamSession()
        val events = session.parse(assistantText("Hi"))
        assertEquals("Hi", assertIs<AgentEvent.AssistantText>(events.single()).text)
    }

    private fun textDelta(text: String): String =
        """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"$text"}}}"""

    private fun assistantText(text: String): String =
        """{"type":"assistant","message":{"content":[{"type":"text","text":"$text"}]}}"""

    private companion object {
        const val MESSAGE_START =
            """{"type":"stream_event","event":{"type":"message_start","message":{}}}"""
    }
}
