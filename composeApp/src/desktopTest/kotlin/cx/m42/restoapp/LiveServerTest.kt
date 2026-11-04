package cx.m42.restoapp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
 * The whole client against the real app-mobile-api hook, through the window: connect a server by
 * address, sign in — the CAPTCHA solved by the Kotlin solver — and work in «Блюда» drawn from the
 * declaration; then the server forgets the device and the host asks to sign in again.
 *
 * By default it starts `tools/fake-server` itself: the hook's Express application of ../restoapp-dev
 * on the fake deps of its tests. With `-Drestoapp.serverUrl=http://127.0.0.1:1341` it goes to a
 * server that is already running instead — `tools/real-server`, a lifted Sails with the admin panel,
 * adminizer and core, whose users, points and dishes are the same (.ai-notes/real-server-walkthrough.md).
 *
 * Skipped, with a line in the output, when there is no ../restoapp-dev with its node_modules and no
 * server to go to.
 */
@OptIn(ExperimentalTestApi::class)
class LiveServerTest {

    private val dev = File(System.getProperty("restoapp.dev") ?: "../restoapp-dev")
    private val tools = File(System.getProperty("restoapp.tools") ?: "tools")
    private var process: Process? = null

    private fun fakeServer(): String? {
        System.getProperty("restoapp.serverUrl")?.takeIf { it.isNotBlank() }?.let {
            println("against the server already running at $it")
            return it
        }
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

    /**
     * «Каналы продаж» against the running hook: the screen exists to say **why** a channel does not
     * work and what to do next, so what is walked here is a refusal — the switch of a channel whose
     * provider is alive but not set up comes back as a sentence, not as a 409
     * (`sales-channels.plan.md`, C31). Works against either server: `tools/fake-server` has the
     * channels of the hook's own fakes, `tools/real-server` the two of the stand.
     */
    @Test
    fun theChannelsSayWhyOneDoesNotWorkAndRefuseTheSwitchInWords() {
        val base = fakeServer() ?: return
        val dir = Files.createTempDirectory("restoapp-channels").toFile()
        val host = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(dir),
            debug = true,
            device = DeviceInfo("Live test", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        // On the stand: the provider of its channel says it is not set up, and the switch is off —
        // the state a walk starts from. The fake server has no such path and answers 404; its own
        // channels are already in that state.
        runCatching { control(base, "channel-status", """{"status": "needs_setup", "message": "Не задан токен бота", "enabled": false}""") }

        runComposeUiTest {
            setContent { RestoApp(host) }
            waitForIdle()
            signIn(base, "channeler")
            waitUntil(timeoutMillis = 30_000L) { host.active.value.superizer.registry.get(AppId("channels")) != null }
            waitForIdle()

            onNodeWithTag("home:tile-channels").performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("channels:list") }

            // A channel whose provider module is not installed says so on its own row, with the appId
            // to install — the notice of the web page, in one line.
            assertTrue(
                onAllNodesWithText("Не работает: провайдер не установлен", substring = true).fetchSemanticsNodes().isNotEmpty(),
                "no channel on this server says its provider is not installed",
            )

            // The channel whose provider is here and not finished: open it and try the switch.
            onAllNodesWithText("Бот", substring = true).onFirst().performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("channels:detail") }
            onNodeWithTag("channels:channels.toggle").performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("channels:error") }
            assertTrue(
                onAllNodesWithText("Сначала настройте канал", substring = true).fetchSemanticsNodes().isNotEmpty(),
                "the refusal of the switch is not shown in words",
            )
        }

        // The same screen for somebody who may only look: every channel, and nothing to press.
        val reader = RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(Files.createTempDirectory("restoapp-channels-reader").toFile()),
            debug = true,
            device = DeviceInfo("Live reader", "desktop"),
            solver = CryptoPuzzleSolver,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

        runComposeUiTest {
            setContent { RestoApp(reader) }
            waitForIdle()
            signIn(base, "channelview")
            waitUntil(timeoutMillis = 30_000L) { reader.active.value.superizer.registry.get(AppId("channels")) != null }
            onNodeWithTag("home:tile-channels").performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("channels:list") }
            onAllNodesWithText("Бот", substring = true).onFirst().performClick()
            waitUntil(timeoutMillis = 15_000L) { exists("channels:detail") }
            onNodeWithTag("channels:channels.toggle").assertDoesNotExist()
            onNodeWithTag("channels:channels.delete").assertDoesNotExist()
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.exists(tag: String) =
        runCatching { onNodeWithTag(tag).assertExists() }.isSuccess

    /** Connect the server by its address and sign in — the CAPTCHA solved by the Kotlin solver. */
    private fun androidx.compose.ui.test.ComposeUiTest.signIn(base: String, login: String) {
        onNodeWithContentDescriptionOpenMenu()
        onNodeWithTag("context:row").performClick()
        waitForIdle()
        onNodeWithTag("context:add").performClick()
        waitForIdle()
        onNodeWithTag("servers:add-custom").performClick()
        waitForIdle()
        onNodeWithTag("servers:address").performTextInput(base)
        onNodeWithTag("servers:check").performClick()
        waitUntil(timeoutMillis = 15_000L) { exists("servers:found") }
        onNodeWithTag("servers:login").performTextInput(login)
        onNodeWithTag("servers:password").performTextInput("secret-password")
        onNodeWithTag("servers:connect").performClick()
    }

    /**
     * An app whose code comes from the server (D12): as soon as the declaration names it, the session
     * downloads the bundle by the link, checks it against the hash and keeps it in the server's own
     * storage — so the second time no request is made at all. «Навигация» of simple-navigation is the
     * app, served by `tools/fake-server` out of the hook's built bundle.
     */
    @Test
    fun theSessionDownloadsTheCodeOfAScriptAppAndKeepsIt() {
        val base = fakeServer() ?: return
        val dir = Files.createTempDirectory("restoapp-scripts").toFile()
        val store = DesktopServerStore(dir)
        val profile = ServerProfile("live", "Live", base)
        val session = ServerSession(
            profile = profile,
            api = cx.m42.restoapp.protocol.HttpMobileApi(base, cx.m42.superizer.host.net.KtorNetworkService(cx.m42.superizer.host.net.KtorNetworkService.defaultClient())),
            store = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            device = DeviceInfo("Live test", "desktop"),
            solver = CryptoPuzzleSolver,
            lang = "ru",
        )

        kotlinx.coroutines.runBlocking {
            session.signIn("admin", "secret-password") {}

            val declared = session.declaration.value ?: error("no declaration")
            val navigation = declared.apps.firstOrNull { it.id == "navigation" } ?: error("no «Навигация» on this server")
            val ref = navigation.script ?: error("«Навигация» came without a link to its code")

            // The declaration also says which modules this server is made of, and which brought an app.
            val module = declared.modules.firstOrNull { it.id == "simple-navigation" } ?: error("no module in the declaration")
            assertEquals("navigation", module.app)

            // `refresh` starts the download in the background; wait for it, as a screen would.
            val deadline = System.currentTimeMillis() + 20_000
            while (session.scripts.kept("navigation") == null && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(100)
            }
            val loaded = session.scripts.kept("navigation") ?: error("the session did not download the bundle")
            assertEquals(ref.sha256, loaded.sha256)
            assertTrue(loaded.body.contains("navigation"))

            // Kept under its hash in this server's own storage, and nowhere else.
            val kept = File(dir, "servers/live/script-${ref.sha256}.js")
            assertTrue(kept.isFile, "the bundle is kept as ${kept.name}")
            assertEquals(ref.size, kept.readBytes().size)
        }
    }
}
