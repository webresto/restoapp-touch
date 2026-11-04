@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.script

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.protocol.ScriptRef
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.restoapp.render.RestoServer
import cx.m42.restoapp.render.ServerStatus
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The screen of a script app in a window: the rows it drew, and the order the person drags them into.
 *
 * Dragging is the host's: the script hears about it once, on the drop, as `{from, to}` — so this test
 * drags a handle with the mouse and then reads back what the script was told.
 */
class ScriptScreenTest {

    private val calls = mutableListOf<Pair<String, JsonObject>>()

    /** The two links of a menu, as the server would hand them over. */
    private fun items() = buildJsonObject {
        put("item", buildJsonObject {
            put("id", "m1")
            put("name", "Шапка")
            put("items", buildJsonArray {
                add(buildJsonObject { put("label", "Меню"); put("link", "/menu") })
                add(buildJsonObject { put("label", "Акции"); put("link", "/stocks") })
                add(buildJsonObject { put("label", "О нас"); put("link", "/static/about") })
            })
        })
    }

    private val bundle = """
        app({
          id: "navigation",
          routes: {
            menu: {
              async enter(ctx) {
                const { item } = await ctx.server.call("navigation.menu", { id: "m1" });
                return { items: item.items, order: item.items.map((i) => i.label).join(",") };
              },
              view: (s) => ui.column([
                ui.text(s.order),
                ui.list({
                  items: s.items.map((item, index) => ({ key: String(index), title: item.label, subtitle: item.link })),
                  onReorder: (move) => {
                    const next = [...s.items];
                    const [moved] = next.splice(move.from, 1);
                    next.splice(move.to, 0, moved);
                    return { items: next, order: next.map((i) => i.label).join(",") };
                  },
                }),
              ]),
            },
          },
        });
    """.trimIndent()

    /** A server of one method, enough for the screen under test. */
    private inner class FakeServer : RestoServer {
        private val declared = DeclaredApp(
            id = "navigation",
            version = "0.1.0",
            title = "Навигация",
            home = "navigation.menu",
            script = ScriptRef("/api/mobile/app-assets/navigation/${"a".repeat(64)}.js", "a".repeat(64), 1),
        )
        override val app: StateFlow<DeclaredApp?> = MutableStateFlow(declared)
        override val facts: StateFlow<ServerFacts?> = MutableStateFlow(ServerFacts("Test", null, "RUB", "Europe/Moscow", "ru"))
        override val places: StateFlow<List<Place>> = MutableStateFlow(emptyList())
        override val place: StateFlow<Place?> = MutableStateFlow(null)
        override val status: StateFlow<ServerStatus> = MutableStateFlow(ServerStatus.Online)
        override val notices: SharedFlow<String> = MutableSharedFlow()
        override fun selectPlace(id: String) = Unit
        override suspend fun call(method: String, params: JsonObject): JsonElement {
            calls += method to params
            return items()
        }
    }

    @Test
    fun aRowIsDraggedIntoItsPlaceAndTheScriptIsToldOnce() {
        val server = FakeServer()
        runAppTest(
            ScriptApp(server.app.value!!, "ru") { bundle },
            prepare = { it.provide(RestoServer.Key, server) },
        ) {
            ui.waitUntil(timeoutMillis = 20_000L) {
                runCatching { ui.onNodeWithTag("script:0:text").assertExists() }.isSuccess
            }
            // What the script drew: three rows in the order the server gave them.
            ui.onNodeWithTag("script:0:text").assertExists()
            assertEquals(listOf("navigation.menu"), calls.map { it.first })
            assertEquals("Меню,Акции,О нас", drawnOrder())

            // Drag the first row's handle down past one row and drop it.
            val first = ui.onNodeWithTag("script:1:row-0")
            val height = first.fetchSemanticsNode().size.height.toFloat()
            ui.onNodeWithTag("script:1:row-0:drag").performMouseInput {
                moveTo(center)
                press()
                moveTo(center + Offset(0f, height * 0.6f))
                moveTo(center + Offset(0f, height * 1.2f))
                release()
            }
            ui.waitForIdle()

            // The script was told once, and what it drew after that is the new order.
            ui.waitUntil(timeoutMillis = 10_000L) { drawnOrder() == "Акции,Меню,О нас" }
            assertEquals("Акции,Меню,О нас", drawnOrder())
            // Reordering is the host's work: nothing went to the server for it.
            assertEquals(listOf("navigation.menu"), calls.map { it.first })
        }
    }

    private fun AppTestScope<*>.drawnOrder(): String =
        ui.onNodeWithTag("script:0:text").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString("") { line -> line.text }
}
