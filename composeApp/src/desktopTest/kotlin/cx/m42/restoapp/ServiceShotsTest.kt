package cx.m42.restoapp

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
 * The screens of one service of a Resto Cloud account — the prototype's `services/<id>[/events|logs|billing|settings]`
 * and the card of a service that is starting, failed or off (prototype-rikka/cloudApp, ui/screens/Service.kt):
 * the tabs of the record, the notice with what to do (P12), the timeline of the start (P13), the card re-read while it
 * starts (P14). Phone 420, tablet 800, desktop 1280 → `composeApp/build/screenshots-service/<width>/`, which
 * `tools/compare-service.py` puts next to the prototype's.
 */
@OptIn(ExperimentalTestApi::class)
class ServiceShotsTest {

    @Test fun phone() = walk(420)
    @Test fun tablet() = walk(800)
    @Test fun desktop() = walk(1280)

    private class MemoryDemoStore : DemoStore {
        var text: String? = null
        override suspend fun read(): String? = text
        override suspend fun write(text: String) {
            this.text = text
        }
    }

    private fun hostWith(cloud: DemoCloud): RestoAppHost {
        val servers = mutableMapOf<String, MobileApi>(CLOUD to cloud)
        cloud.services.forEach { s ->
            servers["https://${s.host}"] = DemoBackend(MemoryDemoStore(), DemoBackend.TWO_POINTS, clock = { 1_700_000_000_000 }, serverName = s.title)
        }
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-service-shots").toFile()),
            debug = true,
            network = DemoNetwork(servers),
            clock = { 1_700_000_000_000 },
            cloudAddress = CLOUD,
        )
        host.active.value.superizer.settings.chooseLanguage("ru")
        runBlocking { host.addServer(CLOUD, "Resto Cloud · ${cloud.login}", cloud.login, "anna-password", cloud = true) {} }
        host.active.value.superizer.settings.chooseTheme(ThemeMode.Light)
        return host
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.stateOf(tag: String): String? =
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription)

    private fun ComposeUiTest.texts(): String =
        onAllNodes(SemanticsMatcher("has text") { it.config.getOrNull(SemanticsProperties.Text) != null }, useUnmergedTree = true)
            .fetchSemanticsNodes().joinToString("\n") { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString(" ") { it.text } }

    private fun ComposeUiTest.shooter(width: Int): (String) -> Unit {
        val out = File("build/screenshots-service/$width").apply { mkdirs() }
        return { name ->
            waitForIdle()
            ImageIO.write(((this as? androidx.compose.ui.test.SkikoComposeUiTest)?.captureToImage() ?: onRoot().captureToImage()).toAwtImage(), "png", File(out, "$name.png"))
        }
    }

    private fun ComposeUiTest.tab(title: String) {
        onNode(hasText(title) and hasAnyAncestor(hasTestTag("services:tabs")), useUnmergedTree = true).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openService(id: String) {
        waitUntil(timeoutMillis = 15_000L) { exists("services:row-$id") }
        onNodeWithTag("services:row-$id").apply { runCatching { performScrollTo() } }.performClick()
        try {
            waitUntil(timeoutMillis = 15_000L) { exists("services:detail") && exists("services:tabs") }
        } catch (e: Throwable) {
            shooter(0)("debug-$id")
            val tags = onAllNodes(SemanticsMatcher("tag") { it.config.getOrNull(SemanticsProperties.TestTag) != null }, useUnmergedTree = true)
                .fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            throw AssertionError("no tabs on $id; tags: ${tags.joinToString()}\n${texts()}", e)
        }
    }

    private fun ComposeUiTest.backToList() {
        openApp("services")
        waitUntil(timeoutMillis = 15_000L) { exists("services:list") }
    }

    private fun walk(width: Int) = runDesktopComposeUiTest(width = width, height = 900) {
        val shot = shooter(width)
        val cloud = DemoCloud()
        val host = hostWith(cloud)
        setContent { RestoApp(host) }
        waitUntil(timeoutMillis = 20_000L) { exists("home:cloud") }
        openApp("services")

        // A running service: the tabs under its header, the metrics, no notice and no timeline.
        openService("pizza-north")
        assertTrue(exists("services:summary-cpu"))
        assertFalse(exists("services:summary-notice") || exists("services:summary-launch"))
        val words = texts()
        listOf("Обзор", "События", "Логи", "Расходы", "Настройки").forEach { assertTrue(it in words, "no tab «$it»:\n$words") }
        shot("01-overview")

        // «События»: a list of the record under the card's header and tabs.
        tab("События")
        waitUntil(timeoutMillis = 15_000L) { exists("services:list") && exists("services:tabs") }
        assertTrue("Пицца Север" in texts(), "the card's name is not over its events")
        shot("02-events")

        tab("Логи")
        waitUntil(timeoutMillis = 15_000L) { "Server lifted" in texts() }
        shot("03-logs")

        tab("Расходы")
        waitUntil(timeoutMillis = 15_000L) { exists("services:summary-perDay") }
        shot("04-spend")

        // «Настройки»: only the form of the settings, not the card again.
        tab("Настройки")
        try {
            waitUntil(timeoutMillis = 15_000L) { exists("services:tabs") && "Версия RestoApp" in texts() }
        } catch (e: Throwable) {
            shot("debug-settings")
            throw AssertionError("settings:\n${texts()}", e)
        }
        assertFalse(exists("services:summary-cpu"), "the card's metrics on «Настройки»")
        shot("05-settings")

        // Back to «Обзор» by its tab.
        tab("Обзор")
        waitUntil(timeoutMillis = 15_000L) { exists("services:summary-cpu") }

        // Starting: the timeline step by step, the card read again every few seconds (P14).
        backToList()
        openService("sushi-market")
        waitUntil(timeoutMillis = 15_000L) { exists("services:summary-launch") }
        assertEquals("done", stateOf("services:summary-launch-0"))
        assertEquals("active", stateOf("services:summary-launch-2"))
        assertEquals("pending", stateOf("services:summary-launch-4"))
        assertFalse(exists("services:summary-cpu"))
        val before = cloud.calls.count { it == "services.get" }
        mainClock.advanceTimeBy(5_500)
        waitUntil(timeoutMillis = 10_000L) { cloud.calls.count { it == "services.get" } > before }
        shot("06-starting")

        // Failed: the notice with the cause and «Запустить снова», the step it stopped at; statusMessage not twice.
        backToList()
        openService("burger-lenina")
        waitUntil(timeoutMillis = 15_000L) { exists("services:summary-notice") && exists("services:summary-launch") }
        assertEquals("failed", stateOf("services:summary-launch-2"))
        assertFalse("упал 26 мин назад" in texts(), "statusMessage is drawn under the notice that says it")
        shot("07-error")
        val calls = cloud.calls.size
        onNodeWithTag("services:summary-notice-action-0").performClick()
        waitUntil(timeoutMillis = 10_000L) { cloud.calls.drop(calls).contains("services.power") }

        // Off: the notice says nothing is charged, «Запустить» in it.
        backToList()
        openService("coffee-zerno")
        waitUntil(timeoutMillis = 15_000L) { exists("services:summary-notice") }
        assertTrue(exists("services:summary-notice-action-0"))
        shot("08-stopped")
    }

    private companion object {
        const val CLOUD = "https://cloud.test"
    }
}
