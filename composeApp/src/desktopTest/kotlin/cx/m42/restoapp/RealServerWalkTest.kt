package cx.m42.restoapp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.restoapp.protocol.CryptoPuzzleSolver
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.restoapp.render.ServerStatus
import cx.m42.superizer.app.AppId
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The client through the window against `tools/real-server` — a lifted Sails with the admin panel of
 * adminizer 5.2, the app-mobile-api hook and core's own dishes, places and orders. Everything the
 * owner can do in «Блюда» on a phone, in one sitting: the points, the chips, the search, the card,
 * the stepper, «Безлимит», the stop, «В продаже», and signing out and back in.
 *
 * Runs only when the stand is up and its address is given; otherwise it says so and passes:
 *
 *   cd ../restoapp-dev && npx tsx ../restoapp-mobile-admin/tools/real-server/serve.ts 1341
 *   ./gradlew :composeApp:desktopTest --tests '*RealServerWalkTest' -Prestoapp.serverUrl=http://127.0.0.1:1341
 */
@OptIn(ExperimentalTestApi::class)
class RealServerWalkTest {

    private val base = System.getProperty("restoapp.serverUrl")?.takeIf { it.isNotBlank() }

    private fun control(path: String, body: String = "{}"): String {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$base/__fake/$path"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return response.body()
    }

    private fun get(path: String): String =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$base/__fake/$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()

    @Test
    fun theWholeDishesAppAgainstTheRealServer() {
        val address = base ?: run {
            println("SKIPPED: no -Prestoapp.serverUrl=<url> of a running tools/real-server")
            return
        }
        // A clean catalog and a clean limit counter: the walk is run again and again.
        control("reset-catalog")
        control("clear-stock")
        control("clear-attempts")
        control("stock-points", """{"places": ["p1", "p2"]}""")
        // The stand keeps whatever an earlier run left: step 13 changes this password.
        control("reset-password", """{"login": "stock"}""")

        val dir = Files.createTempDirectory("restoapp-real-walk").toFile()
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(dir),
            debug = true,
            device = DeviceInfo("Real walk", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        runComposeUiTest {
            setContent { RestoApp(host) }
            waitForIdle()

            // 1. Connect the server by address and sign in as the Stock Manager.
            openMenu()
            onNodeWithTag("context:row").performClick()
            waitForIdle()
            onNodeWithTag("context:add").performClick()
            waitForIdle()
            onNodeWithTag("servers:add-custom").performClick()
            waitForIdle()
            onNodeWithTag("servers:address").performTextInput(address)
            onNodeWithTag("servers:check").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("servers:found") }
            onNodeWithTag("servers:login").performTextInput("stock")
            onNodeWithTag("servers:password").performTextInput("secret-password")
            onNodeWithTag("servers:connect").performClick()
            waitUntil(timeoutMillis = 40_000L) {
                if (exists("servers:problem")) error("the server refused the sign-in: see servers:problem")
                host.active.value.superizer.registry.get(AppId("dishes")) != null && exists("home:tile-dishes")
            }
            waitForIdle()

            // 2. «Блюда» is on Home, and it opens.
            onNodeWithTag("home:tile-dishes").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("dishes:list") }

            // 3. The point of the app is shown — two points, so there is something to switch.
            onNodeWithTag("dishes:place").assertExists()

            // 4. Nothing is limited at p1 yet; the whole catalog is three dishes.
            onNodeWithTag("dishes:chip-all").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("dishes:row-d1") }
            for (dish in listOf("d1", "d2", "d3")) onNodeWithTag("dishes:row-$dish").assertExists()

            // 5. The search, by name and by code.
            onNodeWithTag("dishes:search").performTextInput("Плов")
            waitUntil(timeoutMillis = 20_000L) { !exists("dishes:row-d1") && exists("dishes:row-d2") }
            onNodeWithTag("dishes:search").performTextClearance()
            onNodeWithTag("dishes:search").performTextInput("M1")
            waitUntil(timeoutMillis = 20_000L) { exists("dishes:row-d3") && !exists("dishes:row-d2") }
            onNodeWithTag("dishes:search").performTextClearance()
            waitUntil(timeoutMillis = 20_000L) { exists("dishes:row-d1") }

