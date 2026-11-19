@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.render

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.Field
import cx.m42.restoapp.protocol.Formats
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.restoapp.protocol.Special
import cx.m42.restoapp.protocol.Tones
import cx.m42.superizer.kit.LocalWindowClass
import cx.m42.superizer.kit.Tone
import cx.m42.superizer.kit.WindowClass
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.testing.FakeAppRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One app from a JSON declaration, every call answered by [answer]. */
internal class HintServer(declaration: String, private val answer: (String, JsonObject) -> JsonElement) : RestoServer {
    override val app: MutableStateFlow<DeclaredApp?> = MutableStateFlow(ProtocolJson.decodeFromString(DeclaredApp.serializer(), declaration))
    override val facts: StateFlow<ServerFacts?> = MutableStateFlow(ServerFacts("S", currency = "RUB"))
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
 * The optional hints of 2026-11-18 as the renderer draws them (04-protocol.md): each where it is declared and
 * makes sense, and a wrong or unknown value drawn as no value at all.
 */
class HintsTest {

    private fun json(text: String) = ProtocolJson.parseToJsonElement(text)

    // ───────── P2: x-tones ─────────

    private val words = RenderWords("ru")

    @Test
    fun anEnumValueTakesItsToneFromXTones() {
        val field = Field(
            "state", "Статус", Formats.ENUM,
            titles = mapOf("new" to "Новый", "done" to "Готов", "odd" to "Странный"),
            tones = mapOf("new" to Tones.INFO, "done" to Tones.MUTED, "odd" to "purple"),
        )
        assertEquals(Shown("Новый", Tones.INFO), showValue(JsonPrimitive("new"), field, null, words, 0))
        assertEquals(Shown("Готов", Tones.MUTED), showValue(JsonPrimitive("done"), field, null, words, 0))
        // A tone outside the closed set is no tone; a value without one, too.
        assertEquals(Shown("Странный", null), showValue(JsonPrimitive("odd"), field, null, words, 0))
        assertEquals(Shown("other", null), showValue(JsonPrimitive("other"), field, null, words, 0))
        // Muted is the kit's own grey.
        assertEquals(Tone.Muted, toneKit(Tones.MUTED))
        // Only an enum's values have tones there; a special without a tone of its own takes the value's.
        assertNull(showValue(JsonPrimitive("new"), field.copy(format = Formats.TEXT), null, words, 0).tone)
        val special = field.copy(specials = listOf(Special(JsonPrimitive("done"), "Выполнен")))
        assertEquals(Shown("Выполнен", Tones.MUTED), showValue(JsonPrimitive("done"), special, null, words, 0))
    }

    // ───────── P1: columns ─────────

    private val things = listOf(
        """{"id":"1","name":"Первая","note":"Заметка один","sum":10,"state":"new"}""",
        """{"id":"2","name":"Вторая","note":"Заметка два","sum":20,"state":"done"}""",
        """{"id":"3","name":"Третья","note":"Заметка три","sum":30,"state":"gone"}""",
    ).map { json(it).jsonObject }

    private fun listAnswer(method: String, params: JsonObject): JsonElement =
        if (method.endsWith(".list")) json("""{"items":[${things.joinToString(",")}]}""")
        else things.first { it["id"]?.jsonPrimitive?.content == params["id"]?.jsonPrimitive?.content }

    private val thingModel = """
        "models":{"thing":{"fields":[
          {"name":"name","title":"Название"},{"name":"note","title":"Заметка"},{"name":"sum","title":"Сумма","format":"number"},
          {"name":"state","title":"Состояние","format":"enum","x-titles":{"new":"Новая","done":"Готова","gone":"Ушла"},
           "x-tones":{"new":"info","done":"muted"}}]}},
    """.trimIndent()

    private fun listDeclaration(ui: String) = """
        {"id":"h","version":"1.0.0","title":"Подсказки","home":"h.list",$thingModel
         "methods":[
          {"name":"h.list","kind":"query","title":"Вещи","model":"thing","ui":{"view":"list","item":{"title":"name","subtitle":"note","value":"sum"},"open":"h.get"$ui}},
          {"name":"h.get","kind":"query","title":"Вещь","model":"thing","input":{"type":"object","properties":{"id":{"type":"string"}}},"ui":{"view":"detail","item":{"title":"name"}}}
         ]}
    """.trimIndent()

