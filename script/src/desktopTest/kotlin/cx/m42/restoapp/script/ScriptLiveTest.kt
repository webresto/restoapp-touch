package cx.m42.restoapp.script

import cx.m42.restoapp.protocol.CryptoPuzzleSolver
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.restoapp.protocol.HttpMobileApi
import cx.m42.restoapp.protocol.ScriptLoader
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The whole idea in one test (js-module.plan.md, stage 0): the server hands out the bundle of
 * «Навигация» as a link, the client downloads it and checks its hash, QuickJS runs it, and the screen
 * it draws is made of the menus the server really has — then a handler saves them back.
 *
 * The server is `tools/fake-server`: the hook's own Express application of the server checkout on the
 * fake deps of its tests. Skipped, with a line in the output, when there is no such checkout.
 */
class ScriptLiveTest {

    private val dev = File(System.getProperty("restoapp.dev") ?: "../restoapp-dev")
    private val tools = File(System.getProperty("restoapp.tools") ?: "tools")
    private var process: Process? = null
    private var runtime: ScriptRuntime? = null
    private val network = JdkNetwork()

    private fun server(): HttpMobileApi? {
        if (!File(dev, "node_modules/adminizer").isDirectory || !File(dev, "api/hooks/app-mobile-api").isDirectory) {
            println("SKIPPED: no server checkout with the app-mobile-api hook and node_modules at $dev")
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
                return HttpMobileApi(line.removePrefix("listening ").trim(), network)
            }
        }
    }

    @AfterTest
    fun stop() {
        runtime?.close()
        process?.let {
            it.destroy()
            it.waitFor(5, TimeUnit.SECONDS)
            if (it.isAlive) it.destroyForcibly()
        }
    }

    @Test
    fun theDownloadedBundleRunsAndDrawsTheMenusOfTheServer(): Unit = runBlocking {
        val api = server() ?: return@runBlocking
        val token = api.signIn()
        val declaration = api.describe(token, "ru").value
        val app = assertNotNull(declaration.app("navigation"), "«Навигация» of simple-navigation")
        val ref = assertNotNull(app.script, "the app came without a link to its code")

        // Downloaded and checked against the hash of the declaration — nothing else is ever run.
        val bundle = ScriptLoader(api).load(app.id, token, ref)
        assertEquals(ref.sha256, bundle.sha256)

        val calls = mutableListOf<String>()
        val toasts = mutableListOf<String>()
        val script = ScriptRuntime(
            server = { method, params ->
                calls += method
                api.call(token, method, params, place = null, lang = "ru").value
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            lang = "ru",
            onToast = { toasts += it },
        )
        runtime = script
        script.start(bundle.body)

        // The home screen: the menus the hook really has, drawn by the script.
        val menus = script.enter("list")
        assertEquals("Меню сайта", menus.title)
        assertEquals(listOf("navigation.menus"), calls)
        val rows = menus.tree.children.first().items
        assertEquals(listOf("Шапка", "Подвал"), rows.map { it.title })
        assertEquals(listOf("header", "footer"), rows.map { it.subtitle })

        // Opening a menu is a push the host performs: it enters the other route with the id.
        val press = assertNotNull(rows.first().press)
        assertEquals(ScriptAction.PUSH, press.kind)
        val menu = script.enter(press.route!!, press.params)
        assertEquals("Шапка", menu.title)
        val items = menu.tree.children.first().children.first().items
        assertEquals(listOf("Меню"), items.map { it.title })
        assertEquals(listOf("/menu"), items.map { it.subtitle })
        // Nothing is saved until something changes: no «Сохранить» on an untouched menu.
        assertTrue(menu.tree.children.none { it.type == "button" })

        // Switch the item off — and the button appears, because the screen became dirty.
        val toggled = script.handle(assertNotNull(items.first().onToggle), JsonPrimitive(false))
        val save = assertNotNull(toggled.tree.children.lastOrNull { it.type == "button" }, "«Сохранить» after a change")
        assertEquals("Сохранить", save.title)

        // Saving goes through the server, and the server really wrote it: ask it again.
        script.handle(assertNotNull(save.onPress))
        assertEquals(listOf("navigation.menus", "navigation.menu", "navigation.save"), calls)
        assertEquals(listOf("Сохранено"), toasts)

        val again = api.call(token, "navigation.menu", JsonObject(mapOf("id" to press.params["id"]!!)), place = null, lang = "ru").value
        val stored = again.jsonObject["item"]!!.jsonObject["items"]!!.jsonArray
        assertEquals(JsonPrimitive(false), stored.first().jsonObject["visible"], "the server kept the item switched off")
    }

    private suspend fun HttpMobileApi.signIn(login: String = "admin", password: String = "secret-password"): String {
        val task = assertNotNull(challenge(), "the hook asks for a CAPTCHA")
        return login(login, password, CryptoPuzzleSolver.solve(task) {}, DeviceInfo("Script test", "desktop")).token
    }
}
