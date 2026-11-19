package cx.m42.restoapp

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cx.m42.restoapp.demo.DemoRole
import cx.m42.superizer.ThemeMode
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * «Главная» of the console (`_plan/07-prototype-1to1.md`): the day of the point under the three demo roles, in the
 * three window classes — PNGs in `composeApp/build/screenshots-home/<width>-<theme>/` — and the rule the screen
 * stands on: a block is drawn only with the right to its data, a destination only for an app the person has.
 */
@OptIn(ExperimentalTestApi::class)
class HomeShotsTest {

    @Test fun phoneLight() = walk(420, ThemeMode.Light)
    @Test fun phoneDark() = walk(420, ThemeMode.Dark)
    @Test fun tabletLight() = walk(800, ThemeMode.Light)
    @Test fun desktopLight() = walk(1280, ThemeMode.Light)
    @Test fun desktopDark() = walk(1280, ThemeMode.Dark)

    private fun walk(width: Int, theme: ThemeMode) = runDesktopComposeUiTest(width = width, height = 900) {
        val out = File("build/screenshots-home/$width-${theme.name.lowercase()}").apply { mkdirs() }
        fun shot(name: String) {
            waitForIdle()
            ImageIO.write(captureToImage().toAwtImage(), "png", File(out, "$name.png"))
        }
        fun exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-home").toFile()),
            debug = true,
            clock = { 1_700_000_000_000 },
        ).also {
            it.active.value.superizer.settings.chooseLanguage("ru")
            it.active.value.superizer.settings.chooseTheme(theme)
        }
        setContent { RestoApp(host) }

        // The administrator: money, the kitchen, the stop list — and the whole menu.
        waitUntil(timeoutMillis = 15_000L) { exists("home:stat-revenue") }
        assertTrue(exists("home:stat-orders") && exists("home:stat-check") && exists("home:stat-stop"))
        assertTrue(exists("home:new-order"))
        shot("01-home-admin")
        if (exists("shell:nav-more")) {
            onNodeWithTag("shell:nav-more").performClick()
            waitUntil(timeoutMillis = 10_000L) { exists("more:root") }
            shot("02-more-admin")
            onNodeWithTag("shell:nav-home").performClick()
        }

        // The operator leads the orders of one point: no money, no stop list, no till.
        host.setDemoRole(DemoRole.Operator)
        waitUntil(timeoutMillis = 15_000L) { exists("home:stat-orders") && !exists("home:stat-revenue") }
        assertFalse(exists("home:stat-check") || exists("home:stat-stop") || exists("home:new-order"))
        assertFalse(exists("shell:nav-pos") || exists("shell:nav-marketing"))
        shot("03-home-operator")

        // The stock manager: the dishes and nothing of the orders.
        host.setDemoRole(DemoRole.Stock)
        waitUntil(timeoutMillis = 15_000L) { exists("home:stat-stop") && !exists("home:stat-orders") }
        assertFalse(exists("home:stat-revenue") || exists("shell:nav-orders"))
        shot("04-home-stock")
    }
}
