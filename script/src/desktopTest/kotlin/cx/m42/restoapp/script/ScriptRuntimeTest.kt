package cx.m42.restoapp.script

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A script app running in QuickJS (js-module.plan.md, stage 0): the script asks the server, returns
 * a screen as data, and its handlers change that screen. No window here — the tree is what the host
 * draws, and the tree is what is checked.
 */
class ScriptRuntimeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val calls = mutableListOf<Pair<String, JsonObject>>()
    private val toasts = mutableListOf<String>()
    private var runtime: ScriptRuntime? = null

    @AfterTest
    fun close() {
        runtime?.close()
    }

    private fun run(bundle: String, answers: Map<String, () -> JsonObject>): ScriptRuntime {
        val made = ScriptRuntime(
            server = { method, params ->
                calls += method to params
                answers[method]?.invoke() ?: throw IllegalStateException("no answer for $method")
            },
            scope = scope,
            lang = "ru",
            onToast = { toasts += it },
        )
        runtime = made
        runBlocking { made.start(bundle) }
        return made
    }

    private val menus = """
        app({
          id: "demo",
          strings: { ru: { menus: "Меню сайта", save: "Сохранить" } },
          routes: {
            list: {
              async enter(ctx) {
                const { items } = await ctx.server.call("navigation.menus", {});
                return { menus: items, opened: null };
              },
              title: (s, ctx) => ctx.t("menus"),
              view: (s, ctx) => ui.column([
                ui.list({
                  items: s.menus.map((menu) => ({
                    key: menu.id, title: menu.name, subtitle: menu.mnemonicId, value: String(menu.count),
                    toggle: menu.shown !== false,
                    onToggle: (shown) => ({ menus: s.menus.map((m) => m.id === menu.id ? { ...m, shown } : m) }),
                    onPress: act.push("menu", { id: menu.id }),
                  })),
                  empty: "пусто",
                }),
                s.menus.length ? ui.button({
                  title: ctx.t("save"),
                  tone: "success",
                  onPress: async () => {
                    await ctx.server.call("navigation.save", { count: s.menus.length });
                    ctx.toast("Сохранено");
                    return { opened: "saved" };
                  },
                }) : null,
              ]),
            },
          },
        });
    """.trimIndent()

    private fun twoMenus() = buildJsonObject {
        put("items", buildJsonArray {
            add(buildJsonObject { put("id", "m1"); put("name", "Шапка"); put("mnemonicId", "header"); put("count", 1) })
            add(buildJsonObject { put("id", "m2"); put("name", "Подвал"); put("mnemonicId", "footer"); put("count", 0) })
        })
    }

    @Test
    fun theScriptAsksTheServerAndGivesBackTheScreenItDrew(): Unit = runBlocking {
        val app = run(menus, mapOf("navigation.menus" to ::twoMenus))

        val screen = app.enter("list")
        assertEquals("list", screen.route)
        // `strings` of the script, in the language the host set.
        assertEquals("Меню сайта", screen.title)
        assertEquals(listOf("navigation.menus"), calls.map { it.first })

        assertEquals(listOf("list", "button"), screen.tree.children.map { it.type })
        val list = screen.tree.children.first()
        assertEquals("list", list.type)
        assertEquals(listOf("Шапка", "Подвал"), list.items.map { it.title })
        assertEquals(listOf("header", "footer"), list.items.map { it.subtitle })
        assertEquals(listOf("1", "0"), list.items.map { it.value })
        // A tap the host performs itself — no JS for a push.
        assertEquals(ScriptAction.PUSH, list.items.first().press?.kind)
        assertEquals("menu", list.items.first().press?.route)
        assertEquals(JsonPrimitive("m1"), list.items.first().press?.params?.get("id"))

        val button = screen.tree.children.last()
        assertEquals("Сохранить", button.title)
        assertEquals("success", button.tone)
    }

    @Test
    fun aHandlerChangesTheStateAndTheScreenComesBackChanged(): Unit = runBlocking {
        val app = run(menus, mapOf("navigation.menus" to ::twoMenus))
        val screen = app.enter("list")

        val toggle = screen.tree.children.first().items.first().onToggle!!
        val changed = app.handle(toggle, JsonPrimitive(false))

        val rows = changed.tree.children.first().items
        assertEquals(false, rows.first().toggle)
        assertEquals(true, rows.last().toggle)
    }

    @Test
    fun aHandlerMayCallTheServerItselfAndAskForAToast(): Unit = runBlocking {
        val app = run(menus, mapOf("navigation.menus" to ::twoMenus, "navigation.save" to { buildJsonObject { put("ok", true) } }))
        val screen = app.enter("list")

        val save = screen.tree.children.last().onPress!!
        app.handle(save)

        assertEquals(listOf("navigation.menus", "navigation.save"), calls.map { it.first })
        assertEquals(JsonPrimitive(2), calls.last().second["count"])
        assertEquals(listOf("Сохранено"), toasts)
    }

    @Test
    fun theFieldsAndTheChoiceAreScreensTooAndTheirValuesComeBackAsTheyAreTyped(): Unit = runBlocking {
        val app = run(
            """
            app({
              id: "form",
              routes: {
                add: {
                  enter: () => ({ name: "", link: "/menu" }),
                  view: (s) => ui.column([
                    ui.field({ id: "name", label: "Название", value: s.name, onChange: (name) => ({ name }) }),
                    ui.select({
                      id: "link",
                      label: "Ссылка",
                      value: s.link,
                      options: [{ value: "/menu", title: "Меню" }, { value: "/stocks", title: "Акции" }],
                      onChange: (link) => ({ link }),
                    }),
                    ui.button({ title: "Создать", tone: "success", confirm: "Точно?", enabled: s.name.length > 0 }),
                  ]),
                },
              },
            });
            """.trimIndent(),
            emptyMap(),
        )

        val empty = app.enter("add")
        val (field, select, button) = Triple(empty.tree.children[0], empty.tree.children[1], empty.tree.children[2])
        assertEquals("field", field.type)
        assertEquals("name", field.id)
        assertEquals("", field.value)
        assertEquals("select", select.type)
        assertEquals(listOf("/menu", "/stocks"), select.options.map { it.value })
        assertEquals(listOf("Меню", "Акции"), select.options.map { it.title })
        assertEquals("/menu", select.value)
        // A button may ask before it acts, and may be drawn but not pressable.
        assertEquals("Точно?", button.confirm)
        assertFalse(button.enabled)

        val typed = app.handle(field.onChange!!, JsonPrimitive("Шапка"))
        assertEquals("Шапка", typed.tree.children[0].value)
        assertTrue(typed.tree.children[2].enabled)

        val picked = app.handle(typed.tree.children[1].onChange!!, JsonPrimitive("/stocks"))
        assertEquals("/stocks", picked.tree.children[1].value)
    }

    @Test
    fun theScriptMayAskTheHostToGoBack(): Unit = runBlocking {
        val app = run(
            """
            app({
              id: "gone",
              routes: {
                menu: {
                  enter: () => ({}),
                  view: (s, ctx) => ui.button({ title: "Удалить", onPress: () => { ctx.back(); return {}; } }),
                },
              },
            });
            """.trimIndent(),
            emptyMap(),
        )
        val screen = app.enter("menu")
        assertEquals(emptyList(), screen.commands)
        val after = app.handle(screen.tree.onPress!!)
        assertEquals(listOf("back"), after.commands)
    }

    @Test
    fun aMethodTheServerRefusesIsTheScriptsProblemAndNotACrash(): Unit = runBlocking {
        val app = run(menus, emptyMap())
        val failure = assertFailsWith<ScriptException> { app.enter("list") }
        assertTrue(failure.message!!.contains("no answer for navigation.menus"), failure.message!!)
    }

    @Test
    fun anUnknownRouteAndABrokenScriptAreRefusedRatherThanDrawn(): Unit = runBlocking {
        val app = run(menus, mapOf("navigation.menus" to ::twoMenus))
        assertFailsWith<ScriptException> { app.enter("nowhere") }

        val broken = ScriptRuntime(server = { _, _ -> JsonObject(emptyMap()) }, scope = scope)
        assertFailsWith<ScriptException> { broken.start("this is not javascript ((") }
        broken.close()
    }

    @Test
    fun aRunawayScriptIsCutOffInsteadOfHangingTheApp(): Unit = runBlocking {
        val app = run(
            """
            app({ id: "spin", routes: { list: { enter: () => ({}), view: () => { while (true) {} } } } });
            """.trimIndent(),
            emptyMap(),
        )
        assertFailsWith<ScriptException> { app.enter("list") }
    }
}
