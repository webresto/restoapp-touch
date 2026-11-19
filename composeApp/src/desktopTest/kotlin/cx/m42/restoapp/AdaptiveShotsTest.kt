package cx.m42.restoapp

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.superizer.runtime.NetworkRequest
import cx.m42.superizer.runtime.NetworkResponse
import cx.m42.superizer.runtime.NetworkService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import cx.m42.superizer.ThemeMode
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Not a check but a look (02-host.md, 06-quality.md): the demo server's main states in the three
 * window classes — phone 420, tablet 800, desktop 1280 — in the light and the dark, as PNGs in
 * `composeApp/build/screenshots-adaptive/<width>-<theme>/`.
 */
@OptIn(ExperimentalTestApi::class)
class AdaptiveShotsTest {

    @Test fun phoneLight() = all(420, ThemeMode.Light)
    @Test fun phoneDark() = all(420, ThemeMode.Dark)
    @Test fun tabletLight() = all(800, ThemeMode.Light)
    @Test fun tabletDark() = all(800, ThemeMode.Dark)
    @Test fun desktopLight() = all(1280, ThemeMode.Light)
    @Test fun desktopDark() = all(1280, ThemeMode.Dark)

    /** One fresh window per section: each starts at Home and opens its app by the navigation. */
    private fun all(width: Int, theme: ThemeMode) = Section.entries.forEach { walk(width, theme, it) }

    private enum class Section { Orders, Pos, Marketing, Connect, Dishes, Clients, Codes, Menu }

