@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.assistant

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.restoapp.render.RestoServer
import cx.m42.restoapp.render.ServerStatus
import cx.m42.superizer.testing.runAppTest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «Ассистент» on the demo server: the declaration of the demo administrator, `assistant.session` through the host's
 * server — and the chat the panel's events draw: suggestions, a tool call folded under its name, the reply in
 * Markdown, the conversations.
 */
class AssistantAppTest {

    private class Server(private val backend: DemoBackend) : RestoServer {
        private val declaration = runBlocking { backend.describe(DemoBackend.TOKEN, "ru").value }
        val declared: DeclaredApp = declaration.apps.single { it.id == "assistant" }
        override val app: StateFlow<DeclaredApp?> = MutableStateFlow(declared)
        override val address: String = "https://cafe.example"
        override val facts: StateFlow<ServerFacts?> = MutableStateFlow(declaration.server)
        override val places: StateFlow<List<Place>> = MutableStateFlow(emptyList())
        override val place: StateFlow<Place?> = MutableStateFlow(null)
        override fun selectPlace(id: String) = Unit
        override val status = MutableStateFlow(ServerStatus.Online)
        override val notices = MutableSharedFlow<String>()
        val calls = mutableListOf<String>()
        override suspend fun call(method: String, params: JsonObject): JsonElement {
            calls += method
            return backend.call(DemoBackend.TOKEN, method, params, null, "ru").value
        }
    }

    private fun server(): Server = Server(DemoBackend(MemoryStore(), DemoBackend.TWO_POINTS, clock = { 1_700_000_000_000 }, serverName = "Демо"))

    @Test
    fun `the demo administrator has the assistant and it is drawn by this module`() {
        val s = server()
        assertTrue(AssistantApp.canDraw(s.declared))
        assertEquals("assistant", s.declared.native)
    }

    @Test
    fun `a message streams a reply with a tool call, and the conversation shows in the list`() {
        val s = server()
        runAppTest(app = AssistantApp(s.declared, "ru"), prepare = { it.provide(RestoServer.Key, s) }) {
            ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodesWithTag("assistant:welcome").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf("assistant.session"), s.calls)
            assertShown("assistant:suggestion-0")

            type("assistant:input", "Какие у тебя инструменты?")
            click("assistant:send")
            ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodesWithTag("assistant:tool-list_tools").fetchSemanticsNodes().isNotEmpty() }
            ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodes(hasText("Демо:", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            assertShown("assistant:user")
            assertShown("assistant:send")

            click("assistant:tool-list_tools")
            ui.waitUntil(timeoutMillis = 2_000) { ui.onAllNodes(hasText("Параметры")).fetchSemanticsNodes().isNotEmpty() }

            click("assistant:conversations")
            ui.waitUntil(timeoutMillis = 2_000) { ui.onAllNodesWithTag("assistant:conversation-demo-1").fetchSemanticsNodes().isNotEmpty() }
            click("assistant:new")
            ui.waitUntil(timeoutMillis = 2_000) { ui.onAllNodesWithTag("assistant:welcome").fetchSemanticsNodes().isNotEmpty() }
            click("assistant:conversations")
            ui.waitUntil(timeoutMillis = 2_000) { ui.onAllNodesWithTag("assistant:delete-demo-1").fetchSemanticsNodes().isNotEmpty() }
            click("assistant:conversation-demo-1")
            ui.waitUntil(timeoutMillis = 2_000) { ui.onAllNodesWithTag("assistant:reply").fetchSemanticsNodes().isNotEmpty() }
            // One session for the whole chat: the demo needs no other call.
            assertEquals(listOf("assistant.session"), s.calls)
        }
    }

    @Test
    fun `a link of the reply opens on the context's address`() {
        val s = server()
        runAppTest(app = AssistantApp(s.declared, "ru"), prepare = { it.provide(RestoServer.Key, s) }) {
            ui.waitUntil(timeoutMillis = 5_000) { ui.onAllNodesWithTag("assistant:welcome").fetchSemanticsNodes().isNotEmpty() }
            (instance as AssistantInstance).open("/admin/model/dish")
            (instance as AssistantInstance).open("javascript:alert(1)")
            assertEquals(listOf("https://cafe.example/admin/model/dish"), runtime.navigation.urls)
        }
    }
}

/** The demo's data in memory. */
class MemoryStore : cx.m42.restoapp.demo.DemoStore {
    private var text: String? = null
    override suspend fun read(): String? = text
    override suspend fun write(text: String) {
        this.text = text
    }
}
