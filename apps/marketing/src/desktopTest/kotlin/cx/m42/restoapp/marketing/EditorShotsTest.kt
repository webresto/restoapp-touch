package cx.m42.restoapp.marketing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.demo.DemoRole
import cx.m42.restoapp.demo.DemoStore
import cx.m42.restoapp.protocol.Place
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
import kotlinx.serialization.json.JsonPrimitive

/**
 * Not a check but a look (05-native-apps.md §5.2): the editor of a promotion in the three window classes
 * — 420, 800, 1280 — light and dark, as PNGs in `apps/marketing/build/screenshots-native/<width>-<theme>/`.
 *
 * The editor is composed by itself, in the host's theme and window class: in the host it stands inside the
 * renderer's app, whose width is the renderer's business (stage 3), and here only its own look is judged.
 */
@OptIn(ExperimentalTestApi::class)
class EditorShotsTest {

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

    private fun shots(width: Int, theme: ThemeMode) = runDesktopComposeUiTest(width = width, height = 900) {
        val out = File("build/screenshots-native/$width-${theme.name.lowercase()}").apply { mkdirs() }
        fun shot(name: String) {
            waitForIdle()
            ImageIO.write(captureToImage().toAwtImage(), "png", File(out, "$name.png"))
        }
        val backend = DemoBackend(MemoryStore(), listOf(Place("p1", "Ленина, 5", "ул. Ленина, 5")), clock = { 1_700_000_000_000 }, serverName = "Демо")
            .also { runBlocking { it.setRole(DemoRole.Admin) } }
        val server = EditorServer(backend)
        val record = runBlocking {
            recordOf(backend.call("demo", "marketing.promotion", jsonObjectOf("id" to JsonPrimitive("pr-lunch")), null, "ru").value)
        }!!
        val scope = NativeActionScope(
            record = record,
            method = server.app.value!!.method("marketing.savePromotion")!!,
            facts = server.facts.value,
            lang = "ru",
            busy = false,
            failure = null,
            call = { method, params -> server.call(method, params) },
            submit = {},
            submitThen = { _, _ -> },
            notice = {},
            close = {},
        )
        setContent {
            HostTheme(theme, KitAccent.Violet, kitTypography()) {
                Box(Modifier.fillMaxSize().background(Kit.palette.canvas)) {
                    WindowClassProvider { PromotionEditor(scope) }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("marketing:editor").assertExists() }.isSuccess }
        mainClock.advanceTimeBy(500)
        shot("01-promotion-editor")

        onNodeWithTag("marketing:editor-name").performTextReplacement("Бизнес-ланч до 16:00")
        shot("02-promotion-editor-unsaved")

        onNodeWithTag("marketing:editor-day-monday").performScrollTo().performClick()
        onNodeWithTag("marketing:editor-day-friday").performClick()
        onNodeWithTag("marketing:editor-service-delivery").performClick()
        onNodeWithTag("marketing:editor-sort-order").performScrollTo()
        shot("03-promotion-conditions")

        onNodeWithTag("marketing:editor-dishes").performScrollTo().performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("marketing:picker-open-Пицца").assertExists() }.isSuccess }
        onNodeWithTag("marketing:picker-open-Пицца").performClick()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("marketing:picker-dish-d1").assertExists() }.isSuccess }
        onNodeWithTag("marketing:picker-dish-d1").performClick()
        mainClock.advanceTimeBy(500)
        shot("04-promotion-picker")
    }
}
