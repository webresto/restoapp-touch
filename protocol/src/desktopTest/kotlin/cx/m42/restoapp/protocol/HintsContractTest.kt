package cx.m42.restoapp.protocol

import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.demo.DemoRole
import cx.m42.restoapp.demo.DemoStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The optional hints of 2026-11-18 (04-protocol.md, P1–P7): the server's schema takes them in their closed
 * shapes and refuses the rest; the client reads them so that a wrong one is no value and never a lost
 * declaration; the demo server sends every one of them.
 */
class HintsContractTest {

    private fun json(text: String) = ProtocolJson.parseToJsonElement(text)

    @Test
    fun theSchemaTakesEveryHintInItsShape() {
        Contract.assertValid(
            "ui",
            json(
                """{"view":"list","columns":[{"field":"state","width":"narrow"},{"field":"total","align":"end","width":"wide"}],
                   "board":{"field":"state","order":["ORDER","COOKING"]},"section":"Заказы"}""",
            ),
        )
        Contract.assertValid(
            "ui",
            json(
                """{"view":"detail","section":"Сводка","summary":[
                    {"type":"metric","field":"total","title":"Скидок","format":"money","delta":"totalDelta"},
                    {"type":"series","field":"byDay","format":"number"},{"type":"list","field":"top"},{"type":"share","field":"shares","title":"Доли"}]}""",
            ),
        )
        Contract.assertValid("ui", json("""{"view":"action","on":"order","placement":["detail"],"control":{"type":"buttons","param":"to"},"primary":true}"""))
        Contract.assertValid("field", json("""{"name":"state","title":"Статус","format":"enum","x-titles":{"DONE":"Выполнен"},"x-tones":{"DONE":"muted","ORDER":"info"}}"""))
    }

    @Test
    fun theSchemaRefusesHintsOutsideTheirSets() {
        Contract.assertInvalid("column", """{"field":"x","align":"middle"}""")
        Contract.assertInvalid("column", """{"field":"x","width":"huge"}""")
        Contract.assertInvalid("ui", """{"view":"list","columns":[]}""")
        Contract.assertInvalid("ui", """{"view":"list","board":{"field":"state"}}""")
        Contract.assertInvalid("ui", """{"view":"action","primary":"yes"}""")
        Contract.assertInvalid("ui", """{"view":"detail","summary":[{"type":"pie","field":"x"}]}""")
        Contract.assertInvalid("ui", """{"view":"detail","summary":[{"type":"metric"}]}""")
        Contract.assertInvalid("field", """{"name":"s","title":"S","format":"enum","x-tones":{"A":"purple"}}""")
        // `muted` is a tone of `x-tones` only: a special and a button keep the four an older client knows.
        Contract.assertInvalid("special", """{"value":"x","title":"X","tone":"muted"}""")
        Contract.assertInvalid("control", """{"type":"buttons","tones":{"x":"muted"}}""")
    }

    @Test
    fun aHintOfTheWrongShapeIsNoHintAndTheDeclarationStays() {
        val app = ProtocolJson.decodeFromString(
            DeclaredApp.serializer(),
            """{"id":"x","version":"1.0.0","title":"X","home":"x.a","scope":"server","badge":42,
               "models":{"m":{"fields":[{"name":"s","title":"S","format":"enum","x-tones":{"A":"info","B":7,"C":"purple"}}]}},
               "methods":[
                 {"name":"x.a","kind":"query","title":"A","input":{"type":"object"},
                  "ui":{"view":"list","columns":[{"field":"s"},{"align":"end"},"s",{"field":"t","align":"diagonal"}],
                        "board":"s","primary":"yes","section":{"ru":"A"},"summary":{"type":"metric"}}},
                 {"name":"x.b","kind":"query","title":"B","input":{"type":"object"},
                  "ui":{"view":"detail","summary":[{"type":"metric","field":"n"},{"type":"pie","field":"n"},{"field":"n"},7]}}]}""",
        )
        assertNull(app.badge)
        val field = app.fields("m").single()
        assertEquals(mapOf("A" to "info", "C" to "purple"), field.tones)
        assertEquals("info", field.toneOf("A"))
        // A tone outside the set is as no tone.
        assertNull(field.toneOf("C"))
        val list = app.method("x.a")!!.ui!!
        // The broken columns go, the good ones stay; an unknown align is read and drawn as none.
        assertEquals(listOf("s", "t"), list.columns.orEmpty().map { it.field })
        assertNull(list.board)
        assertNull(list.primary)
        assertNull(list.section)
        assertNull(list.summary)
        // An unknown block type is read — the renderer skips it; a block without a field is dropped here.
        assertEquals(listOf("metric", "pie"), app.method("x.b")!!.ui!!.summary.orEmpty().map { it.type })
    }

