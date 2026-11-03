package cx.m42.restoapp

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Not a check but a look: the window's main states as PNGs in `composeApp/build/screenshots`, at
 * the window's own size — for reviewing the switcher without a screen recorder.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenshotsTest {

    private val out = File("build/screenshots").apply { mkdirs() }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    @Test
    fun theWindowsMainStates() = runDesktopComposeUiTest(width = 420, height = 820) {
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-shots").toFile()),
            debug = true,
            clock = { 1_700_000_000_000 },
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }
        setContent { RestoApp(host) }
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("home:tile-orders").assertExists() }.isSuccess }

        shot("01-home-restaurant")

        onNodeWithContentDescription("Открыть меню").performClick()
        shot("02-drawer")

        tap("context:row")
        mainClock.advanceTimeBy(600)
        shot("03-server-sheet")

        tap("context:item-demo-point")
        shot("04-home-point")

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        tap("context:row")
        mainClock.advanceTimeBy(600)
        tap("context:add")
        tap("servers:add-custom")
        shot("05-add-server")

        host.switchTo("demo-restaurant")
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("home:tile-orders").assertExists() }.isSuccess }
        tap("home:tile-orders")
        waitUntil(timeoutMillis = 5_000L) { onAllNodes(tagStartsWith("orders:row-")).fetchSemanticsNodes().isNotEmpty() }
        shot("06-orders")

        onAllNodes(tagStartsWith("orders:row-")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("orders:title").assertExists() }.isSuccess }
        shot("07-order")

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        // The drawer's entry; the open order has a field of that name too.
        onAllNodesWithText("Блюда").onFirst().performClick()
        waitUntil(timeoutMillis = 5_000L) { onAllNodes(tagStartsWith("dishes:row-")).fetchSemanticsNodes().isNotEmpty() }
        shot("08-dishes")

        tap("dishes:place")
        mainClock.advanceTimeBy(600)
        shot("09-points")
        tap("dishes:places:item-p2")
        tap("dishes:chip-all")
        waitForIdle()
        onAllNodes(tagStartsWith("dishes:row-")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("dishes:title").assertExists() }.isSuccess }
        shot("10-dish")

        host.setDemoRole(cx.m42.restoapp.demo.DemoRole.Operator)
        waitUntil(timeoutMillis = 5_000L) { host.active.value.superizer.registry.get(cx.m42.superizer.app.AppId("dishes")) == null }
        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        shot("11-operator-menu")
    }

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
