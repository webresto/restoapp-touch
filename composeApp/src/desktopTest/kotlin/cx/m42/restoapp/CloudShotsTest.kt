package cx.m42.restoapp

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.demo.DemoCloud
import cx.m42.restoapp.demo.DemoNetwork
import cx.m42.restoapp.demo.DemoStore
import cx.m42.restoapp.protocol.MobileApi
import cx.m42.superizer.ThemeMode
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * The Resto Cloud screens (`_plan/08-cloud-screens.md`) against the demo cloud of the contract, offline — the
 * account's «Обзор», «Сервисы» with the load of each, a service's card with «Открыть управление» and the web admin
 * as a quiet link, «Баланс»; the money left in the bar, the rail or the sidebar, the strip when it runs low, in the
 * account and in a server of it. Phone 420, tablet 800, desktop 1280 → `composeApp/build/screenshots-cloud/<width>/`.
 */
@OptIn(ExperimentalTestApi::class)
class CloudShotsTest {

    @Test fun phone() = walk(420, ThemeMode.Light)
    @Test fun tablet() = walk(800, ThemeMode.Light)
    @Test fun desktop() = walk(1280, ThemeMode.Light)
    @Test fun desktopDark() = walk(1280, ThemeMode.Dark, dark = true)

    @Test fun phoneLowMoney() = low(420)
    @Test fun desktopLowMoney() = low(1280)

    private class MemoryDemoStore : DemoStore {
        var text: String? = null
        override suspend fun read(): String? = text
        override suspend fun write(text: String) {
            this.text = text
        }
    }

