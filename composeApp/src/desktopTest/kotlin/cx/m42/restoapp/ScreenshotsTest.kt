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
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
        // The whole scene when it is one, so a dialog (a popup — a second root) is in the picture too.
        val image = (this as? androidx.compose.ui.test.SkikoComposeUiTest)?.captureToImage() ?: onRoot().captureToImage()
        ImageIO.write(image.toAwtImage(), "png", File(out, "$name.png"))
    }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /** The same, for a row that sits below the fold of a long card: scroll to it first. */
    private fun ComposeUiTest.reach(tag: String) {
        runCatching { onNodeWithTag(tag).performScrollTo() }
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
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("home:summary").assertExists() }.isSuccess }

        shot("01-home-restaurant")

        tap("shell:nav-more")
        shot("02-more")
        openHome()

        openContexts()
        mainClock.advanceTimeBy(600)
        shot("03-server-sheet")

        tap("context:item-demo-point")
        shot("04-home-point")

        openContexts()
        mainClock.advanceTimeBy(600)
        tap("context:add")
        tap("servers:add-custom")
        shot("05-add-server")

        host.switchTo("demo-restaurant")
        waitUntil(timeoutMillis = 5_000L) {
            host.current.value?.id == "demo-restaurant" && host.active.value.superizer.registry.get(cx.m42.superizer.app.AppId("orders")) != null
        }
        openApp("orders")
        waitUntil(timeoutMillis = 5_000L) { onAllNodes(tagStartsWith("orders:row-")).fetchSemanticsNodes().isNotEmpty() }
        shot("06-orders")

        onAllNodes(tagStartsWith("orders:row-")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("orders:title").assertExists() }.isSuccess }
        shot("07-order")

        openApp("dishes")
        waitUntil(timeoutMillis = 5_000L) { onAllNodes(tagStartsWith("dishes:row-")).fetchSemanticsNodes().isNotEmpty() }
        shot("08-dishes")

        tap("shell:point")
        mainClock.advanceTimeBy(600)
        shot("09-points")
        tap("shell:point:item-p2")
        tap("dishes:chip-all")
        waitForIdle()
        onAllNodes(tagStartsWith("dishes:row-")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("dishes:title").assertExists() }.isSuccess }
        shot("10-dish")

        host.setDemoRole(cx.m42.restoapp.demo.DemoRole.Operator)
        waitUntil(timeoutMillis = 5_000L) { host.active.value.superizer.registry.get(cx.m42.superizer.app.AppId("dishes")) == null }
        openHome()
        shot("11-operator-home")
        if (runCatching { onNodeWithTag("shell:nav-more").assertExists() }.isSuccess) {
            tap("shell:nav-more")
            shot("11-operator-more")
        }
    }

    /** The POS and «Клиенты» of slice A (order-entry.plan.md), same window, same demo server. */
    @Test
    fun thePosAndTheCustomers() = runDesktopComposeUiTest(width = 420, height = 820) {
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-pos-shots").toFile()),
            debug = true,
            clock = { 1_700_000_000_000 },
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }
        setContent { RestoApp(host) }
        waitUntil(timeoutMillis = 5_000L) {
            runCatching { onNodeWithTag("home:new-order").assertExists() }.isSuccess &&
                host.active.value.superizer.registry.get(cx.m42.superizer.app.AppId("pos")) != null
        }
        shot("12-home-with-pos")

        openApp("pos")
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("pos:new").assertExists() }.isSuccess }
        shot("13-pos-no-drafts")

        tap("pos:new")
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("pos:receipt").assertExists() }.isSuccess }
        shot("14-pos-receipt")

        shellBack()
        waitUntil(timeoutMillis = 5_000L) { onAllNodes(tagStartsWith("pos:draft-")).fetchSemanticsNodes().isNotEmpty() }
        shot("15-pos-drafts")

        tap("pos:place")
        mainClock.advanceTimeBy(600)
        shot("16-pos-points")
        tap("pos:places:item-p1")

        openApp("clients")
        waitUntil(timeoutMillis = 5_000L) { onAllNodes(tagStartsWith("clients:row-")).fetchSemanticsNodes().isNotEmpty() }
        shot("17-clients")

        tap("clients:add")
        waitForIdle()
        onNodeWithTag("clients:form:name").performTextInput("Сергей Крылов")
        onNodeWithTag("clients:form:phone").performTextInput("+7 999 123-45-67")
        waitForIdle()
        shot("18-clients-form")

        tap("clients:form:submit")
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("clients:detail").assertExists() }.isSuccess }
        shot("19-client-card")
    }

    /** «Маркетинг» of slice A-D (marketing.plan.md): the summary, the codes, a card, and the editor. */
    @Test
    fun theMarketingSection() = runDesktopComposeUiTest(width = 420, height = 820) {
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-marketing-shots").toFile()),
            debug = true,
            clock = { 1_700_000_000_000 },
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }
        setContent { RestoApp(host) }
        waitUntil(timeoutMillis = 5_000L) {
            runCatching { onNodeWithTag("home:summary").assertExists() }.isSuccess &&
                host.active.value.superizer.registry.get(cx.m42.superizer.app.AppId("marketing")) != null
        }

        openApp("marketing")
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("marketing:title").assertExists() }.isSuccess }
        shot("20-marketing-summary")

        reach("marketing:link-marketing.promocodes")
        waitUntil(timeoutMillis = 5_000L) { onAllNodes(tagStartsWith("marketing:row-")).fetchSemanticsNodes().isNotEmpty() }
        shot("21-marketing-codes")

        onAllNodes(tagStartsWith("marketing:row-")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("marketing:detail").assertExists() }.isSuccess }
        shot("22-marketing-code")

        reach("marketing:link-marketing.applications")
        waitUntil(timeoutMillis = 5_000L) { onAllNodes(tagStartsWith("marketing:row-")).fetchSemanticsNodes().isNotEmpty() }
        shot("23-marketing-applications")

        // Back down the stack to the summary: the applications, the code, the codes.
        shellBack()
        shellBack()
        shellBack()
        reach("marketing:link-marketing.promotions")
        waitUntil(timeoutMillis = 5_000L) { onAllNodes(tagStartsWith("marketing:row-")).fetchSemanticsNodes().isNotEmpty() }
        shot("24-marketing-promotions")

        onAllNodes(tagStartsWith("marketing:row-")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("marketing:detail").assertExists() }.isSuccess }
        shot("25-marketing-promotion")

        // The one screen of the section the client draws itself: the editor of a promotion (Q43).
        reach("marketing:marketing.savePromotion")
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("marketing:editor").assertExists() }.isSuccess }
        shot("26-marketing-editor")

        reach("marketing:editor-dishes")
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("marketing:picker").assertExists() }.isSuccess }
        shot("27-marketing-editor-catalog")

        // And the same section for somebody who may not read an order: no money, no applications.
        // The catalog is a dialog of the editor now: its «Отмена», then the editor's cross.
        onAllNodesWithText("Отмена").onLast().performClick()
        waitForIdle()
        tap("marketing:editor-cancel")
        host.setDemoRole(cx.m42.restoapp.demo.DemoRole.Marketer)
        waitUntil(timeoutMillis = 5_000L) { host.active.value.superizer.registry.get(cx.m42.superizer.app.AppId("clients")) == null }
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("marketing:title").assertExists() }.isSuccess }
        shot("28-marketing-marketer")
    }

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
