package cx.m42.restoapp

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
 * Not a check but a look (05-native-apps.md §5.2–5.6): the apps the client draws itself, on the demo server, in the
 * three window classes — 420, 800, 1280 — light and dark, as PNGs in `composeApp/build/screenshots-native/<width>-<theme>/`.
 * One fresh window per section, as AdaptiveShotsTest does it. The screens of the renderer's apps (the editor of a
 * promotion, of a setting, the progress of a job) stand in the renderer's width, whatever stage 3 makes of it.
 */
@OptIn(ExperimentalTestApi::class)
class NativeAppsShotsTest {

    @Test fun phoneLight() = all(420, ThemeMode.Light)
    @Test fun phoneDark() = all(420, ThemeMode.Dark)
    @Test fun tabletLight() = all(800, ThemeMode.Light)
    @Test fun tabletDark() = all(800, ThemeMode.Dark)
    @Test fun desktopLight() = all(1280, ThemeMode.Light)
    @Test fun desktopDark() = all(1280, ThemeMode.Dark)

    private enum class Section { Assistant, Promotion, Settings, Transfer, System }

    private fun all(width: Int, theme: ThemeMode) = Section.entries.forEach { walk(width, theme, it) }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.appears(tag: String) = waitUntil(timeoutMillis = 10_000L) { exists(tag) }

    private fun ComposeUiTest.tap(tag: String) {
        appears(tag)
        runCatching { onNodeWithTag(tag).performScrollTo() }
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun walk(width: Int, theme: ThemeMode, section: Section) = runDesktopComposeUiTest(width = width, height = 820) {
        val out = File("build/screenshots-native/$width-${theme.name.lowercase()}").apply { mkdirs() }
        fun shot(name: String) {
            waitForIdle()
            ImageIO.write(captureToImage().toAwtImage(), "png", File(out, "$name.png"))
        }
        var now = 1_700_000_000_000
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-native-shots").toFile()),
            debug = true,
            clock = { now },
        ).also {
            it.active.value.superizer.settings.chooseLanguage("ru")
            it.active.value.superizer.settings.chooseTheme(theme)
        }
        setContent { RestoApp(host) }
        when (section) {
            Section.Assistant -> {
                appears("home:summary")
                openApp("assistant")
                waitUntil(timeoutMillis = 20_000L) { exists("assistant:welcome") }
                waitUntil(timeoutMillis = 10_000L) { !exists("assistant:busy") }
                shot("01-assistant-welcome")
                onNodeWithTag("assistant:input").performTextInput("Какие у тебя инструменты?")
                tap("assistant:send")
                waitUntil(timeoutMillis = 20_000L) { exists("assistant:tool-list_tools") && exists("assistant:send") }
                tap("assistant:tool-list_tools")
                shot("02-assistant-reply")
                if (exists("assistant:conversations")) {
                    tap("assistant:conversations")
                    mainClock.advanceTimeBy(500)
                    shot("03-assistant-history")
                }
            }
            Section.Promotion -> {
                appears("home:summary")
                openApp("marketing")
                tap("marketing:link-marketing.promotions")
                tap("marketing:row-pr-summer")
                tap("marketing:marketing.savePromotion")
                appears("marketing:editor")
                shot("10-promotion-editor")
                onNodeWithTag("marketing:editor-amount").performScrollTo()
                onNodeWithTag("marketing:editor-amount").performTextInput("5")
                shot("11-promotion-unsaved")
                tap("marketing:editor-cancel")
                appears("unsaved:question")
                shot("12-promotion-leave-question")
            }
            Section.Settings -> {
                appears("home:summary")
                openApp("settings")
                tap("settings:row-PROJECT_NAME")
                tap("settings:settings.update")
                appears("settings:editor")
                shot("20-setting-editor")
                onNodeWithTag("settings:field").performTextInput(" Не Латиница")
                appears("settings:editor-problem")
                shot("21-setting-refused-by-schema")
            }
            Section.Transfer -> {
                appears("home:summary")
                openApp("settings")
                tap("settings:add")
                appears("settings:transfer")
                waitUntil(timeoutMillis = 10_000L) { exists("settings:transfer-json") }
                shot("30-transfer-export")
                // The import reads the system clipboard, which a headless run (CI, no X) has none of.
                if (java.awt.GraphicsEnvironment.isHeadless()) {
                    println("SKIPPED: the import's shots need a display (xvfb-run)")
                    return@runDesktopComposeUiTest
                }
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                    java.awt.datatransfer.StringSelection(
                        """{"settings": [{"key": "PROJECT_NAME", "value": "from-the-file"}, {"key": "CITY", "value": "Казань"}, {"key": "NO_SUCH_SETTING", "value": 1}]}""",
                    ),
                    null,
                )
                onAllNodesWithText("Импорт").onFirst().performClick()
                waitForIdle()
                shot("31-transfer-import")
                tap("settings:transfer-paste")
                appears("settings:transfer-selected")
                tap("settings:transfer-select-changed")
                shot("32-transfer-difference")
            }
            Section.System -> {
                appears("home:summary")
                openApp("channels")
                tap("channels:row-ch-vk")
                tap("channels:link-system.catalog")
                tap("system:row-sales-channel-vk")
                tap("system:system.install")
                appears("module-progress")
                shot("40-job-going")
                now += 60_000
                waitUntil(timeoutMillis = 30_000L) { exists("module-progress:done") }
                shot("41-job-done")
            }
        }
    }
}
