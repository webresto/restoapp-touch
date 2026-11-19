@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.render

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.WindowClass
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.testing.FakeAppRuntime
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A server of one app from a JSON declaration, answering every call from [answer]. */
private class StaticServer(declaration: String, private val answer: (String, JsonObject) -> JsonElement) : RestoServer {
    override val app: StateFlow<DeclaredApp?> = MutableStateFlow(ProtocolJson.decodeFromString(DeclaredApp.serializer(), declaration))
    override val facts: StateFlow<ServerFacts?> = MutableStateFlow(null)
    override val places: StateFlow<List<Place>> = MutableStateFlow(emptyList())
    override val place: StateFlow<Place?> = MutableStateFlow(null)
    override val status: MutableStateFlow<ServerStatus> = MutableStateFlow(ServerStatus.Online)
    override val notices: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 8)
    val calls = mutableListOf<Pair<String, JsonObject>>()
    override fun selectPlace(id: String) = Unit
    override suspend fun call(method: String, params: JsonObject): JsonElement {
        calls += method to params
        return answer(method, params)
    }
}

/**
 * An older server (03-render.md, «Проверка»): a declaration with nothing but the keys of the first
 * contract — no `search`, `chips`, `groupBy`, `badge`, `empty`, `confirm`, `tones`, `_links`, no facts,
 * a list answer without `counts` or `total`. The renderer draws it and acts on it without a crash.
 */
class OldServerTest {

    private val things = listOf(
        """{"id":"1","name":"Первая","note":"Заметка один","sum":10,"_actions":[{"method":"old.setState"}]}""",
        """{"id":"2","name":"Вторая","note":"Заметка два","sum":20}""",
    ).map { ProtocolJson.parseToJsonElement(it).jsonObject }

    private fun answer(method: String, params: JsonObject): JsonElement = when (method) {
        "old.list" -> ProtocolJson.parseToJsonElement("""{"items":[${things.joinToString(",")}]}""")
        else -> things.first { it["id"]?.jsonPrimitive?.content == params["id"]?.jsonPrimitive?.content }
    }

    /** [models] — the fields of `thing` as JSON, or none at all. */
    private fun declaration(models: String, extra: String = "") = """
        {"id":"old","version":"1.0.0","title":"Старое","home":"old.list",$models
         "methods":[
          {"name":"old.list","kind":"query","title":"Вещи","model":"thing","ui":{"view":"list","item":{"title":"name","value":"sum"},"open":"old.get"}},
          {"name":"old.get","kind":"query","title":"Вещь","model":"thing",
           "input":{"type":"object","properties":{"id":{"type":"string"}}},"ui":{"view":"detail"}},
          {"name":"old.setState","kind":"mutation","title":"Состояние",
           "input":{"type":"object","properties":{"id":{"type":"string"},"state":{"type":"string","enum":["on","off"]}}},
           "ui":{"view":"action","on":"thing","placement":["row","detail"],"control":{"type":"buttons","param":"state"}}}$extra
         ]}
    """.trimIndent()

    private val fields = """"models":{"thing":{"fields":[{"name":"name","title":"Название"},{"name":"note","title":"Заметка"}]}},"""

    @Test
    fun aMinimalDeclarationDrawsItsListItsRowMenuAndItsRecord() {
        val server = StaticServer(declaration(fields), ::answer)
        runAppTest(ServerApp(server.app.value!!, "ru"), prepare = { it.provide(RestoServer.Key, server) }) {
            idle()
            assertShown("old:list")
            ui.onNodeWithText("Первая").assertExists()
            ui.onNodeWithText("Вторая").assertExists()
            // The row's buttons are behind its `⋯`, and act as the record's would.
            assertAbsent("old:row-2:more")
            click("old:row-1:more")
            idle()
            click("old:row-1:old.setState-off")
            idle()
            assertEquals("off", server.calls.last { it.first == "old.setState" }.second["state"]?.jsonPrimitive?.content)

            click("old:row-1")
            idle()
            assertShown("old:detail")
            ui.onNodeWithText("Заметка один").assertExists()
            assertShown("old:old.setState-on")
        }
    }

    /** A form of a record: its choice is the kit's select, its «Сохранить» in the phone's action bar. */
    @Test
    fun aRecordsFormSavesFromTheActionBar() {
        val update = """,
          {"name":"old.update","kind":"mutation","title":"Изменить",
           "input":{"type":"object","required":["note"],"properties":{"id":{"type":"string"},"note":{"type":"string","title":"Заметка"},
             "kind":{"type":"string","title":"Вид","enum":["a","b"],"x-titles":{"a":"Первый","b":"Второй"}}}},
           "ui":{"view":"action","on":"thing","placement":["detail"],"control":{"type":"form"}}}"""
        val withForm = things.map { JsonObject(it + ("_actions" to ProtocolJson.parseToJsonElement("""[{"method":"old.update"}]"""))) }
        val server = StaticServer(declaration(fields, update)) { method, params ->
            when (method) {
                "old.list" -> ProtocolJson.parseToJsonElement("""{"items":[${withForm.joinToString(",")}]}""")
                else -> withForm.first { it["id"]?.jsonPrimitive?.content == (params["id"]?.jsonPrimitive?.content ?: "1") }
            }
        }
        runAppTest(ServerApp(server.app.value!!, "ru"), prepare = { it.provide(RestoServer.Key, server) }) {
            idle()
            click("old:row-1")
            idle()
            click("old:old.update")
            idle()
            assertShown("old:old.update:form")
            type("old:old.update:form:note", "новая")
            click("old:old.update:form:kind")
            idle()
            click("old:old.update:form:kind-b")
            idle()
            ui.onNodeWithText("Второй").assertExists()
            click("old:old.update:form:submit")
            idle()
            val sent = server.calls.last { it.first == "old.update" }.second
            assertEquals("1", sent["id"]?.jsonPrimitive?.content)
            assertEquals("b", sent["kind"]?.jsonPrimitive?.content)
            assertTrue(sent["note"]?.jsonPrimitive?.content.orEmpty().contains("новая"))
            assertAbsent("old:old.update:form")
        }
    }

