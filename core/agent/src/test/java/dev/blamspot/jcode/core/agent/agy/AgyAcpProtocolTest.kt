package dev.blamspot.jcode.core.agent.agy

import dev.blamspot.jcode.core.agent.AgentEvent
import dev.blamspot.jcode.core.agent.AgentToolStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgyAcpProtocolTest {

    private fun update(json: String): List<AgentEvent> {
        val line = """{"jsonrpc":"2.0","method":"session/update",""" +
            """"params":{"sessionId":"s1","update":$json}}"""
        val message = AgyAcpProtocol.parseLine(line)
        return AgyAcpProtocol.sessionUpdate(message!!)
    }

    @Test
    fun `assistant text chunk maps to delta`() {
        val events = update("""{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hello"}}""")
        assertEquals(listOf(AgentEvent.AssistantText("Hello")), events)
    }

    @Test
    fun `thought chunk maps to thinking`() {
        val events = update("""{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"Reading files"}}""")
        assertEquals(listOf(AgentEvent.Thinking("Reading files")), events)
    }

    @Test
    fun `tool call maps to tool use`() {
        val events = update(
            """{"sessionUpdate":"tool_call","toolCallId":"call_1","name":"run_command",""" +
                """"args":{"command":"ls"},"status":"in_progress","title":"Run ls","kind":"execute"}""",
        )
        val use = assertIs<AgentEvent.ToolUse>(events.single())
        assertEquals("call_1", use.call.id)
        assertEquals("run_command", use.call.name)
        assertEquals(AgentToolStatus.RUNNING, use.call.status)
        assertTrue(use.call.inputJson.contains("ls"))
    }

    @Test
    fun `completed tool update carries output`() {
        val events = update(
            """{"sessionUpdate":"tool_call_update","toolCallId":"call_1","name":"run_command",""" +
                """"status":"completed","rawOutput":{"output":"ok\n","exit_code":0},""" +
                """"content":[{"type":"content","content":{"type":"text","text":"ok\n"}}]}""",
        )
        assertEquals(2, events.size)
        val use = assertIs<AgentEvent.ToolUse>(events[0])
        assertEquals(AgentToolStatus.COMPLETED, use.call.status)
        val result = assertIs<AgentEvent.ToolResult>(events[1])
        assertEquals("call_1", result.toolUseId)
        assertEquals("ok\n", result.output)
        assertEquals(false, result.isError)
    }

    @Test
    fun `failed tool update reports error`() {
        val events = update(
            """{"sessionUpdate":"tool_call_update","toolCallId":"call_2","name":"run_command",""" +
                """"status":"failed","rawOutput":{"output":"boom","exit_code":1}}""",
        )
        val result = assertIs<AgentEvent.ToolResult>(events.last())
        assertEquals(true, result.isError)
        assertEquals("boom", result.output)
    }

    @Test
    fun `plan entries map to thinking lines`() {
        val events = update(
            """{"sessionUpdate":"plan","entries":[""" +
                """{"content":"Read files","priority":"high","status":"pending"},""" +
                """{"content":"Edit file","priority":"high","status":"pending"}]}""",
        )
        assertEquals(listOf(AgentEvent.Thinking("Read files\nEdit file")), events)
    }

    @Test
    fun `permission request maps options and picks allow`() {
        val line = """{"jsonrpc":"2.0","id":"1","method":"session/request_permission",""" +
            """"params":{"sessionId":"s1","toolCall":{"toolCallId":"call_3","name":"run_command",""" +
            """"args":{"command":"rm -rf build"}},"options":[""" +
            """{"optionId":"allow","name":"Allow","kind":"allow_once"},""" +
            """{"optionId":"deny","name":"Deny","kind":"reject_once"}]}}"""
        val info = AgyAcpProtocol.permission(AgyAcpProtocol.parseLine(line)!!)!!
        assertEquals("1", info.requestId)
        assertEquals("s1", info.sessionId)
        assertEquals("call_3", info.call.id)
        assertEquals("allow", AgyAcpProtocol.chooseOption(info.options, allow = true))
        assertEquals("deny", AgyAcpProtocol.chooseOption(info.options, allow = false))
    }

    @Test
    fun `json rpc ids share a key for strings and numbers`() {
        assertEquals("1", AgyAcpProtocol.idKey(JsonPrimitive("1")))
        assertEquals("1", AgyAcpProtocol.idKey(JsonPrimitive(1)))
        assertEquals(null, AgyAcpProtocol.idKey(null))
    }
}