    @Test
    fun aDeclarationWithoutAnyHintReadsAsBefore() {
        val app = ProtocolJson.decodeFromString(
            DeclaredApp.serializer(),
            """{"id":"x","version":"1.0.0","title":"X","home":"x.a","scope":"server",
               "methods":[{"name":"x.a","kind":"query","title":"A","input":{"type":"object"},"ui":{"view":"list"}}]}""",
        )
        val ui = app.methods.single().ui!!
        assertNull(app.badge)
        assertNull(ui.columns)
        assertNull(ui.board)
        assertNull(ui.primary)
        assertNull(ui.section)
        assertNull(ui.summary)
        // And written back, nothing of them appears: an older server's declaration stays what it was.
        val text = Contract.wire.encodeToString(DeclaredApp.serializer(), app)
        listOf("badge", "columns", "board", "primary", "section", "summary", "x-tones").forEach { assertTrue(it !in text, "$it in $text") }
    }

    private class MemoryStore : DemoStore {
        var text: String? = null
        override suspend fun read(): String? = text
        override suspend fun write(text: String) {
            this.text = text
        }
    }

    @Test
    fun theDemoServerSendsEveryHint(): Unit = runBlocking {
        val demo = DemoBackend(MemoryStore(), DemoBackend.TWO_POINTS, clock = { 1_700_000_000_000 }, serverName = "Демо")
        demo.setRole(DemoRole.Admin)
        val declaration = demo.describe(DemoBackend.TOKEN, "ru").value
        val orders = declaration.app("orders")!!
        assertEquals("orders.badge", orders.badge)
        assertEquals("muted", orders.fields("order").first { it.name == "state" }.toneOf("DONE"))
        val list = orders.method("orders.list")!!.ui!!
        assertTrue(list.columns.orEmpty().isNotEmpty())
        assertEquals("state", list.board?.field)
        assertEquals(true, orders.method("orders.setState")!!.ui!!.primary)
        val count = demo.call(DemoBackend.TOKEN, "orders.badge", JsonObject(emptyMap()), "p1", "ru").value
        assertTrue((count as JsonPrimitive).content.toInt() > 0)

        val marketing = declaration.app("marketing")!!
        assertEquals(
            listOf("marketing.home", "marketing.promotions", "marketing.promocodes", "marketing.applications"),
            marketing.methods.filter { it.ui?.section != null }.map { it.name },
        )
        val summary = marketing.method("marketing.home")!!.ui!!.summary.orEmpty()
        assertEquals(setOf("metric", "series", "share", "list"), summary.map { it.type }.toSet())
        val home = demo.call(DemoBackend.TOKEN, "marketing.home", JsonObject(emptyMap()), null, "ru").value
        Contract.assertValid("itemResult", home)
        val item = (home as JsonObject)["item"] as JsonObject
        // Every block's field is in the answer, and none of the arrays is declared in the model.
        summary.forEach { assertTrue(it.field in item, "${it.field} is not in the summary") }
        val declared = marketing.fields("summary").map { it.name }
        assertTrue(summary.filter { it.type != "metric" }.none { it.field in declared })

        val system = declaration.app("system")!!
        assertEquals(listOf("system.home", "system.modules", "system.catalog"), system.methods.filter { it.ui?.section != null }.map { it.name })
    }
}