    /** The cloud at [CLOUD], and each of its running services a demo RestoApp at its own address. */
    private fun hostWith(cloud: DemoCloud, theme: ThemeMode): RestoAppHost {
        val servers = mutableMapOf<String, MobileApi>(CLOUD to cloud)
        cloud.services.forEach { s ->
            servers["https://${s.host}"] = DemoBackend(MemoryDemoStore(), DemoBackend.TWO_POINTS, clock = { 1_700_000_000_000 }, serverName = s.title)
        }
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-cloud-shots").toFile()),
            debug = true,
            network = DemoNetwork(servers),
            clock = { 1_700_000_000_000 },
            cloudAddress = CLOUD,
        )
        host.active.value.superizer.settings.chooseLanguage("ru")
        host.active.value.superizer.settings.chooseTheme(theme)
        runBlocking { host.addServer(CLOUD, "Resto Cloud · ${cloud.login}", cloud.login, "anna-password", cloud = true) {} }
        host.active.value.superizer.settings.chooseTheme(theme)
        return host
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Every word on screen, the merged and the unmerged tree alike. */
    private fun ComposeUiTest.texts(): String =
        onAllNodes(SemanticsMatcher("has text") { it.config.getOrNull(SemanticsProperties.Text) != null }, useUnmergedTree = true)
            .fetchSemanticsNodes().joinToString("\n") { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } }

    /** The cloud speaks money: neither «кр» nor credits anywhere on the screen. */
    private fun ComposeUiTest.assertNoCredits(where: String) {
        val words = texts()
        assertFalse(Regex("""(^|[^а-яё])кр([^а-яё.]|$)|кредит|credit""", RegexOption.IGNORE_CASE).containsMatchIn(words), "credits on $where:\n$words")
    }

    private fun ComposeUiTest.shooter(dir: String): (String) -> Unit {
        val out = File("build/screenshots-cloud/$dir").apply { mkdirs() }
        return { name ->
            waitForIdle()
            ImageIO.write(((this as? androidx.compose.ui.test.SkikoComposeUiTest)?.captureToImage() ?: onRoot().captureToImage()).toAwtImage(), "png", File(out, "$name.png"))
        }
    }

    /** The money left: a chip in the phone's bar, the rail's foot on a tablet, the sidebar's card on a desktop. */
    private fun ComposeUiTest.assertBalanceShown(width: Int) {
        val tag = if (width < 600) "shell:balance-chip" else "shell:balance"
        assertTrue(exists(tag), "no $tag at $width")
    }

    private fun walk(width: Int, theme: ThemeMode, dark: Boolean = false) = runDesktopComposeUiTest(width = width, height = 900) {
        val shot = shooter(if (dark) "$width-dark" else "$width")
        val cloud = DemoCloud()
        val host = hostWith(cloud, theme)
        setContent { RestoApp(host) }

        // «Обзор»: the greeting, the broken service, the four numbers, the services with their load.
        waitUntil(timeoutMillis = 20_000L) { exists("home:cloud") && exists("home:stat-balance") }
        assertTrue(exists("home:attention-burger-lenina"))
        assertTrue(exists("home:stat-running") && exists("home:stat-per-day") && exists("home:stat-orders"))
        assertTrue(exists("home:service-pizza-north"))
        assertBalanceShown(width)
        // 42 days: nothing to warn about.
        assertFalse(exists("shell:low-money"))
        assertNoCredits("Обзор")
        // The account's line in the switcher.
        val account = host.contexts.value.first { it.id == host.current.value!!.id }
        assertEquals("1 840 ₽ · хватит на 42 дня", account.subtitle)
        // The cloud's menu: Обзор, Сервисы, Ассистент, Баланс — the rest under «Ещё» on a phone.
        assertTrue(exists("shell:nav-home") && exists("shell:nav-services") && exists("shell:nav-assistant") && exists("shell:nav-balance"))
        shot("01-overview")

        // «Сервисы»: each with the shape of its load (P8).
        openApp("services")
        waitUntil(timeoutMillis = 15_000L) { exists("services:list") && exists("services:row-pizza-north") }
        assertTrue(exists("services:row-pizza-north:series"), "no sparkline of the load")
        assertBalanceShown(width)
        assertNoCredits("Сервисы")
        shot("02-services")

        // A running service: «Открыть управление» is its main button (P9), the web admin a quiet link at the foot.
        onNodeWithTag("services:row-pizza-north").performClick()
        waitUntil(timeoutMillis = 15_000L) { exists("services:detail") && exists("services:link-server") }
        assertTrue(exists("services:summary-cpu") && exists("services:summary-memory") && exists("services:summary-disk"))
        assertFalse(exists("services:summary-cpuSeries"), "the series of a gauge is drawn again on its own")
        assertTrue(exists("services:link-quiet-0"), "the web admin is not a quiet link")
        assertFalse(exists("services:link-url-1"), "the web admin is drawn as a line")
        assertNoCredits("the card of a service")
        shot("03-service")
        onNodeWithTag("services:link-quiet-0").performScrollTo()
        shot("04-service-foot")

        // «Баланс»: money, the thirty days, by service.
        openApp("balance")
        waitUntil(timeoutMillis = 15_000L) { exists("balance:detail") && exists("balance:summary-balance") }
        assertNoCredits("Баланс")
        shot("05-balance")
    }

    /** Four days of money left: the strip in amber over the account, and over a server of it once it is opened. */
    private fun low(width: Int) = runDesktopComposeUiTest(width = width, height = 900) {
        val shot = shooter("$width-low")
        val cloud = DemoCloud(money = 172.8)
        val host = hostWith(cloud, ThemeMode.Light)
        setContent { RestoApp(host) }

        waitUntil(timeoutMillis = 20_000L) { exists("home:cloud") && exists("shell:low-money") }
        assertBalanceShown(width)
        assertNoCredits("Обзор")
        shot("01-overview-low")

        // «Открыть управление» opens the service in the app: the server of the account, signed in through the cloud.
        openApp("services")
        waitUntil(timeoutMillis = 15_000L) { exists("services:row-pizza-north") }
        onNodeWithTag("services:row-pizza-north").performClick()
        waitUntil(timeoutMillis = 15_000L) { exists("services:link-server") }
        assertTrue(exists("shell:low-money"))
        shot("02-service-low")
        val accountId = host.current.value!!.id
        onNodeWithTag("services:link-server").performClick()
        waitUntil(timeoutMillis = 30_000L) {
            host.current.value?.parent == accountId && host.current.value?.title == "Пицца Север" && exists("home:summary")
        }
        waitUntil(timeoutMillis = 15_000L) { exists("shell:low-money") }
        shot("03-server-of-the-account-low")
    }

    private companion object {
        const val CLOUD = "https://cloud.test"
    }
}