    @Test
    fun aWideTableHasTheServersColumnsAfterTheTitleAndSkipsWhatTheModelDoesNotDeclare() {
        val server = HintServer(listDeclaration(""","columns":[{"field":"state","width":"narrow"},{"field":"ghost"},{"field":"note","width":"wide","align":"end"},{"field":"sum","width":"huge"}]"""), ::listAnswer)
        window(server, WindowClass.Expanded) {
            onNodeWithText("Состояние").assertExists()
            onNodeWithText("Заметка").assertExists()
            onNodeWithText("Сумма").assertExists()
            onNodeWithTag("h:row-1:col-state", useUnmergedTree = true).assertTextEquals("Новая")
            onNodeWithTag("h:row-1:col-note", useUnmergedTree = true).assertTextEquals("Заметка один")
            onNodeWithTag("h:row-2:col-sum", useUnmergedTree = true).assertTextEquals("20")
            // The undeclared one is no column at all.
            onNodeWithTag("h:row-1:col-ghost", useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun columnsOfFieldsTheModelLacksAreAsNoColumnsAtAll() {
        val server = HintServer(listDeclaration(""","columns":[{"field":"ghost"},{"field":"name"}]"""), ::listAnswer)
        window(server, WindowClass.Expanded) {
            onNodeWithTag("h:row-1:col-ghost", useUnmergedTree = true).assertDoesNotExist()
            // The columns of `item`: its value, under its field's title.
            onNodeWithTag("h:row-1:value", useUnmergedTree = true).assertTextEquals("10")
        }
    }

    // ───────── P6: board ─────────

    private val board = ""","board":{"field":"state","order":["new","done"]}"""

    @Test
    fun aBoardOnAWideWindowHasAColumnPerValueAndACardPerRecordOnIt() {
        val server = HintServer(listDeclaration(board), ::listAnswer)
        window(server, WindowClass.Medium) {
            onNodeWithTag("h:board").assertExists()
            onNodeWithTag("h:board-new:count").assertTextEquals("1")
            onNodeWithTag("h:board-done:count").assertTextEquals("1")
            onNode(hasText("Готова") and hasAnyAncestor(hasTestTag("h:board-done"))).assertExists()
            onNode(hasTestTag("h:row-1") and hasAnyAncestor(hasTestTag("h:board-new"))).assertExists()
            // «gone» is not in the order: not on the board.
            onNodeWithTag("h:row-3").assertDoesNotExist()
            onNodeWithTag("h:row-2").performClick()
            waitForIdle()
            onNodeWithTag("h:detail").assertExists()
        }
    }

    @Test
    fun aBoardOnAPhoneIsTheListWithTabsOfItsValues() {
        val server = HintServer(listDeclaration(board), ::listAnswer)
        window(server, WindowClass.Compact) {
            onNodeWithTag("h:board").assertDoesNotExist()
            onNodeWithTag("h:board-tabs").assertExists()
            listOf("h:row-1", "h:row-2", "h:row-3").forEach { onNodeWithTag(it).assertExists() }
            onNodeWithTag("h:board-tab-done").performClick()
            waitForIdle()
            onNodeWithTag("h:row-2").assertExists()
            onNodeWithTag("h:row-1").assertDoesNotExist()
            onNodeWithTag("h:board-tab-all").performClick()
            waitForIdle()
            onNodeWithTag("h:row-1").assertExists()
        }
    }

    @Test
    fun aBoardOfAFieldThatIsNoEnumIsAPlainTable() {
        val server = HintServer(listDeclaration(""","board":{"field":"note","order":["x"]}"""), ::listAnswer)
        window(server, WindowClass.Expanded) {
            onNodeWithTag("h:board").assertDoesNotExist()
            onNodeWithTag("h:row-3").assertExists()
        }
        // And a board of the wrong shape is no board, not a broken declaration.
        val broken = HintServer(listDeclaration(""","board":"state""""), ::listAnswer)
        window(broken, WindowClass.Expanded) {
            onNodeWithTag("h:board").assertDoesNotExist()
            onNodeWithTag("h:row-1").assertExists()
        }
    }

    // ───────── P3: primary ─────────

    @Test
    fun theMainActionFillsItsFirstValueAndNoOtherActionFillsOne() {
        val tones = mapOf("reject" to Tones.DANGER)
        // No main action: the usual next step, as before.
        assertEquals("cook", mainValueOf(listOf("reject", "cook", "done"), tones, null))
        assertEquals("reject", mainValueOf(listOf("reject", "cook"), tones, true))
        assertNull(mainValueOf(listOf("cook", "done"), tones, false))
    }

    @Test
    fun everyButtonOfTheRecordStaysInItsHeaderWithAMainAction() {
        val record = json("""{"id":"1","name":"Первая","_actions":[{"method":"h.move"},{"method":"h.flag"}]}""").jsonObject
        val declaration = """
            {"id":"h","version":"1.0.0","title":"Подсказки","home":"h.get","models":{"thing":{"fields":[{"name":"name","title":"Название"}]}},
             "methods":[
              {"name":"h.get","kind":"query","title":"Вещь","model":"thing","ui":{"view":"detail","item":{"title":"name"}}},
              {"name":"h.move","kind":"mutation","title":"Шаг","input":{"type":"object","properties":{"id":{"type":"string"},"to":{"type":"string","enum":["a","b"]}}},
               "ui":{"view":"action","on":"thing","placement":["detail"],"control":{"type":"buttons","param":"to"}}},
              {"name":"h.flag","kind":"mutation","title":"Флаг","input":{"type":"object","properties":{"id":{"type":"string"},"f":{"type":"string","enum":["x","y"]}}},
               "ui":{"view":"action","on":"thing","placement":["detail"],"control":{"type":"buttons","param":"f"},"primary":true}}
             ]}
        """.trimIndent()
        val server = HintServer(declaration) { _, _ -> JsonObject(mapOf("item" to record)) }
        window(server, WindowClass.Expanded) {
            listOf("h:h.move-a", "h:h.move-b", "h:h.flag-x", "h:h.flag-y").forEach { onNodeWithTag(it).assertExists() }
            onNodeWithTag("h:h.flag-x").performClick()
            waitForIdle()
            assertEquals("x", server.calls.last { it.first == "h.flag" }.second["f"]?.jsonPrimitive?.content)
        }
    }

    // ───────── P5: sections ─────────

    private fun sectionsDeclaration(secondSection: String?) = """
        {"id":"h","version":"1.0.0","title":"Подсказки","home":"h.list",$thingModel
         "methods":[
          {"name":"h.list","kind":"query","title":"Вещи","model":"thing","ui":{"view":"list","item":{"title":"name"},"open":"h.get","section":"Вещи"}},
          {"name":"h.get","kind":"query","title":"Вещь","model":"thing","input":{"type":"object","properties":{"id":{"type":"string"}}},"ui":{"view":"detail","item":{"title":"name"}}},
          {"name":"h.other","kind":"query","title":"Другие","model":"thing","ui":{"view":"list","item":{"title":"name"}${secondSection?.let { ""","section":"$it"""" } ?: ""}}},
          {"name":"h.act","kind":"mutation","title":"Нет","ui":{"view":"action","on":"thing","section":"Не раздел"}}
         ]}
    """.trimIndent()

    @Test
    fun sectionsAreABarOnAPhoneAndASectionOpensAtTheRootOfTheStack() {
        val server = HintServer(sectionsDeclaration("Другие"), ::listAnswer)
        window(server, WindowClass.Compact) { instance ->
            onNodeWithTag("sections:bar").assertExists()
            onNodeWithTag("h:row-1").performClick()
            waitForIdle()
            assertEquals(2, instance.frames.value.size)
            onNodeWithTag("sections:h.other").performClick()
            waitForIdle()
            assertEquals(listOf("h.other"), instance.frames.value.map { it.method })
            assertEquals("h.other", server.calls.last().first)
            // A mutation is never a section, whatever it says.
            onNodeWithTag("sections:h.act").assertDoesNotExist()
        }
    }

    @Test
    fun sectionsAreTabsUnderTheHeaderOfAWideWindowAndOneSectionIsNone() {
        window(HintServer(sectionsDeclaration("Другие"), ::listAnswer), WindowClass.Expanded) {
            onNodeWithTag("sections:tabs").assertExists()
            onNodeWithText("Другие").performClick()
            waitForIdle()
            onNodeWithTag("sections:tabs").assertExists()
        }
        window(HintServer(sectionsDeclaration(null), ::listAnswer), WindowClass.Compact) {
            onNodeWithTag("sections:bar").assertDoesNotExist()
            onNodeWithTag("h:list").assertExists()
        }
    }

    // ───────── P7: summary ─────────

    private val summaryRecord = json(
        """{"id":"s","title":"Сводка","total":119700,"growth":-4,"count":947,"note":"Заметка",
           "byDay":[{"x":"1 ноя","y":30},{"x":"2 ноя","y":42},{"x":"3 ноя","y":37}],
           "top":[{"title":"ЛЕТО10","value":312},{"title":"Без числа"}],
           "shares":[{"title":"Обед","value":72900},{"title":"Лето","value":38940}],
           "broken":"not an array"}""",
    ).jsonObject

    private val summaryDeclaration = """
        {"id":"h","version":"1.0.0","title":"Подсказки","home":"h.home",
         "models":{"sum":{"fields":[{"name":"title","title":"Название"},{"name":"total","title":"Скидок выдано","format":"money"},
           {"name":"count","title":"Применений","format":"number"},{"name":"note","title":"Заметка"}]}},
         "methods":[
          {"name":"h.home","kind":"query","title":"Сводка","model":"sum","ui":{"view":"detail","item":{"title":"title"},"summary":[
            {"type":"metric","field":"total","delta":"growth"},
            {"type":"metric","field":"count","title":"Применения"},
            {"type":"metric","field":"missing"},
            {"type":"series","field":"byDay","title":"По дням"},
            {"type":"list","field":"top","title":"Коды"},
            {"type":"share","field":"shares","title":"Доли"},
            {"type":"share","field":"broken"},
            {"type":"pie","field":"shares"},
            {"field":"total"}
          ]}}
         ]}
    """.trimIndent()

    @Test
    fun aSummaryDrawsItsBlocksOverTheCardAndSkipsWhatItCannotDraw() {
        val server = HintServer(summaryDeclaration) { _, _ -> JsonObject(mapOf("item" to summaryRecord)) }
        for (cls in listOf(WindowClass.Compact, WindowClass.Expanded)) {
            window(server, cls) {
                onNode(hasText("119", substring = true) and hasAnyAncestor(hasTestTag("h:summary-total"))).assertExists()
                // The change's sign is its tone; its words are the percentage.
                onNode(hasText("−4 %") and hasAnyAncestor(hasTestTag("h:summary-total"))).assertExists()
                onNode(hasText("Применения") and hasAnyAncestor(hasTestTag("h:summary-count"))).assertExists()
                onNodeWithTag("h:summary-byDay").assertExists()
                onNode(hasText("312") and hasAnyAncestor(hasTestTag("h:summary-top"))).assertExists()
                onNode(hasText("Без числа") and hasAnyAncestor(hasTestTag("h:summary-top"))).assertExists()
                onNode(hasText("Обед") and hasAnyAncestor(hasTestTag("h:summary-shares"))).assertExists()
                // Missing, of the wrong shape, of an unknown type: skipped, and nothing else is lost.
                onNodeWithTag("h:summary-missing").assertDoesNotExist()
                onNodeWithTag("h:summary-broken").assertDoesNotExist()
                // The card keeps the fields the summary does not draw, and does not repeat those it does.
                onNodeWithTag("h:field-note").assertExists()
                onNodeWithTag("h:field-total").assertDoesNotExist()
                onNodeWithTag("h:field-count").assertDoesNotExist()
            }
        }
        val parsed = server.app.value!!.method("h.home")!!.ui!!.summary.orEmpty()
        // The block without a type was dropped while reading; the unknown one is read and skipped when drawn.
        assertEquals(listOf("metric", "metric", "metric", "series", "list", "share", "share", "pie"), parsed.map { it.type })
    }

    /** The screen of [server]'s app in a window of [cls]. */
    private fun window(server: HintServer, cls: WindowClass, block: ComposeUiTest.(ServerAppInstance) -> Unit) = runComposeUiTest {
        val app = ServerApp(server.app.value!!, "ru")
        val runtime = FakeAppRuntime(app.id, TestScope(UnconfinedTestDispatcher()), declaredServices = app.manifest.requires)
        runtime.provide(RestoServer.Key, server)
        val instance = app.launch(runtime, app.configSpec.default) as ServerAppInstance
        runTest { instance.onLaunch() }
        setContent {
            CompositionLocalProvider(LocalAppRuntime provides runtime, LocalWindowClass provides cls) {
                androidx.compose.foundation.layout.Box(Modifier.testTag("h:root")) { instance.Content() }
            }
        }
        waitForIdle()
        block(instance)
        assertTrue(true)
    }
}
