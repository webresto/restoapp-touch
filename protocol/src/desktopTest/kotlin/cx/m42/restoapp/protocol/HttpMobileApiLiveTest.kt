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

/**
 * The client against the real app-mobile-api hook: `tools/fake-server` runs the hook's Express
 * application of ../restoapp-dev on the fake deps of its own tests — the real PoW CAPTCHA, ajv,
 * adminizer's AccessRightsHelper with core's `stock-manager` check. Only the database is memory.
 *
 * Slice 1 of the plan, from the phone's side: a Stock Manager with points A and B signs in by
 * solving the CAPTCHA, changes a balance at A and does not see it at B; the Operator has no «Блюда»;
 * a point taken away or a password changed is felt on the very next request.
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
