package cx.m42.restoapp.protocol

import cx.m42.superizer.runtime.NetworkRequest
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The client against the real app-mobile-api hook: `tools/fake-server` runs the hook's Express
 * application of ../restoapp-dev on the fake deps of its own tests — the real PoW CAPTCHA, ajv,
 * adminizer's AccessRightsHelper with core's `stock-manager` check. Only the database is memory.
 *
 * Slices 1 and 2 of the plan, from the phone's side: a Stock Manager with points A and B signs in
 * by solving the CAPTCHA, changes a balance at A and does not see it at B; the Operator has no
 * «Блюда» but leads the orders of their own point, one state at a time, and is told by `since`
 * alone what moved; a point taken away or a password changed is felt on the very next request.
 *
 * Skipped, with a line in the output, when there is no ../restoapp-dev with its node_modules.
 */
class HttpMobileApiLiveTest {

    private val dev = File(System.getProperty("restoapp.dev") ?: "../restoapp-dev")
    private val tools = File(System.getProperty("restoapp.tools") ?: "tools")
    private var process: Process? = null
    private val network = JdkNetwork()
    private val device = DeviceInfo("Test desktop", "desktop")

    /** A fresh server per test: users, devices and login attempts start clean. */
    private fun server(): HttpMobileApi? {
        if (!File(dev, "node_modules/adminizer").isDirectory || !File(dev, "api/hooks/app-mobile-api").isDirectory) {
            println("SKIPPED: no ../restoapp-dev with the app-mobile-api hook and node_modules at $dev")
            return null
        }
        val started = ProcessBuilder("npx", "tsx", File(tools, "fake-server/serve.ts").absolutePath, "0")
            .directory(dev)
            .redirectErrorStream(true)
            .start()
        process = started
        val reader = started.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: break
            if (line.startsWith("listening ")) {
                Thread { reader.forEachLine { } }.apply { isDaemon = true }.start()
                return HttpMobileApi(line.removePrefix("listening ").trim(), network)
            }
        }
        error("The fake server did not start")
    }

    @AfterTest
    fun stop() {
        process?.let {
            it.destroy()
            it.waitFor(5, TimeUnit.SECONDS)
            if (it.isAlive) it.destroyForcibly()
        }
    }

    private suspend fun HttpMobileApi.signIn(login: String, password: String = "secret-password"): String {
        val task = assertNotNull(challenge(), "the hook asks for a CAPTCHA")
        val solution = CryptoPuzzleSolver.solve(task) {}
        return login(login, password, solution, device).token
    }

    private suspend fun HttpMobileApi.control(path: String, body: String) {
        network.request(NetworkRequest("POST", "$base/__fake/$path", body = body, contentType = "application/json"))
    }

    @Test
    fun aStockManagerChangesABalanceAtOnePointAndTheOtherPointDoesNotSeeIt(): Unit = runBlocking {
        val api = server() ?: return@runBlocking
        assertEquals(ServerAbout("Test server", "1.2.3"), api.about())

        val token = api.signIn("stock")
        val described = api.describe(token, "ru")
        val declaration = described.value
        Contract.assertValid("declaration", Contract.wire.encodeToJsonElement(Declaration.serializer(), declaration))
        assertEquals(PROTOCOL_VERSION, described.protocol)
        assertEquals(declaration.revision, described.revision)

        val dishes = assertNotNull(declaration.app("dishes"))
        assertEquals("Блюда", dishes.title)
        assertTrue(dishes.byPlace)
        assertEquals(listOf("p1", "p2"), dishes.places)
        assertEquals(listOf("Ленина, 5", "Мира, 3"), declaration.places.map { it.title })
        assertEquals("dishes.list", dishes.home)
        assertEquals(Views.LIST, dishes.method("dishes.list")!!.ui!!.view)
        assertEquals(Controls.STEPPER, dishes.method("dishes.setBalance")!!.ui!!.control!!.type)

        val all = api.call(token, "dishes.list", jsonObjectOf("filter" to JsonPrimitive("all")), "p1", "ru")
        assertEquals(declaration.revision, all.revision)
        Contract.assertValid("listResult", all.value)
        val page = ListPage.of(all.value)
        assertEquals(3, page.items.size)
        assertEquals(mapOf("limited" to 0, "stop" to 0, "all" to 3), page.counts)
        assertEquals(listOf("dishes.setBalance", "dishes.setEnabled"), page.items.first().actions().map { it.method })

        val set = api.call(token, "dishes.setBalance", jsonObjectOf("id" to JsonPrimitive("d1"), "balance" to JsonPrimitive(5)), "p1", "ru")
        assertEquals(JsonPrimitive(5), recordOf(set.value)!!["localBalance"])
        assertEquals(JsonPrimitive(5), recordOf(set.value)!!["balance"])

        val atB = api.call(token, "dishes.get", jsonObjectOf("id" to JsonPrimitive("d1")), "p2", "ru")
        assertEquals(JsonNull, recordOf(atB.value)!!["localBalance"])
        assertEquals(JsonPrimitive(-1), recordOf(atB.value)!!["balance"])
        val limitedAtA = ListPage.of(api.call(token, "dishes.list", JsonObject(emptyMap()), "p1", "ru").value)
        assertEquals(listOf("d1"), limitedAtA.items.map { it.id() })

        val stopped = api.call(token, "dishes.setEnabled", jsonObjectOf("id" to JsonPrimitive("d2"), "enable" to JsonPrimitive(false)), "p2", "ru")
        assertEquals(JsonPrimitive(0), recordOf(stopped.value)!!["balance"])
    }

    @Test
    fun theServerRefusesByCodeWhatTheDeclarationDoesNotAllow(): Unit = runBlocking {
        val api = server() ?: return@runBlocking

        val wrong = assertFailsWith<MobileApiException> { api.signIn("stock", password = "nope") }
        assertEquals(MobileApiException.INVALID_CREDENTIALS, wrong.code)
        val unknown = assertFailsWith<MobileApiException> { api.signIn("nobody") }
        assertEquals(MobileApiException.INVALID_CREDENTIALS, unknown.code)

        val task = api.challenge()!!
        val solution = CryptoPuzzleSolver.solve(task) {}
        api.login("operator", "secret-password", solution, device)
        val replay = assertFailsWith<MobileApiException> { api.login("operator", "secret-password", solution, device) }
        assertEquals(MobileApiException.CAPTCHA_INVALID, replay.code)

        val operator = api.signIn("operator")
        assertNull(api.describe(operator, "ru").value.app("dishes"))
        val forbidden = assertFailsWith<MobileApiException> { api.call(operator, "dishes.list", JsonObject(emptyMap()), "p1", "ru") }
        assertEquals(MobileApiException.FORBIDDEN, forbidden.code)
        assertEquals(403, forbidden.status)

        val stock = api.signIn("stock")
        val pickup = assertFailsWith<MobileApiException> { api.call(stock, "dishes.list", JsonObject(emptyMap()), "p3", "ru") }
        assertEquals(MobileApiException.PLACE_FORBIDDEN, pickup.code)
        val noPoint = assertFailsWith<MobileApiException> { api.call(stock, "dishes.list", JsonObject(emptyMap()), null, "ru") }
        assertEquals(MobileApiException.INVALID, noPoint.code)
        val badParams = assertFailsWith<MobileApiException> {
            api.call(stock, "dishes.setBalance", jsonObjectOf("id" to JsonPrimitive("d1"), "balance" to JsonPrimitive(-5)), "p1", "ru")
        }
        assertEquals(MobileApiException.INVALID, badParams.code)

        val admin = api.signIn("admin")
        assertEquals(listOf("p1", "p2"), api.describe(admin, "ru").value.app("dishes")!!.places)
    }

    @Test
    fun theOperatorLeadsTheOrdersOfTheirPointAndNothingElse(): Unit = runBlocking {
        val api = server() ?: return@runBlocking
        val operator = api.signIn("operator")
        val declaration = api.describe(operator, "ru").value

        val orders = assertNotNull(declaration.app("orders"), "the operator is given «Заказы»")
        assertNull(declaration.app("dishes"), "and no «Блюда»")
        assertEquals("Заказы", orders.title)
        assertTrue(orders.byPlace)
        assertEquals(listOf("p1", "p3"), orders.places, "the points of their group")
        assertEquals("orders.list", orders.home)
        assertEquals(Views.LIST, orders.method("orders.list")!!.ui!!.view)
        assertEquals(Controls.BUTTONS, orders.method("orders.setState")!!.ui!!.control!!.type)
        assertNotNull(orders.method("orders.setState")!!.ui!!.confirm, "REJECT is asked about")

        val answer = api.call(operator, "orders.list", JsonObject(emptyMap()), "p1", "ru")
        Contract.assertValid("listResult", answer.value)
        val active = ListPage.of(answer.value)
        assertEquals(listOf("AAAA1111"), active.items.map { it["number"]?.jsonPrimitive?.content }, "the active orders of p1")
        assertEquals(mapOf("active" to 1, "done" to 1), active.counts, "the counts of both tabs of the point")
        val row = active.items.first()
        assertEquals(listOf("orders.setState"), row.actions().map { it.method })
        assertEquals(
            listOf("COOKING", "ON_THE_WAY", "DONE", "REJECT"),
            row.actions().first().input!!.property("to")!!.enumValues(),
            "the states the flow allows an operator from ORDER",
        )

        // Nothing has changed since this answer: the polling probe costs an empty page.
        val seen = row["updatedAt"]!!.jsonPrimitive.content.toLong()
        val quiet = ListPage.of(api.call(operator, "orders.list", jsonObjectOf("since" to JsonPrimitive(seen + 1)), "p1", "ru").value)
        assertEquals(emptyList(), quiet.items.map { it.id() })

        // The move, and the probe telling the phone about it.
        val cooking = api.call(operator, "orders.setState", jsonObjectOf("id" to JsonPrimitive(row.id()!!), "to" to JsonPrimitive("COOKING")), "p1", "ru")
        assertEquals(JsonPrimitive("COOKING"), recordOf(cooking.value)!!["state"])
        val moved = ListPage.of(api.call(operator, "orders.list", jsonObjectOf("since" to JsonPrimitive(seen + 1)), "p1", "ru").value)
        assertEquals(listOf(row.id()), moved.items.map { it.id() })
        assertEquals(
            listOf("ON_THE_WAY", "DONE", "REJECT"),
            moved.items.first().actions().first().input!!.property("to")!!.enumValues(),
            "and offers only where it can go from there",
        )

        // The orders of another point: not in the list, not by id, not movable.
        val elsewhere = assertFailsWith<MobileApiException> { api.call(operator, "orders.list", JsonObject(emptyMap()), "p2", "ru") }
        assertEquals(MobileApiException.PLACE_FORBIDDEN, elsewhere.code)
        val byId = assertFailsWith<MobileApiException> { api.call(operator, "orders.get", jsonObjectOf("id" to JsonPrimitive("o2")), "p1", "ru") }
        assertEquals(MobileApiException.NOT_FOUND, byId.code)
        val backwards = assertFailsWith<MobileApiException> {
            api.call(operator, "orders.setState", jsonObjectOf("id" to JsonPrimitive(row.id()!!), "to" to JsonPrimitive("ORDER")), "p1", "ru")
        }
        assertEquals(MobileApiException.INVALID, backwards.code, "a state the narrowed schema does not carry")
    }

    @Test
    fun anOrderWithNoKitchenWaitsAtItsOwnPointAndIsGivenOne(): Unit = runBlocking {
        val api = server() ?: return@runBlocking
        val dispatcher = api.signIn("dispatcher")
        val declaration = api.describe(dispatcher, "ru").value

        val orders = assertNotNull(declaration.app("orders"))
        assertEquals(listOf("p1", "p2", "no-kitchen"), orders.places, "«Без кухни» is a point of the app")
        assertEquals("Без кухни", declaration.places.first { it.id == "no-kitchen" }.title)
        assertEquals(
            listOf("p1", "p2"),
            orders.method("orders.assignKitchen")!!.input.property("place")!!.enumValues(),
            "the kitchens of this person, in the declaration itself",
        )

        val waiting = ListPage.of(api.call(dispatcher, "orders.list", JsonObject(emptyMap()), "no-kitchen", "ru").value)
        assertEquals(listOf("DDDD4444"), waiting.items.map { it["number"]?.jsonPrimitive?.content })
        val order = waiting.items.first()
        assertEquals(listOf("orders.setState", "orders.assignKitchen"), order.actions().map { it.method })

        val assigned = api.call(
            dispatcher, "orders.assignKitchen",
            jsonObjectOf("id" to JsonPrimitive(order.id()!!), "place" to JsonPrimitive("p2")), "no-kitchen", "ru",
        )
        assertEquals(JsonPrimitive("p2"), recordOf(assigned.value)!!["placeId"])
        assertEquals(emptyList(), ListPage.of(api.call(dispatcher, "orders.list", JsonObject(emptyMap()), "no-kitchen", "ru").value).items.map { it.id() })
        assertTrue(
            ListPage.of(api.call(dispatcher, "orders.list", JsonObject(emptyMap()), "p2", "ru").value).items.any { it.id() == order.id() },
            "it is among the orders of that kitchen now",
        )

        // «Без кухни» is granted like any point: the operator does not hold it.
        val operator = api.signIn("operator")
        val refused = assertFailsWith<MobileApiException> { api.call(operator, "orders.list", JsonObject(emptyMap()), "no-kitchen", "ru") }
        assertEquals(MobileApiException.PLACE_FORBIDDEN, refused.code)
    }

    @Test
    fun aPointTakenAwayAndAPasswordChangedAreFeltOnTheNextRequest(): Unit = runBlocking {
        val api = server() ?: return@runBlocking
        val token = api.signIn("stock")
        val before = api.describe(token, "ru").value

        api.control("stock-points", """{"places":["p1"]}""")
        val next = api.call(token, "dishes.list", JsonObject(emptyMap()), "p1", "ru")
        assertNotEquals(before.revision, next.revision, "a new revision tells the client to re-read the declaration")
        assertEquals(listOf("p1"), api.describe(token, "ru").value.app("dishes")!!.places)
        val gone = assertFailsWith<MobileApiException> { api.call(token, "dishes.list", JsonObject(emptyMap()), "p2", "ru") }
        assertEquals(MobileApiException.PLACE_FORBIDDEN, gone.code)

        api.control("password", """{"login":"stock"}""")
        val signedOut = assertFailsWith<MobileApiException> { api.describe(token, "ru") }
        assertEquals(MobileApiException.UNAUTHORIZED, signedOut.code)
        assertEquals(401, signedOut.status)
    }

    @Test
    fun loggingOutRevokesTheDevice(): Unit = runBlocking {
        val api = server() ?: return@runBlocking
        val token = api.signIn("stock")
        api.logout(token)
        val revoked = assertFailsWith<MobileApiException> { api.call(token, "dishes.list", JsonObject(emptyMap()), "p1", "ru") }
        assertEquals(MobileApiException.UNAUTHORIZED, revoked.code)
    }

    @Test
    fun anAddressWithNoRestoAppIsNotTakenForOne(): Unit = runBlocking {
        val api = server() ?: return@runBlocking
        val elsewhere = HttpMobileApi(api.base + "/not-here", network)
        val notIt = assertFailsWith<MobileApiException> { elsewhere.about() }
        assertTrue(notIt.code == MobileApiException.PROTOCOL || notIt.code == MobileApiException.NOT_FOUND, notIt.code)
        val nobody = assertFailsWith<MobileApiException> { HttpMobileApi("http://127.0.0.1:9", network).about() }
        assertEquals(MobileApiException.NETWORK, nobody.code)
    }
}
