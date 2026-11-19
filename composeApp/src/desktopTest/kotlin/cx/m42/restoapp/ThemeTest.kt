package cx.m42.restoapp

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import cx.m42.superizer.ThemeMode
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * «Оформление» in Settings: the choice is made there, the whole window repaints, and the main screens
 * in the dark — as PNGs in `composeApp/build/screenshots-dark`, for a look rather than a check.
 */
@OptIn(ExperimentalTestApi::class)
class ThemeTest {

    private val out = File("build/screenshots-dark").apply { mkdirs() }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    private fun ComposeUiTest.tap(tag: String) {
        runCatching { onNodeWithTag(tag).performScrollTo() }
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /**
     * The brightness, 0..255, of the page behind everything: its left margin half-way down. (The bottom of the
     * window is the bottom bar now, with its icons and labels.)
     */
    private fun ComposeUiTest.cornerLuma(): Int {
        waitForIdle()
        val image = onRoot().captureToImage().toAwtImage()
        val rgb = image.getRGB(3, image.height / 2)
        return ((rgb shr 16 and 0xFF) * 3 + (rgb shr 8 and 0xFF) * 6 + (rgb and 0xFF)) / 10
    }

    @Test
    fun theThemeIsChosenInSettingsAndTheScreensFollow() = runDesktopComposeUiTest(width = 420, height = 820) {
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-dark").toFile()),
            debug = true,
            clock = { 1_700_000_000_000 },
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }
        val settings = host.active.value.superizer.settings
        setContent { RestoApp(host) }
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("home:summary").assertExists() }.isSuccess }

        // The settings of the app itself: the last row of «Ещё».
        onNodeWithTag("shell:nav-more").performClick()
        waitForIdle()
        tap("more:app-settings")
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("settings:theme").assertExists() }.isSuccess }
        assertEquals(ThemeMode.System, settings.theme.value)

        tap("settings:theme-dark")
        assertEquals(ThemeMode.Dark, settings.theme.value)
        assertTrue(cornerLuma() < 60, "the page is dark")
        shot("01-settings")

        tap("settings:theme-light")
        assertEquals(ThemeMode.Light, settings.theme.value)
        assertTrue(cornerLuma() > 200, "the page is light")
        tap("settings:theme-dark")

        shellBack()
        openHome()
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("home:summary").assertExists() }.isSuccess }
        shot("02-home")

        openApp("orders")
        waitUntil(timeoutMillis = 5_000L) { onAllNodes(tagStartsWith("orders:row-")).fetchSemanticsNodes().isNotEmpty() }
        shot("04-orders")

        onAllNodes(tagStartsWith("orders:row-")).onFirst().performClick()
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("orders:title").assertExists() }.isSuccess }
        shot("05-order")

        // Back to the list of the orders; from the app's first screen the bottom bar goes on.
        shellBack()
        waitForIdle()
        openApp("pos")
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("pos:new").assertExists() }.isSuccess }
        tap("pos:new")
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("pos:receipt").assertExists() }.isSuccess }
        shot("06-pos-receipt")

        onNodeWithTag("shell:nav-more").performClick()
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("more:root").assertExists() }.isSuccess }
        shot("03-more")
    }

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
