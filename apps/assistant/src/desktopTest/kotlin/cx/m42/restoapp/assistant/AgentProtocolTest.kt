package cx.m42.restoapp.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The panel's transport as runtime.ts reads it: SSE frames, the events, the history, the session. */
class AgentProtocolTest {

    @Test
    fun `sse frames are split on empty lines and keep their id`() {
        val frame = SseFrame()
        val lines = "retry: 3000\n\nevent: connected\ndata: {\"runId\":\"r\"}\n\nid: 0\nevent: agent\ndata: {\"type\":\"text.delta\",\ndata: \"text\":\"Hi\"}\n\n: comment\n"
        val events = lines.split("\n").mapNotNull(frame::feed)
        assertEquals(listOf("connected", "agent"), events.map { it.name })
        assertEquals("0", events[1].id)
        assertEquals("{\"type\":\"text.delta\",\n\"text\":\"Hi\"}", events[1].data)
    }

    @Test
    fun `events grow one bubble the way the panel does`() {
        val stream = listOf(
            """{"type":"turn.start","turnNumber":1}""",
            """{"type":"tool.start","toolCallId":"c1","toolName":"list_tools","input":{}}""",
            """{"type":"tool.done","toolCallId":"c1","toolName":"list_tools","output":{"groups":[]}}""",
            """{"type":"text.delta","text":"Hel"}""",
            """{"type":"text.delta","text":"lo"}""",
            """{"type":"text.done","text":"Hello"}""",
            """{"type":"text.delta","text":"Again"}""",
            """{"type":"done","result":"complete"}""",
            """{"type":"done"}""",
        ).map { AgentEvent.of(Json.parseToJsonElement(it)) }
        assertIs<AgentEvent.AgentDone>(stream[7])
        assertIs<AgentEvent.RunDone>(stream[8])
        val parts = stream.fold(emptyList<Part>()) { acc, e -> acc.apply(e) }
        assertEquals(3, parts.size)
        val tool = assertIs<Part.Tool>(parts[0])
        assertTrue(tool.finished)
        assertEquals(Part.Text("Hello", done = true), parts[1])
        assertEquals("Again", (parts[2] as Part.Text).text)
    }

    @Test
    fun `navigate and errors are their own events`() {
        val nav = AgentEvent.of(Json.parseToJsonElement("""{"type":"ui.method","method":"navigate","action":"navigate","input":{"href":"/console/services"}}"""))
        assertEquals(AgentEvent.Navigate("/console/services"), nav)
        assertEquals(AgentEvent.Failed("boom"), AgentEvent.of(Json.parseToJsonElement("""{"type":"error","error":"boom"}""")))
        val err = AgentEvent.of(Json.parseToJsonElement("""{"type":"tool.error","toolCallId":"x","toolName":"t","error":"no"}"""))
        val parts = emptyList<Part>().apply(err)
        assertTrue((parts.single() as Part.Tool).isError)
    }

    @Test
    fun `history merges an assistant message with the tool messages after it`() {
        val messages = Json.parseToJsonElement(
            """[
              {"role":"system","content":"x"},
              {"role":"User","content":"Покажи серверы"},
              {"role":"assistant","content":[{"type":"text","text":"Смотрю"},{"type":"tool-call","toolCallId":"c1","toolName":"call_tool","input":{"tool_name":"cloud.resources.list"}}]},
              {"role":"tool","content":[{"type":"tool-result","toolCallId":"c1","toolName":"call_tool","output":{"type":"json","value":{"items":[]}}}]},
              {"role":"assistant","content":"Серверов нет."},
              {"role":"user","content":[{"type":"text","text":"<attachment name=\"menu.csv\" saved_file_id=\"1\">\na,b\n</attachment>"},{"type":"text","text":"Загрузи"}]}
            ]""",
        ).jsonArray
        val chat = chatOf(messages)
        assertEquals(listOf(Role.User, Role.Assistant, Role.User), chat.map { it.role })
        val reply = chat[1].parts
        assertEquals(3, reply.size)
        val tool = assertIs<Part.Tool>(reply[1])
        assertEquals("call_tool", tool.name)
        assertEquals(JsonArray(emptyList()), tool.result!!.jsonObject["items"])
        assertEquals("Серверов нет.", (reply[2] as Part.Text).text)
        assertEquals(Part.Attachment("menu.csv"), chat[2].parts[0])
    }

    @Test
    fun `session, status and conversations are read leniently`() {
        val session = AgentSession.of(Json.parseToJsonElement(
            """{"transport":"adminizer-agent","model":"cloud-assistant","title":"Resto Cloud Assistant","base":"/console/api/ai-assistant/agent/cloud-assistant",
               "cookie":{"name":"adminizer_jwt","value":"jwt"},"csrf":{"cookie":"XSRF-TOKEN","header":"X-XSRF-TOKEN"},"expiresAt":"2026-11-14T11:47:01.357Z"}""",
        ))
        assertEquals("/console/api/ai-assistant/agent/cloud-assistant", session.base)
        assertEquals(1794656821357L, session.expiresAt)
        assertEquals("X-XSRF-TOKEN", session.csrfHeader)

        val status = AgentStatus.of(Json.parseToJsonElement(
            """{"state":"setup_required","lastError":"no key","schema":{"title":"A","suggestions":["one",2],
               "connectionScreens":{"setup_required":{"title":"Not connected","description":"Set the key"}}}}""",
        ))
        assertEquals(false, status.ready)
        assertEquals("Not connected", status.screenTitle)
        assertEquals(listOf("one", "2"), status.suggestions)

        val list = Conversations.of(Json.parseToJsonElement("""{"activeId":"b","conversations":[{"id":"b","title":"T","messages":[]},{"title":"no id"}]}"""))
        assertEquals("b", list.activeId)
        assertEquals(1, list.items.size)
        assertNull(AgentSession.of(JsonPrimitive("x")).csrfCookie)
    }

    @Test
    fun `iso dates without a date library`() {
        assertEquals(0L, parseIsoMillis("1970-01-01T00:00:00Z"))
        assertEquals(951782400000L, parseIsoMillis("2000-02-29T00:00:00.000Z"))
        assertEquals(951782400000L - 3 * 3600_000L, parseIsoMillis("2000-02-29T00:00:00+03:00"))
    }

    @Test
    fun `markdown of the assistant is split into blocks`() {
        val blocks = markdownBlocks("# Title\nOne\ntwo\n\n- a\n  - b\n1. first\n```\ncode\n```\n> said")
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"), MdBlock.Paragraph("One\ntwo"), MdBlock.Item("•", "a", 0), MdBlock.Item("•", "b", 1),
                MdBlock.Item("1.", "first", 0), MdBlock.Code("code"), MdBlock.Quote("said"),
            ),
            blocks,
        )
        var opened: String? = null
        val text = inline("**bold** and [the console](/console) `x`", androidx.compose.ui.graphics.Color.Blue, androidx.compose.ui.graphics.Color.Gray) { opened = it }
        assertEquals("bold and the console x", text.text)
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
        assertNull(opened)
    }
}