    private fun walk(width: Int, theme: ThemeMode, section: Section) = runDesktopComposeUiTest(width = width, height = 820) {
        val out = File("build/screenshots-adaptive/$width-${theme.name.lowercase()}").apply { mkdirs() }
        fun shot(name: String) {
            waitForIdle()
            // The whole scene, so a dialog (a popup — a second root) is in the picture too.
            ImageIO.write(captureToImage().toAwtImage(), "png", File(out, "$name.png"))
        }
        fun tap(tag: String) {
            onNodeWithTag(tag).performClick()
            waitForIdle()
        }
        fun appears(tag: String) = waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag(tag).assertExists() }.isSuccess }
        fun exists(tag: String) = runCatching { onNodeWithTag(tag).assertExists() }.isSuccess
        fun tapBelow(tag: String) {
            runCatching { onNodeWithTag(tag).performScrollTo() }
            waitForIdle()
            tap(tag)
        }
        fun firstTag(prefix: String, suffix: String): String = onAllNodes(tagStartsWith(prefix)).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .first { it.endsWith(suffix) }
        fun rows(prefix: String) = waitUntil(timeoutMillis = 5_000L) { onAllNodes(tagStartsWith(prefix)).fetchSemanticsNodes().isNotEmpty() }

        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-adaptive").toFile()),
            debug = true,
            network = FakeServer,
            clock = { 1_700_000_000_000 },
        ).also {
            it.active.value.superizer.settings.chooseLanguage("ru")
            it.active.value.superizer.settings.chooseTheme(theme)
        }
        setContent { RestoApp(host) }
        when (section) {
            Section.Orders -> {
                appears("home:summary")
                shot("01-home")
                openApp("orders")
                rows("orders:row-")
                shot("02-orders")
                // A phone has the board's tabs (P6): one stage of the orders.
                if (exists("orders:board-tabs")) {
                    tap("orders:board-tab-COOKING")
                    shot("02a-orders-cooking")
                    tap("orders:board-tab-all")
                }
                onAllNodes(tagStartsWith("orders:row-")).onFirst().performClick()
                appears("orders:title")
                shot("03-order")
            }
            Section.Pos -> {
                appears("home:summary")
                openApp("pos")
                appears("pos:new")
                shot("04-pos")
                tap("pos:new")
                appears("pos:receipt")
                shot("05-pos-receipt")
                // The catalog: a page of its own on a phone, beside the receipt wider.
                if (exists("pos:add")) tapBelow("pos:add")
                rows("pos:product-")
                shot("05a-pos-catalog")
                // A pizza has something to choose: its «+» opens the chooser.
                tap(firstTag("pos:product-", ":add"))
                appears("pos:sheet-options")
                tap("pos:option-o-thick")
                shot("05b-pos-options")
                tap("pos:options-save")
                tapBelow("pos:group-Напитки")
                waitForIdle()
                tap(firstTag("pos:product-", ":add"))
                tap(firstTag("pos:product-", ":add"))
                if (exists("pos:to-receipt")) tap("pos:to-receipt")
                appears("pos:receipt")
                shot("05c-pos-receipt-lines")
                // An order field opens its dialog.
                tapBelow("pos:step-guest")
                appears("pos:guest-name")
                onNodeWithTag("pos:guest-name").performTextInput("Сергей Крылов")
                onNodeWithTag("pos:guest-phone").performTextInput("+7 999 123-45-67")
                shot("05d-pos-field-dialog")
                tap("pos:guest-save")
                tapBelow("pos:step-payment")
                appears("pos:payment-cash")
                tap("pos:payment-cash")
                tapBelow("pos:check")
                appears("pos:place-order")
                shot("05e-pos-checked")
                tapBelow("pos:place-order")
                appears("pos:placed")
                shot("05f-pos-accepted")
            }
            Section.Marketing -> {
                appears("home:summary")
                openApp("marketing")
                appears("marketing:title")
                shot("06-marketing")
                // Its sections (P5): the bar of a phone, the tabs of a wider window.
                if (exists("sections:marketing.promotions")) tap("sections:marketing.promotions")
                else {
                    onAllNodes(androidx.compose.ui.test.hasText("Акции") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("sections:tabs")), useUnmergedTree = true).onFirst().performClick()
                    waitForIdle()
                }
                rows("marketing:row-")
                shot("06a-marketing-promotions")
            }
            Section.Dishes -> {
                appears("home:summary")
                openApp("dishes")
                rows("dishes:row-")
                shot("11-dishes")
                onAllNodes(tagStartsWith("dishes:row-")).onFirst().performClick()
                appears("dishes:title")
                shot("12-dish")
            }
            Section.Clients -> {
                appears("home:summary")
                openApp("clients")
                rows("clients:row-")
                shot("13-clients")
                tap("clients:add")
                appears("clients:form:name")
                onNodeWithTag("clients:form:name").performTextInput("Сергей Крылов")
                shot("14-client-form")
            }
            Section.Codes -> {
                appears("home:summary")
                openApp("marketing")
                appears("marketing:title")
                onNodeWithTag("marketing:link-marketing.promocodes").performScrollTo().performClick()
                waitForIdle()
                rows("marketing:row-")
                shot("15-codes")
            }
            Section.Menu -> {
                // The mark of «Заказы» (P4): asked on connecting, so it is there by the time the navigation is —
                // on the bottom bar of a phone, the rail of a tablet, the sidebar of a desktop.
                appears("home:summary")
                waitUntil(timeoutMillis = 5_000L) { host.active.value.superizer.handler.badges.value.isNotEmpty() }
                waitForIdle()
                mainClock.advanceTimeBy(600)
                shot("16-menu-badge")
            }
            Section.Connect -> {
                // «Подключить сервер» from the switcher: the context line of the top bar on a phone, the sidebar's
                // row on a desktop, the rail's mark on a tablet.
                appears("home:summary")
                openContexts()
                mainClock.advanceTimeBy(600)
                tap("context:add")
                appears("servers:add-custom")
                shot("07-connect-choice")
                tap("servers:add-custom")
                appears("servers:address")
                onNodeWithTag("servers:address").performTextInput("https://admin.pizza-north.ru")
                shot("08-connect-address")
                tap("servers:check")
                appears("servers:found")
                onNodeWithTag("servers:login").performTextInput("admin")
                onNodeWithTag("servers:password").performTextInput("wrong")
                shot("09-connect-sign-in")
                tap("servers:connect")
                appears("servers:problem")
                shot("10-connect-refused")
            }
        }
    }

    /** A RestoApp that answers `server.about` and refuses every login: enough to walk the flow. */
    private object FakeServer : NetworkService {
        override val online: StateFlow<Boolean> = MutableStateFlow(true)
        override suspend fun request(request: NetworkRequest): NetworkResponse = when {
            request.url.endsWith("server.about") -> NetworkResponse(200, """{"result":{"name":"Пицца Север","version":"2.14.1"}}""")
            request.url.endsWith("auth/challenge") -> NetworkResponse(200, """{"result":{"task":null}}""")
            else -> NetworkResponse(401, """{"error":{"code":"${MobileApiException.INVALID_CREDENTIALS}","message":"no"}}""")
        }
    }

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
