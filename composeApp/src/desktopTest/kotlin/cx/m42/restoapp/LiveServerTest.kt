package cx.m42.restoapp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.restoapp.protocol.CryptoPuzzleSolver
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.restoapp.render.ServerStatus
import cx.m42.superizer.app.AppId
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The whole client against the real app-mobile-api hook (`tools/fake-server`: the hook's Express
 * application of ../restoapp-dev on the fake deps of its tests), through the window: connect a
 * server by address, sign in — the CAPTCHA solved by the Kotlin solver — and work in «Блюда» drawn
 * from the declaration; then the server forgets the device and the host asks to sign in again.
 *
 * Skipped, with a line in the output, when there is no ../restoapp-dev with its node_modules.
 */
@OptIn(ExperimentalTestApi::class)
class LiveServerTest {

    private val dev = File(System.getProperty("restoapp.dev") ?: "../restoapp-dev")
    private val tools = File(System.getProperty("restoapp.tools") ?: "tools")
    private var process: Process? = null

    private fun fakeServer(): String? {
        if (!File(dev, "node_modules/adminizer").isDirectory || !File(dev, "api/hooks/app-mobile-api").isDirectory) {
            println("SKIPPED: no ../restoapp-dev with the app-mobile-api hook and node_modules at $dev")
            return null
        }
        val started = ProcessBuilder("npx", "tsx", File(tools, "fake-server/serve.ts").absolutePath, "0")
            .directory(dev).redirectErrorStream(true).start()
        process = started
        val reader = started.inputStream.bufferedReader()
        while (true) {
            val line = reader.readLine() ?: error("The fake server did not start")
            if (line.startsWith("listening ")) {
                Thread { reader.forEachLine { } }.apply { isDaemon = true }.start()
                return line.removePrefix("listening ").trim()
            }
        }
    }

    @AfterTest
    fun stop() {
        process?.let {
            it.destroy()
            it.waitFor(5, TimeUnit.SECONDS)
            if (it.isAlive) it.destroyForcibly()
        }
    }

    private fun control(base: String, path: String, body: String) {
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$base/__fake/$path")).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    @Test
    fun connectSignInWorkAndSignInAgainAfterThePasswordChanged() {
        val base = fakeServer() ?: return
        val dir = Files.createTempDirectory("restoapp-live").toFile()
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(dir),
            debug = true,
            device = DeviceInfo("Live test", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        runComposeUiTest {
            setContent { RestoApp(host) }
            waitForIdle()
            // "Подключить сервер" → «Свой сервер» → the address → server.about.
            onNodeWithContentDescriptionOpenMenu()
            onNodeWithTag("context:row").performClick()
            waitForIdle()
            onNodeWithTag("context:add").performClick()
            waitForIdle()
            onNodeWithTag("servers:add-custom").performClick()
            waitForIdle()
            onNodeWithTag("servers:address").performTextInput(base)
            onNodeWithTag("servers:check").performClick()
            waitUntil(timeoutMillis = 15_000L) { runCatching { onNodeWithTag("servers:found").assertExists() }.isSuccess }

            // The admin panel's login; the CAPTCHA is solved by the Kotlin port of crypto-puzzle.
            onNodeWithTag("servers:login").performTextInput("stock")
            onNodeWithTag("servers:password").performTextInput("secret-password")
            onNodeWithTag("servers:connect").performClick()
            val id = slug(base)
            waitUntil(timeoutMillis = 30_000L) { host.current.value?.id == id && host.active.value.superizer.registry.get(AppId("dishes")) != null }
            waitForIdle()

            val session = File(dir, "servers/$id/session.json").toPath()
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(session)))
            assertTrue(Files.readString(session).contains("\"login\":\"stock\""))

            // «Блюда», drawn from the declaration: the three dishes of the fake catalog at «Ленина, 5».
            onNodeWithTag("home:tile-dishes").performClick()
            waitUntil(timeoutMillis = 15_000L) { runCatching { onNodeWithTag("dishes:list").assertExists() }.isSuccess }
            onNodeWithTag("dishes:chip-all").performClick()
            waitUntil(timeoutMillis = 15_000L) { runCatching { onNodeWithTag("dishes:row-d3").assertExists() }.isSuccess }
            onNodeWithTag("dishes:place").assertExists()

            // The server forgets the device: the next request says so, and Home asks to sign in.
            control(base, "password", """{"login":"stock"}""")
            host.active.value.session.refreshInBackground()
            waitUntil(timeoutMillis = 15_000L) { host.active.value.session.status.value == ServerStatus.SignedOut }
            assertTrue(!File(dir, "servers/$id/session.json").exists())
        }

        // Back on Home: the banner, the login screen, the new password.
        runComposeUiTest {
            setContent { RestoApp(host) }
            waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("home:sign-in").assertExists() }.isSuccess }
            onNodeWithTag("home:sign-in:action").performClick()
            waitForIdle()
            onNodeWithTag("login:password").performTextInput("changed")
            onNodeWithTag("login:submit").performClick()
            waitUntil(timeoutMillis = 30_000L) { host.active.value.session.status.value == ServerStatus.Online }
            waitForIdle()
            onNodeWithTag("home:sign-in").assertDoesNotExist()
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onNodeWithContentDescriptionOpenMenu() {
        onNode(androidx.compose.ui.test.hasContentDescription("Открыть меню")).performClick()
        waitForIdle()
    }
}
