package cx.m42.restoapp

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import cx.m42.restoapp.protocol.CryptoPuzzleSolver
import cx.m42.restoapp.protocol.DeviceInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Duration.Companion.minutes

/**
 * The whole app against a live server — `tools/real-server` over a checkout of restoapp (`/prj/restoapp-places`):
 * a lifted Sails with adminizer, core, the app-mobile-api hook with «Главная» (`apps/home.ts`) and the module
 * `simple-navigation`, whose screen comes to the client as JS. Under the navigation of the prototype, for three
 * people of the server (the administrator, an operator of the orders, a stock manager) in three window classes.
 *
 *   cd /prj/restoapp-places && npx tsx ../restoapp-touch/restoapp-mobile-admin/tools/real-server/serve.ts 1341
 *   ./gradlew :composeApp:desktopTest --tests '*LiveShotsTest*' -Prestoapp.serverUrl=http://127.0.0.1:1341
 *
 * PNGs in `composeApp/build/screenshots-live/<width>-<theme>/<role>-NN-<screen>.png`. Besides the pictures it
 * checks the rule the screens stand on: what a person has no right to is neither in the menu nor on «Главная».
 * Says SKIPPED and passes without a server.
 */
@OptIn(ExperimentalTestApi::class)
class LiveShotsTest {

    private val base = System.getProperty("restoapp.serverUrl")?.takeIf { it.isNotBlank() }
    private val http = HttpClient.newHttpClient()

    @Test fun phoneLight() = all(420, 860, ThemeMode.Light)
    @Test fun phoneDark() = walk(420, 860, ThemeMode.Dark, "admin")
    @Test fun tabletLight() = all(800, 1000, ThemeMode.Light)
    @Test fun desktopLight() = all(1280, 860, ThemeMode.Light)
    @Test fun desktopDark() = walk(1280, 860, ThemeMode.Dark, "admin")

    private fun all(width: Int, height: Int, theme: ThemeMode) {
        for (login in listOf("admin", "operator", "stock")) walk(width, height, theme, login)
    }

