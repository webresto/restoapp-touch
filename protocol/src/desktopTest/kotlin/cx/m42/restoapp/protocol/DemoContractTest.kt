package cx.m42.restoapp.protocol

import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.demo.DemoRole
import cx.m42.restoapp.demo.DemoStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The demo server speaks protocol 1 exactly: its declarations for every role and language, and the
 * answers of its methods, pass the server's own schema. A demo that drifted from the contract would
 * make the renderer look right on data no real server sends.
 */
class DemoContractTest {

    private class MemoryStore : DemoStore {
        var text: String? = null
        override suspend fun read(): String? = text
        override suspend fun write(text: String) {
            this.text = text
        }
    }

    private fun demo(places: List<Place> = DemoBackend.TWO_POINTS) =
        DemoBackend(MemoryStore(), places, clock = { 1_700_000_000_000 }, serverName = "Демо-ресторан")

    @Test
    fun everyRoleAndLanguageDeclaresByTheContract(): Unit = runBlocking {
        val demo = demo()
        for (role in DemoRole.entries) {
            demo.setRole(role)
            for (lang in listOf("ru", "en")) {
                val declaration = demo.describe(DemoBackend.TOKEN, lang).value
                Contract.assertValid("declaration", Contract.wire.encodeToJsonElement(Declaration.serializer(), declaration))
            }
        }
    }

    @Test
    fun theRolesSeeWhatTheirGroupsWouldGrant(): Unit = runBlocking {
        val demo = demo()
        fun ids(declaration: Declaration) = declaration.apps.associate { it.id to it.places }

        demo.setRole(DemoRole.Admin)
        assertEquals(mapOf("dishes" to listOf("p1", "p2"), "orders" to listOf("p1", "p2")), ids(demo.describe("demo", "ru").value))
        demo.setRole(DemoRole.Stock)
        assertEquals(mapOf("dishes" to listOf("p1", "p2")), ids(demo.describe("demo", "ru").value))
        demo.setRole(DemoRole.Operator)
        val operator = demo.describe("demo", "ru").value
        assertEquals(mapOf("orders" to listOf("p1")), ids(operator))
        assertEquals(listOf("p1"), operator.places.map { it.id })

        // What the declaration lacks, the server refuses — whatever the client thinks it may do.
        val forbidden = assertFailsWith<MobileApiException> { demo.call("demo", "dishes.list", JsonObject(emptyMap()), "p1", "ru") }
        assertEquals(MobileApiException.FORBIDDEN, forbidden.code)
        val point = assertFailsWith<MobileApiException> { demo.call("demo", "orders.list", JsonObject(emptyMap()), "p2", "ru") }
        assertEquals(MobileApiException.PLACE_FORBIDDEN, point.code)
    }

    @Test
    fun theAnswersOfTheMethodsAreTheEnvelopesOfTheContract(): Unit = runBlocking {
        val demo = demo()
        for (filter in listOf("limited", "stop", "all")) {
            val list = demo.call("demo", "dishes.list", jsonObjectOf("filter" to JsonPrimitive(filter)), "p1", "ru").value
            Contract.assertValid("listResult", list)
        }
        val first = ListPage.of(demo.call("demo", "dishes.list", jsonObjectOf("filter" to JsonPrimitive("all")), "p1", "ru").value).items.first()
        val id = first.id()!!
        Contract.assertValid("itemResult", demo.call("demo", "dishes.get", jsonObjectOf("id" to JsonPrimitive(id)), "p1", "ru").value)
        Contract.assertValid(
            "itemResult",
            demo.call("demo", "dishes.setBalance", jsonObjectOf("id" to JsonPrimitive(id), "balance" to JsonPrimitive(7)), "p1", "ru").value,
        )
        val orders = demo.call("demo", "orders.list", JsonObject(emptyMap()), "p1", "ru").value
        Contract.assertValid("listResult", orders)
        val order = ListPage.of(orders).items.first()
        assertTrue(order.actions().single().input!!.property("to")!!.enumValues().isNotEmpty())
    }

    @Test
    fun aBalanceSetAtOnePointStaysThere(): Unit = runBlocking {
        val demo = demo()
        val params = jsonObjectOf("id" to JsonPrimitive("d1"), "balance" to JsonPrimitive(42))
        val item = recordOf(demo.call("demo", "dishes.setBalance", params, "p1", "ru").value)!!
        assertEquals(JsonPrimitive(42), item["localBalance"])

        fun balanceAt(place: String) = runBlocking {
            recordOf(demo.call("demo", "dishes.get", jsonObjectOf("id" to JsonPrimitive("d1")), place, "ru").value)!!["localBalance"]
        }
        assertEquals(JsonPrimitive(42), balanceAt("p1"))
        assertTrue(balanceAt("p2") != JsonPrimitive(42))
    }

    @Test
    fun anOrderMovesOnlyAlongTheFlow(): Unit = runBlocking {
        val demo = demo()
        val orders = ListPage.of(demo.call("demo", "orders.list", jsonObjectOf("tab" to JsonPrimitive("done")), "p1", "ru").value).items
        val done = orders.first { it["state"] == JsonPrimitive("DONE") }
        assertTrue(done.actions().isEmpty())
        val conflict = assertFailsWith<MobileApiException> {
            demo.call("demo", "orders.setState", jsonObjectOf("id" to JsonPrimitive(done.id()!!), "to" to JsonPrimitive("COOKING")), "p1", "ru")
        }
        assertEquals(MobileApiException.CONFLICT, conflict.code)
    }

    @Test
    fun theContractRefusesWhatTheClientWouldNotDraw() {
        Contract.assertInvalid("ui", """{"view":"grid"}""")
        Contract.assertInvalid("ui", """{"view":"list","colour":"red"}""")
        Contract.assertInvalid("control", """{"type":"slider"}""")
        Contract.assertInvalid("field", """{"name":"x","title":"X","format":"markdown"}""")
    }

    @Test
    fun theClientReadsWhatANewerServerMightSend() {
        // Unknown keys, an unknown view, control and format: parsed, and drawn later as text.
        val text = """
            {"protocolVersion":1,"revision":"r","server":{"name":"S","extra":1},"user":{"id":"u1","login":"a"},
             "places":[],"apps":[{"id":"x","version":"2.0.0","title":"X","home":"x.a","scope":"server","future":true,
             "models":{"m":{"fields":[{"name":"f","title":"F","format":"markdown"}]}},
             "methods":[{"name":"x.a","kind":"query","title":"A","input":{"type":"object"},"ui":{"view":"grid","control":{"type":"slider"}}}],
             "script":{"url":"x.js"}}]}
        """.trimIndent()
        val declaration = ProtocolJson.decodeFromString(Declaration.serializer(), text)
        assertEquals("grid", declaration.apps.single().methods.single().ui!!.view)
        assertEquals("markdown", declaration.apps.single().fields("m").single().format)
        assertTrue(declaration.apps.single().script!!.jsonObject.isNotEmpty())
    }
}