            // 6. The card of Борщ: set 5 through the stepper's field, then + and −.
            onNodeWithTag("dishes:row-d1").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("dishes:detail") }
            onNodeWithTag("dishes:dishes.setBalance:field").performTextInput("5")
            onNodeWithTag("dishes:dishes.setBalance:set").performClick()
            waitUntil(timeoutMillis = 20_000L) { balanceAtP1("d1") == 5 }
            onNodeWithTag("dishes:dishes.setBalance:plus").performClick()
            waitUntil(timeoutMillis = 20_000L) { balanceAtP1("d1") == 6 }
            onNodeWithTag("dishes:dishes.setBalance:minus").performClick()
            waitUntil(timeoutMillis = 20_000L) { balanceAtP1("d1") == 5 }

            // 7. «В продаже» off on the card: the hand-set 5 stays, the effective stock is a stop.
            onNodeWithTag("dishes:dishes.setEnabled").performClick()
            waitUntil(timeoutMillis = 20_000L) { balanceAtP1("d1") == 0 }
            assertEquals(5, localBalanceAtP1("d1"), "the hand-set number survives the switch")
            onNodeWithTag("dishes:dishes.setEnabled").performClick()
            waitUntil(timeoutMillis = 20_000L) { balanceAtP1("d1") == 5 }

            // 8. «Безлимит» — the special below zero, which the ± cannot reach.
            onNodeWithTag("dishes:dishes.setBalance:special--1").performClick()
            waitUntil(timeoutMillis = 20_000L) { balanceAtP1("d1") == -1 }
            // And back to a number, so the row below has a limit to show.
            onNodeWithTag("dishes:dishes.setBalance:field").performTextInput("5")
            onNodeWithTag("dishes:dishes.setBalance:set").performClick()
            waitUntil(timeoutMillis = 20_000L) { balanceAtP1("d1") == 5 }

            // 9. Back to the list: the row has the stepper, and it works there too.
            onNodeWithTag("dishes:back").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("dishes:list") }
            onNodeWithTag("dishes:chip-limited").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("dishes:row-d1") && !exists("dishes:row-d2") }
            onNodeWithTag("dishes:row-d1:dishes.setBalance:plus").performClick()
            waitUntil(timeoutMillis = 20_000L) { balanceAtP1("d1") == 6 }
            onNodeWithTag("dishes:row-d1:dishes.setEnabled").assertIsSelected()
            onNodeWithTag("dishes:row-d1:dishes.setEnabled").performClick()
            waitUntil(timeoutMillis = 20_000L) { balanceAtP1("d1") == 0 }
            onNodeWithTag("dishes:row-d1:dishes.setEnabled").assertIsNotSelected()
            onNodeWithTag("dishes:row-d1:dishes.setEnabled").performClick()
            waitUntil(timeoutMillis = 20_000L) { balanceAtP1("d1") == 6 }

            // 10. The stop chip holds what was stopped, and nothing else.
            onNodeWithTag("dishes:row-d1:dishes.setBalance:minus").performClick()
            waitUntil(timeoutMillis = 20_000L) { balanceAtP1("d1") == 5 }
            onNodeWithTag("dishes:chip-stop").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("dishes:empty") }

            // 11. The other point: its own stock, nothing of p1's.
            onNodeWithTag("dishes:chip-limited").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("dishes:row-d1") }
            onNodeWithTag("dishes:place").performClick()
            waitForIdle()
            onNodeWithTag("dishes:places:item-p2").performClick()
            waitUntil(timeoutMillis = 20_000L) { !exists("dishes:row-d1") }
            assertEquals(-1, balanceAt("p2", "d1"), "p1's five never reached p2")
            onNodeWithTag("dishes:place").performClick()
            waitForIdle()
            onNodeWithTag("dishes:places:item-p1").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("dishes:row-d1") }

            // 12. The point is taken from the group: the app loses it on the next request.
            control("stock-points", """{"places": ["p1"]}""")
            onNodeWithTag("dishes:chip-all").performClick()
            waitUntil(timeoutMillis = 20_000L) {
                host.active.value.session.declaration.value?.app("dishes")?.places?.size == 1
            }
            waitForIdle()

            // 13. The server forgets the device: the host asks to sign in again, and does.
            control("password", """{"login": "stock"}""")
            host.active.value.session.refreshInBackground()
            waitUntil(timeoutMillis = 20_000L) { host.active.value.session.status.value == ServerStatus.SignedOut }
            assertTrue(!dir.resolve("servers").walkTopDown().any { it.name == "session.json" }, "the token is gone from the disk")
        }

        runComposeUiTest {
            setContent { RestoApp(host) }
            waitUntil(timeoutMillis = 10_000L) { exists("home:sign-in") }
            onNodeWithTag("home:sign-in:action").performClick()
            waitForIdle()
            onNodeWithTag("login:password").performTextInput("changed")
            onNodeWithTag("login:submit").performClick()
            waitUntil(timeoutMillis = 60_000L) { host.active.value.session.status.value == ServerStatus.Online }
            waitForIdle()
            onNodeWithTag("home:sign-in").assertDoesNotExist()
            // The menu is rebuilt only when it changes, so the tile comes back a frame later.
            waitUntil(timeoutMillis = 20_000L) { exists("home:tile-dishes") }
        }

        // 14. What the server itself holds after the walk: the stock of p1 the client wrote.
        assertEquals(5, localBalanceAtP1("d1"), "the five the client set is the server's number")

        // 15. A Stock Manager is given no «Заказы»: that app is the operator's, and the walk of it
        // is `theWholeOrdersAppAgainstTheRealServer` below.
        assertTrue(
            host.active.value.session.declaration.value?.app("orders") == null,
            "a stock manager must not be given the orders app",
        )
    }

    /**
     * «Заказы» of slice 2 through the window, as the operator of «Ленина, 5»: the orders of the
     * point and of no other, the card, the walk of the flow by its buttons, «Отклонить» asking
     * first, the polling catching a move made on the web board, and «Без кухни» handing an order a
     * kitchen. Against the same stand, so every answer comes from core through the hook.
     */
    @Test
    fun theWholeOrdersAppAgainstTheRealServer() {
        val address = base ?: run {
            println("SKIPPED: no -Prestoapp.serverUrl=<url> of a running tools/real-server")
            return
        }
        control("clear-attempts")
        control("reset-password", """{"login": "operator"}""")

        // One order of this point, one of another, one that arrived with no kitchen at all.
        val mine = idOf(control("order", """{"dish": "d1", "amount": 2, "place": "p1"}"""))
        val theirs = idOf(control("order-delivery", """{"dish": "d1", "place": "p2"}"""))
        val waiting = idOf(control("order-without-kitchen", """{"dish": "d2"}"""))

        val dir = Files.createTempDirectory("restoapp-real-orders").toFile()
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(dir),
            debug = true,
            device = DeviceInfo("Real orders walk", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        runComposeUiTest {
            setContent { RestoApp(host) }
            waitForIdle()

            // 1. Connect the server and sign in as the operator.
            openMenu()
            onNodeWithTag("context:row").performClick()
            waitForIdle()
            onNodeWithTag("context:add").performClick()
            waitForIdle()
            onNodeWithTag("servers:add-custom").performClick()
            waitForIdle()
            onNodeWithTag("servers:address").performTextInput(address)
            onNodeWithTag("servers:check").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("servers:found") }
            onNodeWithTag("servers:login").performTextInput("operator")
            onNodeWithTag("servers:password").performTextInput("secret-password")
            onNodeWithTag("servers:connect").performClick()
            waitUntil(timeoutMillis = 40_000L) {
                if (exists("servers:problem")) error("the server refused the sign-in: see servers:problem")
                host.active.value.superizer.registry.get(AppId("orders")) != null && exists("home:tile-orders")
            }
            waitForIdle()

            // 2. «Заказы» opens on «Ленина, 5», and «Блюда» is not there at all.
            onNodeWithTag("home:tile-dishes").assertDoesNotExist()
            onNodeWithTag("home:tile-orders").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("orders:list") }
            waitUntil(timeoutMillis = 20_000L) { exists("orders:row-$mine") }
            onNodeWithTag("orders:row-$theirs").assertDoesNotExist()

            // 3. The card of the order, and the flow by its buttons.
            onNodeWithTag("orders:row-$mine").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("orders:detail") }
            onNodeWithTag("orders:orders.setState-COOKING").performClick()
            waitUntil(timeoutMillis = 20_000L) { stateOf(mine) == "COOKING" }
            waitForIdle()
            // From COOKING there is no way back to ORDER: the server sends no such button. Waited for
            // rather than asserted on the spot — the card learns of the move by its own re-reading,
            // and with another walk of this file running before it that takes a moment longer.
            waitUntil(timeoutMillis = 20_000L) { !exists("orders:orders.setState-COOKING") }

            // 4. «Отклонить» asks first, and «нет» changes nothing.
            onNodeWithTag("orders:orders.setState-REJECT").performClick()
            waitUntil(timeoutMillis = 10_000L) { exists("orders:orders.setState-REJECT:confirm") }
            onNodeWithTag("orders:orders.setState-REJECT:no").performClick()
            waitForIdle()
            assertEquals("COOKING", stateOf(mine), "«нет» left the order where it was")

            // 5. The web board sends it on its way; the screen asks what changed and catches up.
            control("move", """{"id": "$mine", "state": "ON_THE_WAY", "login": "operator"}""")
            waitUntil(timeoutMillis = 40_000L) {
                host.active.value.session.declaration.value != null && exists("orders:orders.setState-DONE") &&
                    !exists("orders:orders.setState-ON_THE_WAY")
            }

            // 6. And the operator finishes it from the phone.
            onNodeWithTag("orders:orders.setState-DONE").performClick()
            waitUntil(timeoutMillis = 20_000L) { stateOf(mine) == "DONE" }
            onNodeWithTag("orders:back").performClick()
            waitForIdle()

            // 7. «Без кухни»: the order that arrived without one waits there, and is given one.
            onNodeWithTag("orders:place").performClick()
            waitForIdle()
            onNodeWithTag("orders:places:item-no-kitchen").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("orders:row-$waiting") }
            onNodeWithTag("orders:row-$waiting").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("orders:orders.assignKitchen-p1") }
            onNodeWithTag("orders:orders.assignKitchen-p1").performClick()
            waitUntil(timeoutMillis = 20_000L) { placeOf(waiting) == "p1" }
            waitForIdle()

            // 8. It is among the orders of «Ленина, 5» now, and gone from «Без кухни».
            onNodeWithTag("orders:back").performClick()
            waitForIdle()
            onNodeWithTag("orders:place").performClick()
            waitForIdle()
            onNodeWithTag("orders:places:item-p1").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("orders:row-$waiting") }
        }

        println("orders on the stand: ${get("orders")}")
    }

    /**
     * «Клиенты» and the guest of the POS against the real server: the search by the last digits of a
     * number, the card, then an order for the person found — their own saved address, placed as a
     * delivery, and back on their card.
     */
    @Test
    fun theClientsAndTheGuestOfThePosAgainstTheRealServer() {
        val address = base ?: run {
            println("SKIPPED: no -Prestoapp.serverUrl=<url> of a running tools/real-server")
            return
        }
        control("clear-attempts")
        control("reset-password", """{"login": "taker"}""")

        // Who the stand knows: «Ольга Белова» +7 999 000 00 07 with one saved address.
        val olga = customer("Ольга Белова")
        val savedAddress = (olga["addresses"] as JsonArray).first().jsonObject
        val savedId = savedAddress["id"]!!.jsonPrimitive.content
        val olgaId = olga["id"]!!.jsonPrimitive.content

        val dir = Files.createTempDirectory("restoapp-real-clients").toFile()
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(dir),
            debug = true,
            device = DeviceInfo("Real clients walk", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        runComposeUiTest {
            setContent { RestoApp(host) }
            waitForIdle()

            // 1. Connect the server and sign in as the order taker.
            openMenu()
            onNodeWithTag("context:row").performClick()
            waitForIdle()
            onNodeWithTag("context:add").performClick()
            waitForIdle()
            onNodeWithTag("servers:add-custom").performClick()
            waitForIdle()
            onNodeWithTag("servers:address").performTextInput(address)
            onNodeWithTag("servers:check").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("servers:found") }
            onNodeWithTag("servers:login").performTextInput("taker")
            onNodeWithTag("servers:password").performTextInput("secret-password")
            onNodeWithTag("servers:connect").performClick()
            waitUntil(timeoutMillis = 40_000L) {
                if (exists("servers:problem")) error("the server refused the sign-in: see servers:problem")
                host.active.value.superizer.registry.get(AppId("clients")) != null && exists("home:tile-clients")
            }
            waitForIdle()

            // 2. The taker has «Клиенты» and the POS, and no «Блюда» or «Заказы» at all.
            onNodeWithTag("home:tile-pos").assertExists()
            onNodeWithTag("home:tile-dishes").assertDoesNotExist()
            onNodeWithTag("home:tile-orders").assertDoesNotExist()

            // 3. «Клиенты»: the last four digits of the number find the person.
            onNodeWithTag("home:tile-clients").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("clients:list") }
            val ivan = customer("Иван Петров")["id"]!!.jsonPrimitive.content
            onNodeWithTag("clients:row-$ivan").assertExists()
            onNodeWithTag("clients:search").performTextInput("0007")
            // The whole list was there before the search, so the wait is for the other one to go.
            waitUntil(timeoutMillis = 20_000L) { exists("clients:row-$olgaId") && !exists("clients:row-$ivan") }

            // 4. The card: the number, and the address the courier has been to.
            onNodeWithTag("clients:row-$olgaId").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("clients:detail") }
            onNodeWithText("ул. Мира, 3").assertExists()
            onNodeWithTag("clients:back").performClick()
            waitForIdle()

            // 5. The POS, at the terminal's point: a new order with one dish in it.
            goHome()
            onNodeWithTag("home:tile-pos").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("pos:new") || exists("pos:place") }
            // Two points are ticked for this group; the terminal works at one, and p1 is it.
            if (!exists("pos:new")) {
                onNodeWithTag("pos:place").performClick()
                waitForIdle()
                onNodeWithTag("pos:places:item-p1").performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("pos:new") }
            }
            onNodeWithTag("pos:new").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("pos:receipt") }
            onNodeWithTag("pos:add").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("pos:catalog") }
            onNodeWithTag("pos:catalog-search").performClick()
            onNodeWithTag("pos:catalog-search").performTextInput("Борщ")
            waitUntil(timeoutMillis = 20_000L) { exists("pos:product-d1:add") }
            onNodeWithTag("pos:product-d1:add").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("pos:product-d1:in-order") }
            repeat(3) { if (!exists("pos:receipt")) { onNodeWithTag("pos:catalog-back").performClick(); waitForIdle() } }
            waitUntil(timeoutMillis = 20_000L) { exists("pos:receipt") }

            // 6. A delivery, for the person the search finds in the guest sheet.
            onNodeWithTag("pos:step-service").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("pos:service-delivery") }
            onNodeWithTag("pos:service-delivery").performClick()
            waitUntil(timeoutMillis = 20_000L) { !exists("pos:service-delivery") }
            onNodeWithTag("pos:step-guest").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("pos:guest-find") }
            onNodeWithTag("pos:guest-find").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("pos:clients-search") }
            onNodeWithTag("pos:clients-search").performClick()
            onNodeWithTag("pos:clients-search").performTextInput("0007")
            waitUntil(timeoutMillis = 20_000L) { exists("pos:client-$olgaId") }
            onNodeWithTag("pos:client-$olgaId").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("pos:receipt") }
            onNodeWithText("Ольга Белова").assertExists()

            // 7. Their own saved address, taken by its id — the house and the flat come with it.
            onNodeWithTag("pos:step-address").performClick()
            waitForIdle()
            // The sheet of the previous step closes when its own call comes back, and that can land
            // on top of this one: opened again when that happened.
            if (!exists("pos:address-street")) {
                onNodeWithTag("pos:step-address").performClick()
                waitForIdle()
            }
            waitUntil(timeoutMillis = 20_000L) { exists("pos:address-street") }
            waitUntil(timeoutMillis = 20_000L) { exists("pos:saved-$savedId") }
            onNodeWithTag("pos:saved-$savedId").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("pos:receipt") }
            onNodeWithText("ул. Мира, 3").assertExists()

            // 8. Checked and placed: core prices the delivery of the zone on top of the dish.
            runCatching { onNodeWithTag("pos:check").performScrollTo() }
            onNodeWithTag("pos:check").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("pos:place-order") }
            runCatching { onNodeWithTag("pos:place-order").performScrollTo() }
            onNodeWithTag("pos:place-order").performClick()
            waitForIdle()
            if (exists("pos:place-order:yes")) {
                onNodeWithTag("pos:place-order:yes").performClick()
            }
            waitUntil(timeoutMillis = 30_000L) { exists("pos:placed") }

            // The order as the stand holds it: a delivery of that account, to that very address.
            val placed = orders().lastOrNull { it["user"]?.jsonPrimitive?.contentOrNull == olgaId }
                ?: error("the stand has no order of that customer: ${get("orders")}")
            assertEquals("ORDER", placed["state"]?.jsonPrimitive?.contentOrNull)
            assertEquals("delivery", placed["serviceType"]?.jsonPrimitive?.contentOrNull)
            assertEquals(olgaId, placed["user"]?.jsonPrimitive?.contentOrNull)
            assertEquals("3", placed["address"]?.jsonObject?.get("home")?.jsonPrimitive?.contentOrNull)
            assertEquals("7", placed["address"]?.jsonObject?.get("apartment")?.jsonPrimitive?.contentOrNull)
            assertTrue(
                (placed["total"]?.jsonPrimitive?.contentOrNull?.toDouble() ?: 0.0) > 300.0,
                "the delivery of the zone is charged on top of the dish: ${placed["total"]}",
            )

            // 9. And the loop closes in the app: the order is on the customer's card.
            onNodeWithTag("pos:placed-done").performClick()
            waitForIdle()
            goHome()
            onNodeWithTag("home:tile-clients").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("clients:list") }
            onNodeWithTag("clients:search").performTextInput("0007")
            waitUntil(timeoutMillis = 20_000L) { exists("clients:row-$olgaId") }
            onNodeWithTag("clients:row-$olgaId").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("clients:detail") }
            val number = placed["shortId"]!!.jsonPrimitive.content
            waitUntil(timeoutMillis = 20_000L) { runCatching { onNodeWithText("№$number", substring = true).assertExists() }.isSuccess }
        }

        println("the customers of the stand: ${get("customers")}")
    }

    /** One customer of the stand by name, as `/__fake/customers` answers them. */
    private fun customer(name: String): kotlinx.serialization.json.JsonObject {
        val people = (Json.parseToJsonElement(get("customers")).jsonObject["customers"] as JsonArray)
        return people.map { it.jsonObject }.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == name }
            ?: error("the stand knows no «$name»: ${get("customers")}")
    }

    /** The orders of the stand, oldest first. */
    private fun orders(): List<kotlinx.serialization.json.JsonObject> =
        (Json.parseToJsonElement(get("orders")).jsonObject["orders"] as JsonArray).map { it.jsonObject }

    /**
     * «Маркетинг» through the window against the real server: the section opens on its summary, the links lead
     * to the codes, a code says what it gives and what it does not, its switch is felt by core, and the editor
     * of a promotion — the client's own screen — writes a promotion the server then reads back (Q43, C22).
     */
    @Test
    fun theWholeMarketingSectionAgainstTheRealServer() {
        val address = base ?: run {
            println("SKIPPED: no -Prestoapp.serverUrl=<url> of a running tools/real-server")
            return
        }
        control("clear-attempts")
        control("reset-password", """{"login": "marketer"}""")

        val dir = Files.createTempDirectory("restoapp-real-marketing").toFile()
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(dir),
            debug = true,
            device = DeviceInfo("Real marketing walk", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        runComposeUiTest {
            setContent { RestoApp(host) }
            waitForIdle()

            // 1. Connect the server and sign in as the marketer: both rights of the section.
            openMenu()
            onNodeWithTag("context:row").performClick()
            waitForIdle()
            onNodeWithTag("context:add").performClick()
            waitForIdle()
            onNodeWithTag("servers:add-custom").performClick()
            waitForIdle()
            onNodeWithTag("servers:address").performTextInput(address)
            onNodeWithTag("servers:check").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("servers:found") }
            onNodeWithTag("servers:login").performTextInput("marketer")
            onNodeWithTag("servers:password").performTextInput("secret-password")
            onNodeWithTag("servers:connect").performClick()
            waitUntil(timeoutMillis = 40_000L) {
                if (exists("servers:problem")) error("the server refused the sign-in: see servers:problem")
                host.active.value.superizer.registry.get(AppId("marketing")) != null && exists("home:tile-marketing")
            }
            waitForIdle()

            // 2. The summary is the home screen of the section, and nothing else is on Home.
            onNodeWithTag("home:tile-dishes").assertDoesNotExist()
            onNodeWithTag("home:tile-marketing").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("marketing:detail") }
            onNodeWithTag("marketing:field-codesTotal:value").assertTextEquals("3")
            onNodeWithTag("marketing:field-promotionsByCodeOnly:value").assertTextEquals("1")

            // 3. The link of the summary opens the codes; the words are core's own.
            onNodeWithTag("marketing:link-marketing.promocodes").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("marketing:row-code-leto") }
            onNodeWithTag("marketing:row-code-dead").assertExists()

            onNodeWithTag("marketing:row-code-leto").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("marketing:detail") }
            onNodeWithTag("marketing:field-gives:value").assertTextEquals("−10% на всю корзину")
            onNodeWithTag("marketing:field-worksNow:value").assertTextEquals("Да")

            // 4. The switch of the code, and core stops taking it.
            onNodeWithTag("marketing:marketing.setCodeEnabled").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { codeIsOff("ЛЕТО10") }
            waitForIdle()
            onNodeWithTag("marketing:field-status:value").assertTextEquals("Выключен")
            onNodeWithTag("marketing:marketing.setCodeEnabled").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { !codeIsOff("ЛЕТО10") }

            // 5. Back to the summary, and on to the promotions: the three words of where each stands.
            onNodeWithTag("marketing:back").performClick()
            waitForIdle()
            onNodeWithTag("marketing:back").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("marketing:link-marketing.promotions") }
            onNodeWithTag("marketing:link-marketing.promotions").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("marketing:row-pr-summer") }
            onNodeWithTag("marketing:row-pr-old").assertExists()

            // 6. The editor of a promotion: the client's own screen, and what it writes the server reads back.
            onNodeWithTag("marketing:row-pr-lunch").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("marketing:detail") }
            onNodeWithTag("marketing:field-discountFlat:value").assertTextEquals("150\u00A0₽")
            onNodeWithTag("marketing:marketing.savePromotion").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("marketing:editor") }
            onNodeWithTag("marketing:editor-amount").performScrollTo().performTextClearance()
            onNodeWithTag("marketing:editor-amount").performTextInput("199")
            onNodeWithTag("marketing:editor-only-by-code").performScrollTo().performClick()
            onNodeWithTag("marketing:editor-save").performClick()
            waitUntil(timeoutMillis = 20_000L) { !exists("marketing:editor") }
            waitForIdle()
            onNodeWithTag("marketing:field-discountFlat:value").assertTextEquals("199\u00A0₽")
            onNodeWithTag("marketing:field-appliesNow:value").assertTextEquals("Только по коду")
        }
    }

    @Test
    fun theSystemAppAgainstTheRealServer() {
        val address = base ?: run {
            println("SKIPPED: no -Prestoapp.serverUrl=<url> of a running tools/real-server")
            return
        }
        control("clear-attempts")
        control("reset-password", """{"login": "admin"}""")
        control("reset-password", """{"login": "stock"}""")

        val dir = Files.createTempDirectory("restoapp-real-system").toFile()
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(dir),
            debug = true,
            device = DeviceInfo("Real system walk", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        runComposeUiTest {
            setContent { RestoApp(host) }
            waitForIdle()

            // 1. Connect the server and sign in as the administrator: «Система» is the one app
            // whose right — the Extensions page of the panel — nobody but an administrator holds
            // on this stand.
            connect(address, "admin", "home:tile-system")

            // 2. The card of the server: which server this is, in its own time zone, and the
            // honest word about the marketplace — the stand has no licence, so no version of any
            // module is checked at all. This is the refusal of this walk, and it is on the first
            // screen rather than behind a button.
            onNodeWithTag("home:tile-system").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("system:detail") }
            onNodeWithTag("system:field-timezone:value").assertTextEquals("Europe/Moscow")
            onNodeWithTag("system:field-marketplaceText:value").performScrollTo()
                .assertTextEquals("Лицензия не задана: версии в маркетплейсе не проверяются")
            // Its two neighbours are on this server and the administrator may open them, so the home
            // screen of the central place leads there (`ctx.canOpen`).
            onNodeWithTag("system:link-settings.list").assertExists()
            onNodeWithTag("system:link-channels.list").assertExists()

            // 2a. The restart. «Сейчас» drops everybody's connection, so it asks in words, and «нет»
            // leaves the server alone. The plan stays in the stand's memory between runs of this
            // walk, so «в полночь» is pressed only where it is still offered.
            onNodeWithTag("system:system.restart-now").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000L) { exists("system:system.restart-now:confirm") }
            onNodeWithText(
                "Перезапустить сервер сейчас? Приложение на несколько секунд перестанет отвечать, " +
                    "а заказы, которые оформляются в эту минуту, могут не пройти.",
            ).assertExists()
            onNodeWithTag("system:system.restart-now:no").performClick()
            waitForIdle()
            onNodeWithTag("system:system.restart-now").assertExists()
            // A plan for the night asks nothing: it is harmless. The card says so at once, and of
            // the two buttons only «сейчас» is left.
            if (exists("system:system.restart-midnight")) {
                onNodeWithTag("system:system.restart-midnight").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:field-restartText:value") }
            }
            onNodeWithTag("system:field-restartText:value").performScrollTo()
                .assertTextEquals("Перезагрузка запланирована на 00:00 по времени сервера.")
            onNodeWithTag("system:system.restart-midnight").assertDoesNotExist()
            onNodeWithTag("system:system.restart-now").assertExists()

            // 3. The programs of this server, as the «Extensions» page lists them: the core of the
            // installation is among them and is marked a system one.
            onNodeWithTag("system:link-system.modules").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("system:row-restocore") }
            // A row merges its semantics, so the badge of a row is looked for in the unmerged tree.
            onNodeWithTag("system:row-restocore:badge", useUnmergedTree = true).assertTextEquals("Системная")

            // 4. The search over what is installed — the panel has none, and the owner asked for
            // it: one word, and only the program it names is left.
            onNodeWithTag("system:search").performTextInput("core")
            waitUntil(timeoutMillis = 20_000L) { !exists("system:row-restographql") }
            onNodeWithTag("system:row-restocore").assertExists()
            onNodeWithTag("system:search").performTextClearance()
            waitUntil(timeoutMillis = 20_000L) { exists("system:row-restographql") }

            // 5. The chips: the system programs alone.
            onNodeWithTag("system:chip-system").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("system:row-restocore") }
            onNodeWithTag("system:row-restographql").assertExists()

            // 6. The card of a program: what its own `package.json` says about it, and the refusal
            // in words — a system program cannot be removed, and this server runs no migration at
            // all, because its database is not postgres.
            onNodeWithTag("system:row-restocore").performClick()
            // The row's own record draws the card at once; the card of the server arrives after it,
            // and only it carries the hook name — so that is what tells the card has loaded.
            waitUntil(timeoutMillis = 20_000L) { exists("system:field-hookName:value") }
            onNodeWithTag("system:field-state:value").assertTextEquals("Системная")
            onNodeWithTag("system:field-removeText:value").performScrollTo()
                .assertTextEquals("Системную программу удалить нельзя")
            onNodeWithTag("system:field-migrationsText:value").performScrollTo()
                .assertTextEquals("Миграции на этом сервере не выполняются: база не postgres")
            // Nothing can be done to a program in this slice, so no action is drawn on its card.
            onNodeWithTag("system:system.remove").assertDoesNotExist()
            onNodeWithTag("system:system.update").assertDoesNotExist()

            // 7. The right is what opens «Система»: a stock manager of the same server — who works
            // in the panel every day — has no tile for it at all.
            onNodeWithTag("system:back").performClick()
            waitForIdle()
            connect(address, "stock", "home:tile-dishes")
            onNodeWithTag("home:tile-system").assertDoesNotExist()

            // 8. The right closes the restart too: somebody who only leads the settings has the
            // card of the server, with no button on it and not a word of a plan they cannot act on.
            connect(address, "settler", "home:tile-system")
            onNodeWithTag("home:tile-system").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("system:detail") }
            onNodeWithTag("system:field-timezone:value").assertTextEquals("Europe/Moscow")
            onNodeWithTag("system:system.restart-now").assertDoesNotExist()
            onNodeWithTag("system:system.restart-midnight").assertDoesNotExist()
            onNodeWithTag("system:field-restartText:value").assertDoesNotExist()
        }
    }

    /**
     * The manager of extensions, all of it, on the fake marketplace of the stand: browsing and searching,
     * installing, the request to restart, an update that appears, a job longer than a request lives, removing,
     * and each refusal in words.
     */
    @Test
    fun theExtensionManagerAgainstTheRealServer() {
        val address = base ?: run {
            println("SKIPPED: no -Prestoapp.serverUrl=<url> of a running tools/real-server")
            return
        }
        control("clear-attempts")
        control("reset-password", """{"login": "admin"}""")
        control("reset-marketplace")
        // With a licence the marketplace is asked about versions: that is how an update is seen at all.
        control("license", """{"value": "stand-license"}""")

        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-real-extensions").toFile()),
            debug = true,
            device = DeviceInfo("Real extensions walk", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        try {
            runComposeUiTest {
                setContent { RestoApp(host) }
                waitForIdle()
                connect(address, "admin", "home:tile-system")
                onNodeWithTag("home:tile-system").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:detail") }

                // 1. The marketplace, from the home screen, and a word that finds one program.
                onNodeWithTag("system:link-system.catalog").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:row-stand-hello") }
                onNodeWithTag("system:row-stand-paid").assertExists()
                onNodeWithTag("system:search").performTextInput("здоровается")
                waitUntil(timeoutMillis = 20_000L) { !exists("system:row-stand-paid") }
                onNodeWithTag("system:row-stand-hello").assertExists()
                onNodeWithTag("system:search").performTextClearance()
                waitUntil(timeoutMillis = 20_000L) { exists("system:row-stand-paid") }

                // 2. Refusal: what the licence does not cover says so, and has no button.
                onNodeWithTag("system:row-stand-paid").performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:field-description:value") }
                onNodeWithTag("system:field-stateText:value").assertTextEquals("Недоступна по лицензии")
                onNodeWithTag("system:system.install").assertDoesNotExist()
                backOneScreen()
                waitUntil(timeoutMillis = 20_000L) { exists("system:row-stand-future") }

                // 3. Refusal: what does not suit this server says why, and nothing is left behind.
                onNodeWithTag("system:row-stand-future").performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:system.install") }
                onNodeWithTag("system:system.install").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("module-progress:error") }
                onNodeWithText("Программа не подходит этому серверу. Нет того, что ей нужно: @webresto/core").assertExists()
                onNodeWithTag("module-progress:restart-now").assertDoesNotExist()
                onNodeWithTag("module-progress:close").performClick()
                waitForIdle()
                backOneScreen()
                waitUntil(timeoutMillis = 20_000L) { exists("system:row-stand-hello") }

                // 4. Installing: the job ends, and asks for a restart.
                onNodeWithTag("system:row-stand-hello").performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:system.install") }
                onNodeWithTag("system:system.install").performScrollTo().performClick()
                waitUntil(timeoutMillis = 60_000L) { exists("module-progress:done") }
                onNodeWithText("Программа установлена").assertExists()
                onNodeWithTag("module-progress:restart").assertTextEquals("Перезагрузка запланирована на 00:00 по времени сервера.")
                onNodeWithTag("module-progress:restart-now").assertExists()
                assertTrue(get("modules").contains("\"stand-hello\""), "the manager has it: " + get("modules"))

                // 5. «Готово»: the card under the screen is right at once, with no button left.
                onNodeWithTag("module-progress:finish").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:field-stateText:value") && !exists("module-progress") }
                onNodeWithTag("system:field-stateText:value").assertTextEquals("Установлена")
                onNodeWithTag("system:system.install").assertDoesNotExist()

                // 6. The marketplace does not offer it any more, and the programs list it.
                backOneScreen()
                waitUntil(timeoutMillis = 20_000L) { !exists("system:row-stand-hello") }
                backOneScreen()
                waitUntil(timeoutMillis = 20_000L) { exists("system:detail") }
                onNodeWithTag("system:field-restartText:value").performScrollTo()
                    .assertTextEquals("Перезагрузка запланирована на 00:00 по времени сервера.")
                onNodeWithTag("system:link-system.modules").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:row-stand-hello") }

                // 7. A new version appears, and the program says so.
                control("publish", """{"appId": "stand-hello", "version": "1.1.0"}""")
                onNodeWithTag("system:row-stand-hello").performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:system.update") }
                onNodeWithTag("system:field-version:value").assertTextEquals("1.0.0")
                onNodeWithTag("system:field-latestVersion:value").assertTextEquals("1.1.0")

                // 8. Updating.
                onNodeWithTag("system:system.update").performScrollTo().performClick()
                waitUntil(timeoutMillis = 60_000L) { exists("module-progress:done") }
                onNodeWithText("Программа обновлена").assertExists()
                onNodeWithTag("module-progress:finish").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000L) { !exists("module-progress") && !exists("system:system.update") }
                onNodeWithTag("system:field-version:value").assertTextEquals("1.1.0")
                assertTrue(get("modules").contains("\"version\":\"1.1.0\""), "the manager has the update: " + get("modules"))
                backOneScreen()
                waitUntil(timeoutMillis = 20_000L) { exists("system:row-stand-hello") }
                backOneScreen()
                waitUntil(timeoutMillis = 20_000L) { exists("system:detail") }

                // 9. A job longer than any request lives: the fake's download waits 20 seconds, a request 15.
                onNodeWithTag("system:link-system.catalog").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:row-stand-slow") }
                onNodeWithTag("system:row-stand-slow").performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:system.install") }
                val startedAt = System.currentTimeMillis()
                onNodeWithTag("system:system.install").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000L) {
                    onAllNodesWithText("Скачиваем из маркетплейса").fetchSemanticsNodes().isNotEmpty()
                }

                // 10. The screen can be left while it goes on, and the next job is refused in words.
                onNodeWithTag("module-progress:close").performClick()
                waitForIdle()
                backOneScreen()
                waitUntil(timeoutMillis = 20_000L) { exists("system:row-stand-locked") }
                onNodeWithTag("system:row-stand-locked").performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:system.install") }
                onNodeWithTag("system:system.install").performScrollTo().performClick()
                waitUntil(timeoutMillis = 15_000L) { exists("module-progress:refused") }
                onNodeWithText("Сейчас идёт другое задание, дождитесь его конца: stand-slow").assertExists()
                onNodeWithTag("module-progress:close").performClick()
                waitForIdle()
                backOneScreen()
                waitUntil(timeoutMillis = 20_000L) { exists("system:row-stand-slow") }

                // 11. The same button comes back to the job, and it reaches the end.
                onNodeWithTag("system:row-stand-slow").performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:system.install") }
                onNodeWithTag("system:system.install").performScrollTo().performClick()
                waitUntil(timeoutMillis = 60_000L) { exists("module-progress:done") }
                assertTrue(System.currentTimeMillis() - startedAt > 15_000L, "it outlived the timeout of a request")
                onNodeWithTag("module-progress:finish").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000L) { !exists("module-progress") }
                backOneScreen()
                waitUntil(timeoutMillis = 20_000L) { !exists("system:row-stand-slow") }
                backOneScreen()
                waitUntil(timeoutMillis = 20_000L) { exists("system:detail") }

                // 12. Removing asks first, and «Отмена» leaves it in place.
                onNodeWithTag("system:link-system.modules").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:row-stand-hello") }
                onNodeWithTag("system:row-stand-hello").performClick()
                waitUntil(timeoutMillis = 20_000L) { exists("system:system.remove") }
                onNodeWithTag("system:system.remove").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000L) { exists("module-progress:question") }
                onNodeWithText("Удалить программу? Её файлы и настройки будут удалены.").assertExists()
                onNodeWithTag("module-progress:no").performClick()
                waitForIdle()
                assertTrue(get("modules").contains("\"stand-hello\""), "«Отмена» removes nothing")

                // 13. And with «да» it goes.
                onNodeWithTag("system:system.remove").performScrollTo().performClick()
                waitUntil(timeoutMillis = 10_000L) { exists("module-progress:yes") }
                onNodeWithTag("module-progress:yes").performClick()
                waitUntil(timeoutMillis = 60_000L) { exists("module-progress:done") }
                onNodeWithText("Программа удалена").assertExists()
                onNodeWithTag("module-progress:finish").performScrollTo().performClick()
                waitUntil(timeoutMillis = 20_000L) { !exists("module-progress") && !exists("system:system.remove") }
                assertTrue(!get("modules").contains("\"stand-hello\""), "the manager let it go: " + get("modules"))
                backOneScreen()
                waitUntil(timeoutMillis = 20_000L) { exists("system:row-restocore") && !exists("system:row-stand-hello") }
            }
        } finally {
            control("license", """{"value": ""}""")
            control("reset-marketplace")
        }
    }

    /**
     * The first time «Каналы продаж» and «Система» stand on one server: the last row of the types is
     * a line into another app, and the catalog there has to take what the line sends. Its schema is
     * strict, so a parameter it does not know would be a validation error on this very screen. Nothing
     * the stand's marketplace sells is a sales channel, which is how the narrowing shows: no program.
     */
    @Test
    fun theLineOfTheChannelsOpensTheCatalogOfTheSystem() {
        val address = base ?: run {
            println("SKIPPED: no -Prestoapp.serverUrl=<url> of a running tools/real-server")
            return
        }
        control("clear-attempts")
        control("reset-password", """{"login": "admin"}""")

        val dir = Files.createTempDirectory("restoapp-real-line").toFile()
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(dir),
            debug = true,
            device = DeviceInfo("Real line walk", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        runComposeUiTest {
            setContent { RestoApp(host) }
            waitForIdle()
            connect(address, "admin", "home:tile-channels")

            onNodeWithTag("home:tile-channels").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("channels:list") }
            waitUntil(timeoutMillis = 20_000L) { exists("channels:row-channel-types") }
            onNodeWithTag("channels:row-channel-types").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("channels:row-marketplace") }

            onNodeWithTag("channels:row-marketplace").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("system:list") || exists("system:error") }
            onNodeWithTag("system:error").assertDoesNotExist()
            onNodeWithTag("system:row-stand-hello").assertDoesNotExist()
        }
    }

    /**
     * The one press that takes the stand down, so it is asked for by name and runs alone:
     *
     *   RESTOAPP_RESTART_STAND=1 ./gradlew :composeApp:desktopTest \
     *     --tests '*RealServerWalkTest.theRestartNowAgainstTheRealServer' -Prestoapp.serverUrl=<url>
     */
    @Test
    fun theRestartNowAgainstTheRealServer() {
        val address = base ?: return
        if (System.getenv("RESTOAPP_RESTART_STAND") != "1") {
            println("SKIPPED: this walk exits the stand, set RESTOAPP_RESTART_STAND=1")
            return
        }
        control("clear-attempts")
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-real-restart").toFile()),
            debug = true,
            device = DeviceInfo("Real restart walk", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        runComposeUiTest {
            setContent { RestoApp(host) }
            waitForIdle()
            connect(address, "admin", "home:tile-system")
            onNodeWithTag("home:tile-system").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("system:detail") }

            onNodeWithTag("system:system.restart-now").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000L) { exists("system:system.restart-now:confirm") }
            onNodeWithTag("system:system.restart-now:yes").performClick()

            // The answer gets out before the process goes: the card says what is going on, and
            // nothing is left to press.
            waitUntil(timeoutMillis = 10_000L) { exists("system:field-restartText:value") }
            onNodeWithTag("system:field-restartText:value").performScrollTo()
                .assertTextEquals("Сервер перезапускается")
            onNodeWithTag("system:system.restart-now").assertDoesNotExist()
            onNodeWithTag("system:system.restart-midnight").assertDoesNotExist()
        }
    }

    /**
     * Installing and then the request to restart taken up, from the progress screen itself. It takes the stand
     * down too, so it runs alone; what comes back is checked by starting the stand again.
     */
    @Test
    fun theInstallAndTheRestartItAsksForAgainstTheRealServer() {
        val address = base ?: return
        if (System.getenv("RESTOAPP_RESTART_STAND") != "2") {
            println("SKIPPED: this walk exits the stand, set RESTOAPP_RESTART_STAND=2")
            return
        }
        control("clear-attempts")
        control("reset-marketplace")
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-real-install-restart").toFile()),
            debug = true,
            device = DeviceInfo("Real install and restart walk", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        runComposeUiTest {
            setContent { RestoApp(host) }
            waitForIdle()
            connect(address, "admin", "home:tile-system")
            onNodeWithTag("home:tile-system").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("system:detail") }
            onNodeWithTag("system:link-system.catalog").performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("system:row-stand-hello") }
            onNodeWithTag("system:row-stand-hello").performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("system:system.install") }
            onNodeWithTag("system:system.install").performScrollTo().performClick()
            waitUntil(timeoutMillis = 60_000L) { exists("module-progress:restart-now") }

            onNodeWithTag("module-progress:restart-now").performScrollTo().performClick()
            waitUntil(timeoutMillis = 10_000L) { exists("module-progress:restart-now:confirm") }
            onNodeWithTag("module-progress:restart-now:yes").performClick()
            waitUntil(timeoutMillis = 10_000L) { exists("module-progress:restarting") }
            onNodeWithText("Сервер перезапускается").assertExists()
        }
    }

    /** «Назад»: a card scrolls it away, a list does not scroll it at all. */
    private fun androidx.compose.ui.test.ComposeUiTest.backOneScreen() {
        runCatching { onNodeWithTag("system:back").performScrollTo() }
        onNodeWithTag("system:back").performClick()
    }

    /** Adds the stand as another server of the client and signs in, as every walk above does. */
    private fun androidx.compose.ui.test.ComposeUiTest.connect(address: String, login: String, tile: String) {
        openMenu()
        onNodeWithTag("context:row").performClick()
        waitForIdle()
        onNodeWithTag("context:add").performClick()
        waitForIdle()
        onNodeWithTag("servers:add-custom").performClick()
        waitForIdle()
        onNodeWithTag("servers:address").performTextInput(address)
        onNodeWithTag("servers:check").performClick()
        waitUntil(timeoutMillis = 20_000L) { exists("servers:found") }
        onNodeWithTag("servers:login").performTextInput(login)
        onNodeWithTag("servers:password").performTextInput("secret-password")
        onNodeWithTag("servers:connect").performClick()
        waitUntil(timeoutMillis = 40_000L) {
            if (exists("servers:problem")) error("the server refused the sign-in: see servers:problem")
            exists(tile)
        }
        waitForIdle()
    }

    /** What the stand's own API says about a code: switched off, core does not take it any more. */
    private fun codeIsOff(code: String): Boolean = get("promocode?code=$code").contains("\"enable\":false")

    private fun idOf(answer: String): String =
        Regex("\"id\":\"([^\"]+)\"").find(answer)?.groupValues?.get(1)
            ?: error("the stand placed no order: $answer")

    /** The state of one order as the stand's database holds it. */
    private fun stateOf(id: String): String? = orderField(id, "state")

    /** The point the order is led by, as core answers it — `no-kitchen` while it has none. */
    private fun placeOf(id: String): String? = orderField(id, "placeId")

    /** One field of one order, out of what the stand's `/__fake/orders` answers. */
    private fun orderField(id: String, field: String): String? {
        val orders = (Json.parseToJsonElement(get("orders")).jsonObject["orders"] as JsonArray)
        val order = orders.map { it.jsonObject }.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == id } ?: return null
        return (order[field] as? JsonPrimitive)?.contentOrNull
    }

    /** The effective stock the web Stock Manager reads at that point. */
    private fun balanceAt(place: String, dish: String): Int? =
        Regex("\"balance\":(-?\\d+)").find(get("web-stock?place=$place&id=$dish"))?.groupValues?.get(1)?.toInt()

    private fun balanceAtP1(dish: String) = balanceAt("p1", dish)

    private fun localBalanceAtP1(dish: String): Int? =
        Regex("\"localBalance\":(-?\\d+)").find(get("dish-place?place=p1&id=$dish"))?.groupValues?.get(1)?.toInt()

    private fun androidx.compose.ui.test.ComposeUiTest.exists(tag: String) = runCatching { onNodeWithTag(tag).assertExists() }.isSuccess

    /** Back to Home through the shell's menu: «Главная» is the host's own word for it. */
    private fun androidx.compose.ui.test.ComposeUiTest.goHome() {
        openMenu()
        onNodeWithText("Главная").performClick()
        waitForIdle()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.openMenu() {
        onNode(hasContentDescription("Открыть меню")).performClick()
        waitForIdle()
    }
}
