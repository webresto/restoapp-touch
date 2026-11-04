package cx.m42.restoapp

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import cx.m42.restoapp.protocol.CryptoPuzzleSolver
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.superizer.app.AppId
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Not a check but a look, against the real backend: the window's screens as PNGs in
 * `composeApp/build/screenshots-real`, taken from `tools/real-server` — a lifted Sails with the admin
 * panel of adminizer 5.2, the app-mobile-api hook, core's dishes and places, and the installed module
 * `simple-navigation` whose app comes to the client as JS (js-module.plan.md).
 *
 *   cd ../restoapp-dev-jsmod && npx tsx ../restoapp-jsmod/tools/real-server/serve.ts 1341
 *   ./gradlew :composeApp:desktopTest --tests '*RealServerShotsTest' -Prestoapp.serverUrl=http://127.0.0.1:1341
 *
 * Says so and passes when there is no stand to go to.
 */
@OptIn(ExperimentalTestApi::class)
class RealServerShotsTest {

    private val base = System.getProperty("restoapp.serverUrl")?.takeIf { it.isNotBlank() }
    private val out = File("build/screenshots-real").apply { mkdirs() }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    private fun ComposeUiTest.exists(tag: String) = runCatching { onNodeWithTag(tag).assertExists() }.isSuccess

    private fun ComposeUiTest.hasText(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.nodesTagged(suffix: String) =
        onAllNodes(SemanticsMatcher("tag ends with $suffix") { it.config.getOrNull(SemanticsProperties.TestTag)?.endsWith(suffix) == true })

    @Test
    fun theScreensOfTheRealServer() {
        val address = base ?: run {
            println("SKIPPED: no -Prestoapp.serverUrl=<url> of a running tools/real-server")
            return
        }
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-real-shots").toFile()),
            debug = true,
            device = DeviceInfo("Real shots", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        runDesktopComposeUiTest(width = 420, height = 820) {
            setContent { RestoApp(host) }
            waitForIdle()

            // Connect the server by its address and sign in as the administrator.
            onNode(hasContentDescription("Открыть меню")).performClick()
            waitForIdle()
            onNodeWithTag("context:row").performClick()
            waitForIdle()
            onNodeWithTag("context:add").performClick()
            waitForIdle()
            onNodeWithTag("servers:add-custom").performClick()
            waitForIdle()
            onNodeWithTag("servers:address").performTextInput(address)
            shot("01-connect-address")
            onNodeWithTag("servers:check").performClick()
            waitUntil(timeoutMillis = 30_000L) { exists("servers:found") }
            onNodeWithTag("servers:login").performTextInput("admin")
            onNodeWithTag("servers:password").performTextInput("secret-password")
            shot("02-sign-in")
            onNodeWithTag("servers:connect").performClick()
            waitUntil(timeoutMillis = 60_000L) {
                if (exists("servers:problem")) error("the server refused the sign-in: see servers:problem")
                host.active.value.superizer.registry.get(AppId("dishes")) != null && exists("home:tile-dishes")
            }
            // Home: every app the server declared for this user, «Навигация» of the module among them.
            shot("03-home-real-server")

            // The app whose code came from the server as JS: its screen is drawn by the script.
            if (exists("home:tile-navigation")) {
                onNodeWithTag("home:tile-navigation").performClick()
                // The bundle is downloaded, run, and the menus it asked the server for are drawn.
                waitUntil(timeoutMillis = 60_000L) { hasText("Шапка") || hasText("Меню пока нет") || exists("script:error") }
                shot("04-navigation-menus")

                if (hasText("Шапка")) {
                    onAllNodesWithText("Шапка").onFirst().performClick()
                    waitUntil(timeoutMillis = 30_000L) { exists("script:back") }
                    shot("05-navigation-items")

                    // Switch an item off: the script marks the screen dirty and draws «Сохранить».
                    val toggles = nodesTagged(":toggle")
                    if (toggles.fetchSemanticsNodes().isNotEmpty()) {
                        toggles.onFirst().performClick()
                        waitUntil(timeoutMillis = 10_000L) { hasText("Сохранить") }
                        shot("06-navigation-changed")

                        // And saving goes back to the server through the method the script named.
                        onAllNodesWithText("Сохранить").onFirst().performClick()
                        waitUntil(timeoutMillis = 30_000L) { hasText("Сохранено") || !hasText("Сохранить") }
                        shot("07-navigation-saved")
                    }
                    if (exists("script:back")) onNodeWithTag("script:back").performClick()
                    waitForIdle()
                }
            }

            // And the apps the universal renderer draws, from the same server.
            onNode(hasContentDescription("Открыть меню")).performClick()
            waitForIdle()
            shot("08-drawer-apps")
            onAllNodesWithText("Блюда").onFirst().performClick()
            waitUntil(timeoutMillis = 30_000L) { exists("dishes:list") }
            shot("09-dishes")

            onNode(hasContentDescription("Открыть меню")).performClick()
            waitForIdle()
            onAllNodesWithText("Клиенты").onFirst().performClick()
            waitUntil(timeoutMillis = 30_000L) { exists("clients:list") || exists("clients:empty") }
            shot("10-clients")
            println("screenshots in ${out.absolutePath}")
        }
    }
}
