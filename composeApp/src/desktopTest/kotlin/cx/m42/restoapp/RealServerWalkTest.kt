package cx.m42.restoapp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
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
            onNodeWithTag("home:tile-dishes").performClick()
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

        // 15. The orders: a real one of core, moved the way the admin panel's board moves it. The
        // client has nothing to show for them — the mobile API declares no orders app (slice 2 of
        // server-apps-and-rights.plan.md), and the declaration says so.
        assertTrue(
            host.active.value.session.declaration.value?.app("orders") == null,
            "there is no orders app in the declaration yet",
        )
        val placed = control("order", """{"dish": "d1", "amount": 2, "place": "p1"}""")
        val id = Regex("\"id\":\"([^\"]+)\"").find(placed)?.groupValues?.get(1)
        assertTrue(id != null, "the order was placed: $placed")
        assertTrue(get("orders").contains("\"state\":\"ORDER\""), "it starts in ORDER: ${get("orders")}")
        for (state in listOf("COOKING", "ON_THE_WAY", "DONE")) {
            val moved = control("move", """{"id": "$id", "state": "$state", "login": "operator"}""")
            assertTrue(moved.contains("\"status\":200"), "the operator moves it to $state: $moved")
        }
        val finished = get("orders")
        assertTrue(finished.contains("\"state\":\"DONE\""), "it ends in DONE: $finished")
        println("orders on the stand: $finished")
    }

    /** The effective stock the web Stock Manager reads at that point. */
    private fun balanceAt(place: String, dish: String): Int? =
        Regex("\"balance\":(-?\\d+)").find(get("web-stock?place=$place&id=$dish"))?.groupValues?.get(1)?.toInt()

    private fun balanceAtP1(dish: String) = balanceAt("p1", dish)

    private fun localBalanceAtP1(dish: String): Int? =
        Regex("\"localBalance\":(-?\\d+)").find(get("dish-place?place=p1&id=$dish"))?.groupValues?.get(1)?.toInt()

    private fun androidx.compose.ui.test.ComposeUiTest.exists(tag: String) = runCatching { onNodeWithTag(tag).assertExists() }.isSuccess

    private fun androidx.compose.ui.test.ComposeUiTest.openMenu() {
        onNode(hasContentDescription("Открыть меню")).performClick()
        waitForIdle()
    }
}
