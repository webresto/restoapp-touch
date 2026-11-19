package cx.m42.restoapp.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.demo.DemoRole
import cx.m42.restoapp.demo.DemoStore
import cx.m42.restoapp.protocol.jsonObjectOf
import cx.m42.restoapp.protocol.recordOf
import cx.m42.restoapp.render.NativeActionScope
import cx.m42.superizer.ThemeMode
import cx.m42.superizer.kit.Kit
import cx.m42.superizer.kit.KitAccent
import cx.m42.superizer.kit.WindowClassProvider
import cx.m42.superizer.kit.kitTypography
import cx.m42.superizer.kit.palette
import cx.m42.superizer.ui.shell.HostTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Not a check but a look (05-native-apps.md §5.4): the editor of a value and the transfer, composed by themselves on
 * the demo server in 420 / 800 / 1280, light and dark — `apps/settings/build/screenshots-native/<width>-<theme>/`.
 * In the host they stand in the renderer's width (stage 3); here only their own look is judged.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsNativeShotsTest {

    @Test fun phoneLight() = shots(420, ThemeMode.Light)
    @Test fun phoneDark() = shots(420, ThemeMode.Dark)
    @Test fun tabletLight() = shots(800, ThemeMode.Light)
    @Test fun tabletDark() = shots(800, ThemeMode.Dark)
    @Test fun desktopLight() = shots(1280, ThemeMode.Light)
    @Test fun desktopDark() = shots(1280, ThemeMode.Dark)

    private class MemoryStore : DemoStore {
        private var text: String? = null
        override suspend fun read(): String? = text
        override suspend fun write(text: String) {
            this.text = text
        }
    }

    private fun shots(width: Int, theme: ThemeMode) {
        val backend = DemoBackend(MemoryStore(), DemoBackend.TWO_POINTS, clock = { 1_700_000_000_000 }, serverName = "Демо")
            .also { runBlocking { it.setRole(DemoRole.Admin) } }
        val declared = runBlocking { backend.describe(DemoBackend.TOKEN, "ru").value }.app("settings")!!
        fun scopeOf(record: JsonObject, screen: String) = NativeActionScope(
            record = record,
            method = declared.methods.first { it.ui?.control?.screen == screen },
            facts = null,
            lang = "ru",
            busy = false,
            failure = null,
            call = { method, params -> backend.call(DemoBackend.TOKEN, method, params, null, "ru").value },
            submit = {},
            submitThen = { _, _ -> },
            notice = {},
            close = {},
        )
        val project = runBlocking {
            recordOf(backend.call(DemoBackend.TOKEN, "settings.get", jsonObjectOf("id" to JsonPrimitive("PROJECT_NAME")), null, "ru").value)
        }!!
        val secret = runBlocking {
            recordOf(backend.call(DemoBackend.TOKEN, "settings.get", jsonObjectOf("id" to JsonPrimitive("SALES_CHANNEL_TELEGRAM_TOKEN")), null, "ru").value)
        }!!
        val out = File("build/screenshots-native/$width-${theme.name.lowercase()}").apply { mkdirs() }

        fun frame(content: @androidx.compose.runtime.Composable () -> Unit): @androidx.compose.runtime.Composable () -> Unit = {
            HostTheme(theme, KitAccent.Violet, kitTypography()) {
                Box(Modifier.fillMaxSize().background(Kit.palette.canvas)) { WindowClassProvider { content() } }
            }
        }

        runDesktopComposeUiTest(width = width, height = 860) {
            fun shot(name: String) {
                waitForIdle()
                ImageIO.write(captureToImage().toAwtImage(), "png", File(out, "$name.png"))
            }
            setContent(frame { SettingEditor(scopeOf(project, SettingsScreens.SETTING_EDITOR)) })
            shot("01-setting-editor")
            onNodeWithTag("settings:field").performTextInput(" Не Латиница")
            shot("02-setting-refused")
        }
        runDesktopComposeUiTest(width = width, height = 860) {
            setContent(frame { SettingEditor(scopeOf(secret, SettingsScreens.SETTING_EDITOR)) })
            waitForIdle()
            ImageIO.write(captureToImage().toAwtImage(), "png", File(out, "03-setting-secret.png"))
        }
        runDesktopComposeUiTest(width = width, height = 860) {
            fun shot(name: String) {
                waitForIdle()
                ImageIO.write(captureToImage().toAwtImage(), "png", File(out, "$name.png"))
            }
            setContent(frame { SettingTransfer(scopeOf(JsonObject(emptyMap()), SettingsScreens.SETTING_TRANSFER)) })
            waitUntil(timeoutMillis = 10_000L) { onAllNodesWithTag("settings:transfer-json").fetchSemanticsNodes().isNotEmpty() }
            shot("10-transfer-export")
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                java.awt.datatransfer.StringSelection(
                    """{"settings": [{"key": "PROJECT_NAME", "value": "from-the-file"}, {"key": "CITY", "value": "Казань"}, {"key": "IMAGES_URL", "value": "x"}, {"key": "NO_SUCH_SETTING", "value": 1}]}""",
                ),
                null,
            )
            onAllNodesWithText("Импорт").onFirst().performClick()
            shot("11-transfer-import")
            onNodeWithTag("settings:transfer-paste").performClick()
            waitUntil(timeoutMillis = 10_000L) { onAllNodesWithTag("settings:transfer-selected").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("settings:transfer-select-changed").performClick()
            shot("12-transfer-difference")
        }
    }
}