    private fun post(path: String, body: String = "{}") {
        runCatching {
            http.send(
                HttpRequest.newBuilder(URI.create("$base/__fake/$path")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
    }

    private fun walk(width: Int, height: Int, theme: ThemeMode, login: String) {
        val address = base ?: run {
            println("SKIPPED: no -Prestoapp.serverUrl=<url> of a running tools/real-server")
            return
        }
        // Ten sign-ins a minute per address: a walk of three widths makes more.
        post("clear-attempts")
        post("reset-menus")
        // A day of a pizzeria: orders, clients, promo codes — the screens with something on them (idempotent).
        post("demo")
        val out = File("build/screenshots-live/$width-${theme.name.lowercase()}").apply { mkdirs() }
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-live-shots").toFile()),
            debug = true,
            device = DeviceInfo("Live shots", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also {
            it.active.value.superizer.settings.chooseLanguage("ru")
            it.active.value.superizer.settings.chooseTheme(theme)
        }

        runDesktopComposeUiTest(width = width, height = height, testTimeout = 10.minutes) {
            var n = 0
            fun shot(name: String) {
                waitForIdle()
                n += 1
                val image = (this as? androidx.compose.ui.test.SkikoComposeUiTest)?.captureToImage() ?: captureToImage()
                ImageIO.write(image.toAwtImage(), "png", File(out, "$login-${n.toString().padStart(2, '0')}-$name.png"))
            }
            setContent { RestoApp(host) }
            waitForIdle()
            try {

            // Connect the server by its address, as a person does: the context line, «Добавить», the address.
            openContexts()
            if (login == "admin" && width == 420 && theme == ThemeMode.Light) shot("contexts-demo")
            onNodeWithTag("context:add").performClick()
            waitForIdle()
            onNodeWithTag("servers:add-custom").performClick()
            waitForIdle()
            onNodeWithTag("servers:address").performTextInput(address)
            onNodeWithTag("servers:check").performClick()
            waitUntil(timeoutMillis = 30_000L) { exists("servers:found") }
            onNodeWithTag("servers:login").performTextInput(login)
            onNodeWithTag("servers:password").performTextInput("secret-password")
            if (login == "admin") shot("connect")
            onNodeWithTag("servers:connect").performClick()
            waitUntil(timeoutMillis = 60_000L) {
                if (exists("servers:problem")) error("the server refused the sign-in of $login")
                exists("home:summary")
            }

            // «Главная»: the blocks of the day this person may see, from the server.
            when (login) {
                "admin" -> {
                    waitUntil(timeoutMillis = 30_000L) { exists("home:stat-revenue") && exists("home:stat-stop") }
                    assertTrue(exists("home:stat-orders") && exists("home:stat-check"))
                }
                "operator" -> {
                    waitUntil(timeoutMillis = 30_000L) { exists("home:stat-orders") }
                    assertFalse(exists("home:stat-revenue") || exists("home:stat-check") || exists("home:stat-stop"))
                }
                "stock" -> {
                    waitUntil(timeoutMillis = 30_000L) { exists("home:stat-stop") }
                    assertFalse(exists("home:stat-revenue") || exists("home:stat-orders"))
                }
            }
            shot("home")

            if (exists("shell:nav-more")) {
                onNodeWithTag("shell:nav-more").performClick()
                waitUntil(timeoutMillis = 10_000L) { exists("more:root") }
                shot("more")
                openHome()
            }
            if (login == "admin") {
                openContexts()
                waitForIdle()
                shot("contexts")
                // Closed the way a person closes it: the same line again, or a tap outside.
                runCatching { onNodeWithTag("context:row").performClick() }
                waitForIdle()
                openHome()
            }

            val apps = when (login) {
                "admin" -> listOf("orders", "pos", "dishes", "clients", "marketing", "channels", "system", "navigation", "settings")
                "operator" -> listOf("orders")
                else -> listOf("dishes")
            }
            // The rights: what is not the person's is not in the navigation at all.
            if (login != "admin") {
                listOf("pos", "marketing", "settings", "system", "navigation").forEach { assertFalse(hasApp(it), "$login sees $it") }
                if (login == "operator") assertFalse(hasApp("dishes")) else assertFalse(hasApp("orders"))
            }

            for (app in apps) {
                openApp(app)
                settle(app)
                shot(app)
                when (app) {
                    "navigation" -> navigation(::shot)
                    "pos" -> Unit
                    else -> if (openFirstRow(app)) {
                        settle(app)
                        shot("$app-card")
                        runCatching { shellBack() }
                    }
                }
                openHome()
            }
            } catch (failure: Throwable) {
                runCatching { shot("FAILED") }
                throw failure
            }
        }
    }

    /** The script app: the menus the bundle asked the server for, and the editor of one of them. */
    private fun ComposeUiTest.navigation(shot: (String) -> Unit) {
        waitUntil(timeoutMillis = 60_000L) { hasText("Шапка") || hasText("Меню пока нет") || exists("script:error") }
        shot("navigation-menus")
        if (hasText("Шапка")) {
            onAllNodesWithText("Шапка").onFirst().performClick()
            waitUntil(timeoutMillis = 30_000L) { exists("script:nested") }
            shot("navigation-menu")
            if (hasText("Изменить")) {
                onAllNodesWithText("Изменить").onFirst().performClick()
                waitUntil(timeoutMillis = 10_000L) { hasText("Подпись") }
                shot("navigation-item")
                onAllNodesWithText("Готово").onFirst().performClick()
                waitForIdle()
            }
            if (exists("script:nested")) runCatching { shellBack() }
        }
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.hasText(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Until the app has drawn what the server answered: a list, a card, an empty state or its own screen. */
    private fun ComposeUiTest.settle(app: String) {
        runCatching {
            waitUntil(timeoutMillis = 30_000L) {
                listOf(":list", ":detail", ":empty", ":board", ":nested").any { exists("$app$it") } ||
                    rows(app).isNotEmpty() || (app == "pos" && onAllNodes(tagPrefix("pos:product-")).fetchSemanticsNodes().isNotEmpty())
            }
        }
        // A list asks for its badge and its counts after its rows: let them come too.
        val until = System.currentTimeMillis() + 800
        while (System.currentTimeMillis() < until) waitForIdle()
    }

    private fun tagPrefix(prefix: String) =
        SemanticsMatcher("tag starts with $prefix") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }

    private fun ComposeUiTest.rows(app: String) =
        onAllNodes(
            SemanticsMatcher("a row of $app") {
                val tag = it.config.getOrNull(SemanticsProperties.TestTag) ?: return@SemanticsMatcher false
                tag.startsWith("$app:row-") && tag.count { c -> c == ':' } == 1
            },
        ).fetchSemanticsNodes()

    /** Opens the first row of the list, when it has one. */
    private fun ComposeUiTest.openFirstRow(app: String): Boolean {
        val tag = rows(app).firstOrNull()?.config?.getOrNull(SemanticsProperties.TestTag) ?: return false
        return runCatching {
            onNodeWithTag(tag).performScrollTo().performClick()
            waitUntil(timeoutMillis = 20_000L) { exists("$app:detail") }
        }.isSuccess
    }
}
