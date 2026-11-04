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
import kotlin.test.assertNull
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
 * The whole idea in one test (js-module.plan.md, stage 0): the server hands out the bundle of an app
 * as a link, the client downloads it and checks its hash, QuickJS runs it, and the screen it draws is
 * made of what the server really has — then a handler saves it back.
 *
 * The first test is that, on the module made for tests (`tools/fake-server/test-module`), which is
 * there on any checkout. The other two walk the real editor of «Навигация» and need its module in
 * `modules/simple-navigation` of the server: without it they say SKIPPED.
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
    fun theBundleOfAModuleIsDownloadedRunAndTalksToTheServer(): Unit = runBlocking {
        val api = server() ?: return@runBlocking
        val token = api.signIn()
        val declaration = api.describe(token, "ru").value
        val app = assertNotNull(declaration.app("notes"), "«Заметки» of the test module")
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

        // The home screen: what the hook really has, drawn by the script.
        val list = script.enter("list")
        assertEquals("Заметки", list.title)
        assertEquals(listOf("notes.list"), calls)
        val rows = list.tree.firstOfType("list").items
        assertEquals(listOf("Первая", "Вторая"), rows.map { it.title })

        // Opening one is a push the host performs: it enters the other route with the id.
        val press = assertNotNull(rows.first().press)
        assertEquals(ScriptAction.PUSH, press.kind)
        var note = script.enter(press.route!!, press.params)
        assertEquals("Первая", note.title)
        assertEquals("Первая", note.tree.field("text").value)
        assertNull(note.tree.button("Сохранить"), "nothing to save on an untouched note")

        // Typing makes the screen dirty, saving goes through the server, and the server really wrote it.
        note = script.handle(assertNotNull(note.tree.field("text").onChange), JsonPrimitive("Изменённая"))
        script.handle(assertNotNull(note.tree.button("Сохранить")?.onPress))
        assertEquals(listOf("notes.list", "notes.note", "notes.save"), calls)
        assertEquals(listOf("Сохранено"), toasts)
        val stored = api.call(token, "notes.note", JsonObject(mapOf("id" to press.params["id"]!!)), place = null, lang = "ru").value
        assertEquals(JsonPrimitive("Изменённая"), stored.jsonObject["item"]!!.jsonObject["text"])

        // Deleted: the script asks the host to go back, and the list has one fewer.
        note = script.enter(press.route!!, press.params)
        val removed = script.handle(assertNotNull(note.tree.button("Удалить")?.onPress))
        assertEquals(listOf("back"), removed.commands)
        assertEquals(listOf("Вторая"), script.enter("list").tree.firstOfType("list").items.map { it.title })
    }

    /** «Навигация», when its module is in this server; the walks above stand without it. */
    private fun app(declaration: cx.m42.restoapp.protocol.Declaration): cx.m42.restoapp.protocol.DeclaredApp? =
        declaration.app("navigation").also {
            if (it == null) println("SKIPPED: no modules/simple-navigation at $dev, so no «Навигация» to walk")
        }

    @Test
    fun theDownloadedBundleRunsAndDrawsTheMenusOfTheServer(): Unit = runBlocking {
        val api = server() ?: return@runBlocking
        val token = api.signIn()
        val declaration = api.describe(token, "ru").value
        val app = app(declaration) ?: return@runBlocking
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
        val rows = menus.tree.firstOfType("list").items
        assertEquals(listOf("Шапка", "Подвал"), rows.map { it.title })
        assertEquals(listOf("header", "footer"), rows.map { it.subtitle })

        // Opening a menu is a push the host performs: it enters the other route with the id.
        val press = assertNotNull(rows.first().press)
        assertEquals(ScriptAction.PUSH, press.kind)
        val menu = script.enter(press.route!!, press.params)
        assertEquals("Шапка", menu.title)
        val items = menu.tree.firstOfType("list").items
        assertEquals(listOf("Меню"), items.map { it.title })
        assertEquals(listOf("/menu"), items.map { it.subtitle })
        // Nothing is saved until something changes: no «Сохранить» on an untouched menu, though the
        // buttons that add an item and delete the menu are always there.
        assertTrue(menu.tree.children.none { it.title == "Сохранить" })
        assertEquals(listOf("Добавить пункт", "Удалить меню"), menu.tree.children.filter { it.type == "button" }.map { it.title })
        // The name of the menu is a field, so it can be renamed right here.
        assertEquals("Шапка", menu.tree.firstOfType("field").value)

        // Switch the item off — and the button appears, because the screen became dirty.
        val toggled = script.handle(assertNotNull(items.first().onToggle), JsonPrimitive(false))
        val save = assertNotNull(toggled.tree.children.firstOrNull { it.title == "Сохранить" }, "«Сохранить» after a change")
        assertEquals("Сохранить", save.title)

        // Saving goes through the server, and the server really wrote it: ask it again.
        script.handle(assertNotNull(save.onPress))
        assertEquals(listOf("navigation.menus", "navigation.menu", "navigation.save"), calls)
        assertEquals(listOf("Сохранено"), toasts)

        val again = api.call(token, "navigation.menu", JsonObject(mapOf("id" to press.params["id"]!!)), place = null, lang = "ru").value
        val stored = again.jsonObject["item"]!!.jsonObject["items"]!!.jsonArray
        assertEquals(JsonPrimitive(false), stored.first().jsonObject["visible"], "the server kept the item switched off")
    }

    /** The first node of this type anywhere in the tree: the screens nest, the assertions need not. */
    private fun ScriptNode.firstOfType(type: String): ScriptNode =
        if (this.type == type) this else children.firstNotNullOf { child -> runCatching { child.firstOfType(type) }.getOrNull() }

    /** A button by the words on it, anywhere in the tree. */
    private fun ScriptNode.button(title: String): ScriptNode? =
        if (type == "button" && this.title == title) this else children.firstNotNullOfOrNull { it.button(title) }

    /** A field by the id the script gave it. */
    private fun ScriptNode.field(id: String): ScriptNode =
        requireNotNull(fieldOrNull(id)) { "no field $id on the screen" }

    private fun ScriptNode.fieldOrNull(id: String): ScriptNode? =
        if (type == "field" && this.id == id) this else children.firstNotNullOfOrNull { it.fieldOrNull(id) }

    private suspend fun HttpMobileApi.signIn(login: String = "admin", password: String = "secret-password"): String {
        val task = assertNotNull(challenge(), "the hook asks for a CAPTCHA")
        return login(login, password, CryptoPuzzleSolver.solve(task) {}, DeviceInfo("Script test", "desktop")).token
    }


    /**
     * Everything the editor can do, against the real hook: a menu is created, named, filled with an
     * item whose label and link are typed and picked, saved, read back — and then deleted, and the
     * list no longer has it. Nothing of this is in the client: it is all the script the server sent.
     */
    @Test
    fun aMenuIsMadeFilledSavedAndDeletedThroughTheScript(): Unit = runBlocking {
        val api = server() ?: return@runBlocking
        val token = api.signIn()
        val declaration = api.describe(token, "ru").value
        val app = app(declaration) ?: return@runBlocking
        val bundle = ScriptLoader(api).load(app.id, token, assertNotNull(app.script))

        val script = ScriptRuntime(
            server = { method, params ->
                try {
                    api.call(token, method, params, place = null, lang = "ru").value
                } catch (failure: Throwable) {
                    throw IllegalStateException("$method $params: ${failure.message}", failure)
                }
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            lang = "ru",
        )
        runtime = script
        script.start(bundle.body)

        // 1. The form that adds a menu, its two fields typed into, and the menu created.
        var list = script.enter("list")
        val before = list.tree.firstOfType("list").items.size
        list = script.handle(assertNotNull(list.tree.button("Добавить меню")?.onPress))
        val name = list.tree.field("name")
        val code = list.tree.field("code")
        // Nothing to create from an empty form.
        assertEquals(false, assertNotNull(list.tree.button("Создать")).enabled)
        list = script.handle(assertNotNull(name.onChange), JsonPrimitive("Боковое"))
        list = script.handle(assertNotNull(list.tree.field("code").onChange), JsonPrimitive("aside-" + (System.currentTimeMillis() % 100000)))
        assertEquals(true, assertNotNull(list.tree.button("Создать")).enabled)
        list = script.handle(assertNotNull(list.tree.button("Создать")?.onPress))
        assertEquals(before + 1, list.tree.firstOfType("list").items.size)

        // 2. Open it: empty, and the name is a field.
        val row = assertNotNull(list.tree.firstOfType("list").items.firstOrNull { it.title == "Боковое" })
        val push = assertNotNull(row.press)
        var menu = script.enter(assertNotNull(push.route), push.params)
        assertEquals("Боковое", menu.tree.field("name").value)
        assertEquals(0, menu.tree.firstOfType("list").items.size)

        // 3. An item added, its label typed and its link picked from the pages the site has.
        menu = script.handle(assertNotNull(menu.tree.button("Добавить пункт")?.onPress))
        menu = script.handle(assertNotNull(menu.tree.field("label-0").onChange), JsonPrimitive("Контакты"))
        val pages = menu.tree.firstOfType("select")
        assertTrue(pages.options.any { it.value == "/stocks" }, "the pages of the site are offered")
        menu = script.handle(assertNotNull(pages.onChange), JsonPrimitive("/stocks"))
        menu = script.handle(assertNotNull(menu.tree.field("icon-0").onChange), JsonPrimitive("star"))
        menu = script.handle(assertNotNull(menu.tree.field("name").onChange), JsonPrimitive("Боковое меню"))
        menu = script.handle(assertNotNull(menu.tree.button("Сохранить")?.onPress))

        // 4. Read back from the server: the name, the item, its link and its icon are all there.
        val saved = api.call(token, "navigation.menu", JsonObject(mapOf("id" to push.params["id"]!!)), place = null, lang = "ru").value
        val stored = saved.jsonObject["item"]!!.jsonObject
        assertEquals(JsonPrimitive("Боковое меню"), stored["name"])
        val item = stored["items"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("Контакты"), item["label"])
        assertEquals(JsonPrimitive("/stocks"), item["link"])
        assertEquals(JsonPrimitive("star"), item["icon"])

        // 5. Deleted: the script asks the host to go back, and the list has one menu fewer.
        menu = script.enter(assertNotNull(push.route), push.params)
        val removed = script.handle(assertNotNull(menu.tree.button("Удалить меню")?.onPress))
        assertEquals(listOf("back"), removed.commands)
        val after = script.enter("list")
        assertEquals(before, after.tree.firstOfType("list").items.size)
        assertTrue(after.tree.firstOfType("list").items.none { it.title?.startsWith("Боковое") == true })
    }
}
