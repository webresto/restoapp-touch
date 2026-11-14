package cx.m42.restoapp

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.restoapp.assistant.AssistantApp
import cx.m42.restoapp.protocol.CryptoPuzzleSolver
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.superizer.app.AppId
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * «Ассистент» through the window, in two contexts and one chat: the demo server (its canned assistant), then — with
 * `-Pcloud.url` — a Resto Cloud account, whose assistant is the AI panel's of the cloud console. Which one answers
 * is the context's: the app is the same class, `assistant.session` of the context says where to go.
 *
 *   ./gradlew :composeApp:desktopTest --rerun --tests '*AssistantWalkTest*' \
 *     -Pcloud.url=http://127.0.0.1:17320 -Pcloud.login=user1 -Pcloud.password=user1
 *
 * The cloud panel needs its assistant on (CLOUD_AI_*; without a key — `node tools/fake-llm/serve.mjs`). Screenshots —
 * `composeApp/build/screenshots-assistant`.
 */
@OptIn(ExperimentalTestApi::class)
class AssistantWalkTest {

    private val url = System.getProperty("cloud.url")?.takeIf { it.isNotBlank() }?.trimEnd('/')
    private val login = System.getProperty("cloud.login") ?: "user1"
    private val password = System.getProperty("cloud.password") ?: login
    private val out = File("build/screenshots-assistant").apply { mkdirs() }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    private fun ComposeUiTest.exists(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.hasText(text: String) = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.openMenu() {
        onNode(hasContentDescription("Открыть меню")).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openAssistant() {
        // From another app: «Главная» of the drawer; on Home already the drawer is not opened at all.
        if (!exists("home:tile-assistant")) {
            openMenu()
            onAllNodesWithText("Главная").onFirst().performClick()
        }
        waitUntil(timeoutMillis = 10_000L) { exists("home:tile-assistant") }
        // The last tile of a long Home: scrolled to «Добавить» after it, so the whole tile is on screen.
        onNodeWithTag("home:add").performScrollTo()
        waitForIdle()
        onNodeWithTag("home:tile-assistant").performClick()
        waitUntil(timeoutMillis = 20_000L) { exists("assistant:welcome") || exists("assistant:messages") || exists("assistant:setup") }
    }

    private fun ComposeUiTest.ask(text: String, answer: String) {
        waitUntil(timeoutMillis = 15_000L) { !exists("assistant:busy") }
        onNodeWithTag("assistant:input").performTextInput(text)
        waitForIdle()
        onNodeWithTag("assistant:send").performClick()
        waitUntil(timeoutMillis = 60_000L) { hasText(answer) && exists("assistant:send") }
    }

    private fun host(dir: File, cloud: String? = null) = RestoAppHost(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        store = DesktopServerStore(dir),
        debug = true,
        device = DeviceInfo("Assistant test", "desktop"),
        solver = CryptoPuzzleSolver,
        cloudAddress = cloud ?: ServerProfile.CLOUD_ADDRESS,
        clock = { 1_700_000_000_000 },
    ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

    @Test
    fun theDemoServerHasItsAssistant() = runComposeUiTest {
        val host = host(Files.createTempDirectory("restoapp-assistant").toFile())
        setContent { RestoApp(host) }
        waitUntil(timeoutMillis = 10_000L) { host.active.value.superizer.registry.get(AppId("assistant")) != null }
        assertTrue(host.active.value.superizer.registry.get(AppId("assistant")) is AssistantApp)
        openAssistant()
        shot("01-demo-welcome")
        ask("Какие у тебя инструменты?", "Демо:")
        assertTrue(exists("assistant:tool-list_tools"))
        shot("02-demo-reply")
    }

    @Test
    fun aCloudAccountTalksToTheAssistantOfTheConsole() {
        val base = url ?: run {
            println("SKIPPED: no -Pcloud.url=<a cloud panel with its assistant on>")
            return
        }
        val host = host(Files.createTempDirectory("restoapp-assistant-cloud").toFile(), base)
        runComposeUiTest {
            setContent { RestoApp(host) }
            waitForIdle()
            openMenu()
            onNodeWithTag("context:row").performClick()
            waitForIdle()
            onNodeWithTag("context:add").performClick()
            waitForIdle()
            onNodeWithTag("servers:add-cloud").performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("servers:login") }
            onNodeWithTag("servers:login").performTextInput(login)
            onNodeWithTag("servers:password").performTextInput(password)
            onNodeWithTag("servers:connect").performClick()
            waitUntil(timeoutMillis = 60_000L) {
                host.current.value?.title?.endsWith("· $login") == true && host.active.value.superizer.registry.get(AppId("assistant")) != null
            }
            assertTrue(host.active.value.superizer.registry.get(AppId("assistant")) is AssistantApp)
            openAssistant()
            assertTrue(exists("assistant:welcome") || exists("assistant:messages"), "the cloud's assistant is not ready")
            shot("03-cloud-open")
            // A fresh conversation, so the walk does not depend on what the account asked before.
            onNodeWithTag("assistant:new").performClick()
            // The new conversation exists on the server before anything is sent to it.
            waitUntil(timeoutMillis = 15_000L) { exists("assistant:welcome") && !exists("assistant:busy") }
            shot("04-cloud-welcome")
            val question = "Привет из приложения ${System.currentTimeMillis() % 100000}"
            onNodeWithTag("assistant:input").performTextInput(question)
            waitForIdle()
            onNodeWithTag("assistant:send").performClick()
            waitUntil(timeoutMillis = 60_000L) { exists("assistant:reply") && exists("assistant:send") }
            shot("05-cloud-reply")
            // The person's own message stays on screen with the reply (the cloud's history writes `User`).
            assertTrue(hasText(question), "the question is not on screen")
            onNodeWithTag("assistant:conversations").performClick()
            waitUntil(timeoutMillis = 15_000L) { hasText(question) }
            shot("06-cloud-conversations")
        }
    }
}