    /** Without any models: no titles to head the columns with, and the first column says what the rows are. */
    @Test
    fun aWideTableOfADeclarationWithoutModelsHeadsItsFirstColumnWithTheRecordsNoun() {
        val sumOnly = """"models":{"thing":{"fields":[{"name":"sum","title":"Сумма"}]}},"""
        val server = StaticServer(declaration(sumOnly), ::answer)
        wide(server) {
            waitForIdle()
            onNodeWithTag("old:list").assertExists()
            onNodeWithText("Вещь").assertExists()
            onNodeWithText("Сумма").assertExists()
            onNodeWithTag("old:row-1:more").performClick()
            waitForIdle()
            onNodeWithTag("old:row-1:old.setState-on").performClick()
            waitForIdle()
            assertTrue(server.calls.any { it.first == "old.setState" })
        }
        // And without the `models` key at all, the list still draws.
        val bare = StaticServer(declaration(""), ::answer)
        wide(bare) {
            waitForIdle()
            onNodeWithText("Первая").assertExists()
        }
    }

    /** [runAppTest], in a window as wide as a desktop's: the table, the menus as dropdowns. */
    private fun wide(server: RestoServer, block: ComposeUiTest.() -> Unit) = runComposeUiTest {
        val app = ServerApp(server.app.value!!, "ru")
        val runtime = FakeAppRuntime(app.id, TestScope(UnconfinedTestDispatcher()), declaredServices = app.manifest.requires)
        runtime.provide(RestoServer.Key, server)
        val instance = app.launch(runtime, app.configSpec.default)
        runTest { instance.onLaunch() }
        setContent {
            CompositionLocalProvider(LocalAppRuntime provides runtime, LocalWindowClass provides WindowClass.Expanded) {
                androidx.compose.foundation.layout.Box(Modifier.testTag("old:root")) { instance.Content() }
            }
        }
        block()
    }
}

/**
 * A native screen of an action registered as [NativePresentation.Overlay]: the list stays drawn under it,
 * it presents itself as the kit's dialog, and the question about its unsaved edits comes up over that dialog.
 */
class NativeOverlayTest {

    private val declaration = """
        {"id":"ov","version":"1.0.0","title":"Слой","home":"ov.list",
         "methods":[
          {"name":"ov.list","kind":"query","title":"Вещи","ui":{"view":"list","item":{"title":"name"}}},
          {"name":"ov.import","kind":"mutation","title":"Импорт",
           "ui":{"view":"action","on":"list","placement":["list"],"control":{"type":"native","screen":"test-sheet"}}}
         ]}
    """.trimIndent()

    @Test
    fun anOverlayKeepsThePageAndTheUnsavedQuestionComesOverIt() {
        NativeActions.register("test-sheet", NativePresentation.Overlay) { scope ->
            HoldUnsaved(dirty = true, hold = scope.hold, save = null)
            cx.m42.superizer.kit.Dialog(
                onDismiss = scope.leave,
                title = "Лист импорта",
                footer = { cx.m42.superizer.kit.Button(text = "Закрыть", onClick = scope.leave, modifier = Modifier.testTag("sheet:close")) },
            )
        }
        try {
            val server = StaticServer(declaration) { _, _ -> ProtocolJson.parseToJsonElement("""{"items":[{"id":"1","name":"Первая"}]}""") }
            runAppTest(ServerApp(server.app.value!!, "ru"), prepare = { it.provide(RestoServer.Key, server) }) {
                idle()
                click("ov:add")
                idle()
                // The list is still there, under the dialog.
                assertShown("ov:list")
                ui.onNodeWithText("Первая").assertExists()
                ui.onNodeWithText("Лист импорта").assertExists()

                click("sheet:close")
                idle()
                assertShown("unsaved:question")
                // Answered — so it is the top layer, over the app's dialog.
                click("unsaved:stay")
                idle()
                assertAbsent("unsaved:question")
                ui.onNodeWithText("Лист импорта").assertExists()

                click("sheet:close")
                idle()
                click("unsaved:leave")
                idle()
                ui.onNodeWithText("Лист импорта").assertDoesNotExist()
                assertShown("ov:list")
            }
        } finally {
            NativeActions.clear()
        }
    }
}
