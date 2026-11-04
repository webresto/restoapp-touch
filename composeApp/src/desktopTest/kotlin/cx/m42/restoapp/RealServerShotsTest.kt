package cx.m42.restoapp

import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.test.performMouseInput
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

    private fun control(base: String, path: String, body: String = "{}") {
        runCatching {
            java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("$base/__fake/$path"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString(),
            )
        }
    }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    private fun ComposeUiTest.exists(tag: String) = runCatching { onNodeWithTag(tag).assertExists() }.isSuccess

    private fun ComposeUiTest.hasText(text: String) = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /**
     * In the unmerged tree on purpose: a row that can be tapped merges what is inside it, and the
     * drag handle's own tag then exists only there. The pointer finds it either way.
     */
    private fun ComposeUiTest.nodesTagged(suffix: String) =
        onAllNodes(
            SemanticsMatcher("tag ends with $suffix") { it.config.getOrNull(SemanticsProperties.TestTag)?.endsWith(suffix) == true },
            useUnmergedTree = true,
        )

    @Test
    fun theScreensOfTheRealServer() {
        val address = base ?: run {
            println("SKIPPED: no -Prestoapp.serverUrl=<url> of a running tools/real-server")
            return
        }
        // The same screens every run: the menus of the module back as the stand seeded them.
        control(address, "reset-menus")

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

                // The form that adds a menu — the editor can make one, not just change one.
                if (hasText("Добавить меню")) {
                    onAllNodesWithText("Добавить меню").onFirst().performClick()
                    waitUntil(timeoutMillis = 10_000L) { hasText("Создать") }
                    shot("05-navigation-add-menu")
                    onAllNodesWithText("Отмена").onFirst().performClick()
                    waitForIdle()
                }

                if (hasText("Шапка")) {
                    onAllNodesWithText("Шапка").onFirst().performClick()
                    waitUntil(timeoutMillis = 30_000L) { exists("script:back") }
                    shot("06-navigation-items")

                    // The fields of one item: its label, the page its link points at, and its icon.
                    if (hasText("Изменить")) {
                        onAllNodesWithText("Изменить").onFirst().performClick()
                        waitUntil(timeoutMillis = 10_000L) { hasText("Подпись") }
                        shot("07-navigation-item-fields")
                        onAllNodesWithText("Готово").onFirst().performClick()
                        waitForIdle()
                    }

                    // Drag the first item below the second by its handle: the host drags, and the
                    // script is told once, on the drop.
                    val handles = nodesTagged(":drag")
                    if (handles.fetchSemanticsNodes().size > 1) {
                        val rowHeight = handles.fetchSemanticsNodes().let { nodes ->
                            (nodes[1].positionInRoot.y - nodes[0].positionInRoot.y).coerceAtLeast(1f)
                        }
                        handles.onFirst().performMouseInput {
                            moveTo(center)
                            press()
                            moveTo(center + Offset(0f, rowHeight * 0.5f))
                            moveTo(center + Offset(0f, rowHeight * 1.1f))
                            release()
                        }
                        waitUntil(timeoutMillis = 10_000L) { hasText("Сохранить") }
                        shot("08-navigation-dragged")
                    }

                    // Switch an item off, and save: both go through the methods the script named.
                    val toggles = nodesTagged(":toggle")
                    if (toggles.fetchSemanticsNodes().isNotEmpty()) {
                        toggles.onFirst().performClick()
                        waitForIdle()
                        shot("09-navigation-switched")
                    }
                    if (hasText("Сохранить")) {
                        onAllNodesWithText("Сохранить").onFirst().performClick()
                        waitUntil(timeoutMillis = 30_000L) { hasText("Сохранено") || !hasText("Сохранить") }
                        shot("10-navigation-saved")
                    }
                    // Back out and in again: what the server kept is what the screen shows now.
                    if (exists("script:back")) onNodeWithTag("script:back").performClick()
                    waitUntil(timeoutMillis = 20_000L) { hasText("Шапка") && !exists("script:back") }
                    onAllNodesWithText("Шапка").onFirst().performClick()
                    waitUntil(timeoutMillis = 30_000L) { exists("script:back") }
                    shot("11-navigation-reopened")
                    if (exists("script:back")) onNodeWithTag("script:back").performClick()
                    waitForIdle()
                }
            }

            // And the apps the universal renderer draws, from the same server.
            onNode(hasContentDescription("Открыть меню")).performClick()
            waitForIdle()
            shot("12-drawer-apps")
            onAllNodesWithText("Блюда").onFirst().performClick()
            waitUntil(timeoutMillis = 30_000L) { exists("dishes:list") }
            shot("13-dishes")

            onNode(hasContentDescription("Открыть меню")).performClick()
            waitForIdle()
            onAllNodesWithText("Клиенты").onFirst().performClick()
            waitUntil(timeoutMillis = 30_000L) { exists("clients:list") || exists("clients:empty") }
            shot("14-clients")
            println("screenshots in ${out.absolutePath}")
        }
    }
}
