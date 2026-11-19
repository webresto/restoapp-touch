@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.render

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.demo.DemoRole
import cx.m42.restoapp.protocol.Declaration
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.ListPage
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.restoapp.protocol.id
import cx.m42.restoapp.protocol.jsonObjectOf
import cx.m42.restoapp.protocol.recordOf
import cx.m42.superizer.testing.AppContractTest
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val dishesDeclared: DeclaredApp = runBlocking { DemoServer.demo().describe("demo", "ru").value.app("dishes")!! }

/** Everything the host assumes about a server app, in one line — on «Блюда» of the demo. */
class ServerAppContractTest : AppContractTest(ServerApp(dishesDeclared, "ru")) {
    override fun provideServices(runtime: cx.m42.superizer.testing.FakeAppRuntime) {
        runtime.provide(RestoServer.Key, DemoServer(DemoServer.demo(), "dishes"))
    }

    override val configFixtures: List<String> = listOf("""{"schemaVersion":1}""", "{}")
    override val stateFixtures: List<String> = listOf(
        // The stack, as this build writes it.
        """{"place":"p2","frames":[{"method":"dishes.list","chip":"all","query":"пицца"},{"method":"dishes.get","params":{"id":"d1"}}]}""",
        """{"frames":[{"method":"dishes.list","chip":"stop"}]}""",
        // And the shape of the build before the stack: a chip, a search and the one open record.
        """{"place":"p2","chip":"all","query":"пицца","open":"d1"}""",
        """{"chip":"stop"}""",
        "{}",
    )
}

/** The renderer on the demo server: no screen of «Блюда» or «Заказы» exists in the client. */
class ServerAppTest {

    private val AppTestScope<*>.server get() = runtime.service(RestoServer.Key) as DemoServer
    private val AppTestScope<*>.screen get() = instance as ServerAppInstance

    /** Scrolls to [tag] when it sits in a scrolling column, then clicks it. */
    private fun AppTestScope<*>.tap(tag: String) {
        runCatching { node(tag).performScrollTo() }
        click(tag)
    }

    /** The app's coroutines run on the runtime's test scheduler: a debounce waits for it, not for the clock of the UI. */
    private fun AppTestScope<*>.advance(millis: Long) {
        val scheduler = (runtime.scope as kotlinx.coroutines.test.TestScope).testScheduler
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
        idle()
    }

    /** A value inside a clickable row lives only in the unmerged tree. */
    private fun AppTestScope<*>.inner(tag: String) = ui.onNodeWithTag(tag, useUnmergedTree = true)

    private fun run(
        appId: String = "dishes",
        backend: DemoBackend = DemoServer.demo(),
        restore: JsonObject? = null,
        block: AppTestScope<*>.() -> Unit,
    ) {
        val server = DemoServer(backend, appId)
        runAppTest(ServerApp(server.app.value!!, "ru"), restore = restore, prepare = { it.provide(RestoServer.Key, server) }) {
            idle()
            block()
        }
    }

    private fun AppTestScope<*>.page(filter: String, place: String = server.place.value!!.id): ListPage = runBlocking {
        ListPage.of(server.api.call("demo", "dishes.list", jsonObjectOf("filter" to JsonPrimitive(filter)), place, "ru").value)
    }

    @Test
    fun theListShowsTheDishesWithALimitAtTheFirstPointWithCountsOnTheChips() = run {
        val limited = page("limited")
        assertEquals("p1", server.place.value?.id)
        assertEquals(listOf("dishes.list" to "p1"), server.calls.take(1))
        limited.items.forEach { node("dishes:row-${it.id()}").assertExists() }
        node("dishes:chip-limited").assertIsSelected()
        ui.onNodeWithText(limited.counts.getValue("all").toString(), useUnmergedTree = true).assertExists()
        assertEquals("Ленина, 5", screen.chrome.value.subtitle)
    }

    @Test
    fun minusToZeroPutsADishOnStopAndPlusTakesItOff() = run {
        val counted = page("limited").items.first { (it["localBalance"]?.jsonPrimitive?.intOrNull ?: 0) in 1..12 }
        val id = counted.id()!!
        repeat(counted["localBalance"]!!.jsonPrimitive.intOrNull!!) { tap("dishes:row-$id:dishes.setBalance:minus"); idle() }

        inner("dishes:row-$id:dishes.setBalance:value").assertTextEquals("Нет в наличии")
        tap("dishes:row-$id:dishes.setBalance:plus")
        idle()
        inner("dishes:row-$id:dishes.setBalance:value").assertTextEquals("1")
        assertEquals(JsonPrimitive(1), page("limited").items.first { it.id() == id }["localBalance"])
    }

    @Test
    fun theChipsAndTheSearchAskTheServer() = run {
        tap("dishes:chip-all")
        idle()
        node("dishes:chip-all").assertIsSelected()
        node("dishes:row-d1").assertExists()
        type("dishes:search", "борщ")
        advance(400)
        node("dishes:row-d9").assertExists()
        assertAbsent("dishes:row-d1")

        type("dishes:search", "нет такого")
        advance(400)
        assertShown("dishes:empty")
    }

    @Test
    fun aDishOpensAsACardWithItsFieldsByTheDeclaration() = run {
        tap("dishes:chip-all")
        idle()
        tap("dishes:row-d3")
        idle()
        assertShown("dishes:detail")
        assertEquals("Четыре сыра 30 см", screen.chrome.value.title)
        assertTrue(screen.chrome.value.canGoBack)
        node("dishes:field-price:value").assertTextEquals("650 ₽")
        node("dishes:field-groupTitle:value").assertTextEquals("Пицца")
        node("dishes:field-mode:value").assertTextEquals("Меньший из ручного и RMS")

        type("dishes:dishes.setBalance:field", "15")
        tap("dishes:dishes.setBalance:set")
        idle()
        node("dishes:value").assertTextEquals("15")

        tap("dishes:dishes.setBalance:special--1")
        idle()
        node("dishes:value").assertTextEquals("Безлимит")

        tap("dishes:dishes.setEnabled")
        idle()
        node("dishes:value").assertTextEquals("Нет в наличии")
        node("dishes:field-placeEnable:value").assertTextEquals("Нет")

        tap("dishes:back")
        idle()
        assertShown("dishes:list")
        assertNull(screen.chrome.value.title)
    }

    @Test
    fun anotherPointIsAnotherScreenAndNothingOfTheFirstStaysOnIt() = run {
        tap("dishes:chip-all")
        idle()
        type("dishes:search", "пицца")
        advance(400)
        runBlocking { server.api.call("demo", "dishes.setBalance", jsonObjectOf("id" to JsonPrimitive("d1"), "balance" to JsonPrimitive(77)), "p1", "ru") }

        tap("dishes:place")
        idle()
        assertShown("dishes:places")
        tap("dishes:places:item-p2")
        idle()

        assertEquals("p2", server.place.value?.id)
        assertEquals("Мира, 3", screen.chrome.value.subtitle)
        node("dishes:chip-limited").assertIsSelected()
        node("dishes:search").assertTextEquals("")
        assertEquals("p2", server.calls.last().second)
        val atB = page("all", "p2").items.first { it.id() == "d1" }
        assertNotEquals(JsonPrimitive(77), atB["localBalance"])
    }

    @Test
    fun onePointMeansNoPointLine() = run(backend = DemoServer.demo(places = DemoBackend.ONE_POINT)) {
        assertAbsent("dishes:place")
        assertEquals("Садовая, 3", screen.chrome.value.subtitle)
    }

    @Test
    fun anOrderMovesByTheButtonsTheServerAllowsAndRejectingAsksFirst() = run(appId = "orders") {
        val order = runBlocking { ListPage.of(server.api.call("demo", "orders.list", JsonObject(emptyMap()), "p1", "ru").value) }
            .items.first { it["state"] == JsonPrimitive("ORDER") }
        val id = order.id()!!
        node("orders:row-$id").assertExists()
        tap("orders:row-$id")
        idle()
        inner("orders:badge").assertTextEquals("Заказан")
        node("orders:orders.setState-COOKING").assertExists()

        // On a phone the main step is the wide button and the others are behind «⋯».
        tap("orders:orders.setState:more")
        idle()
        tap("orders:orders.setState-REJECT")
        idle()
        assertShown("orders:orders.setState-REJECT:confirm")
        tap("orders:orders.setState-REJECT:no")
        idle()
        tap("orders:orders.setState-COOKING")
        idle()
        inner("orders:badge").assertTextEquals("Готовится")
        // From COOKING there is no way back to ORDER, and COOKING itself is gone.
        assertAbsent("orders:orders.setState-COOKING")

        tap("orders:orders.setState:more")
        idle()
        tap("orders:orders.setState-REJECT")
        idle()
        tap("orders:orders.setState-REJECT:yes")
        idle()
        inner("orders:badge").assertTextEquals("Отклонён")
        assertAbsent("orders:orders.setState-DONE")
    }

    /**
     * The orders screen asks what moved while it is open (plan section 3): a probe with `since`
     * every ten seconds, and a full re-read as soon as the probe brings anything — so a move made
     * on another device or on the web board lands on the screen without anybody touching it.
     */
    @Test
    fun theOrdersScreenPollsWhatChangedAndCatchesUpByItself() {
        var now = 1_700_000_000_000L
        val backend = DemoBackend(MemoryDemoStore(), DemoBackend.TWO_POINTS, clock = { now }, serverName = "Демо")
        run(appId = "orders", backend = backend) {
            val id = runBlocking { ListPage.of(server.api.call("demo", "orders.list", JsonObject(emptyMap()), "p1", "ru").value) }
                .items.first { it["state"] == JsonPrimitive("ORDER") }.id()!!
            node("orders:row-$id").assertExists()
            val cookingBefore = cookingBadges()

            // Somebody else — the web board, another phone — starts cooking it.
            now += 60_000
            runBlocking {
                server.api.call("demo", "orders.setState", jsonObjectOf("id" to JsonPrimitive(id), "to" to JsonPrimitive("COOKING")), "p1", "ru")
            }
            assertEquals(JsonPrimitive("ORDER"), screen.list.value.items.first { it.id() == id }["state"])

            advance(10_000)

            val probes = server.requests.filter { it.first == "orders.list" && "since" in it.third }
            assertTrue(probes.isNotEmpty(), "the screen never asked what changed: ${server.requests}")
            assertEquals(JsonPrimitive("COOKING"), screen.list.value.items.first { it.id() == id }["state"])
            assertEquals(cookingBefore + 1, cookingBadges(), "the row on screen still says what it said before")
        }
    }

    /** How many cards say they are «Готовится» (their stripe's state): the screen's own account of what it was told. */
    private fun AppTestScope<*>.cookingBadges(): Int =
        ui.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Готовится"),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size

    /** «Блюда» take no `since`: that screen is re-read on return, never polled. */
    @Test
    fun theDishesScreenIsNotPolled() = run {
        val before = server.requests.size
        advance(60_000)
        assertEquals(before, server.requests.size)
    }

    @Test
    fun theSnapshotBringsBackTheStackWithItsChipAndItsSearch() = run(
        restore = buildRestore(
            """{"place":"p1","frames":[{"method":"dishes.list","chip":"all"},{"method":"dishes.get","params":{"id":"d2"}}]}""",
        ),
    ) {
        idle()
        assertShown("dishes:detail")
        assertEquals("Маргарита 30 см", screen.chrome.value.title)
        tap("dishes:back")
        idle()
        node("dishes:chip-all").assertIsSelected()
        val saved = screen.saveState()
        val frames = saved["frames"]!!.jsonArray
        assertEquals(1, frames.size)
        assertEquals("dishes.list", frames[0].jsonObject["method"]!!.jsonPrimitive.content)
        assertEquals("all", frames[0].jsonObject["chip"]!!.jsonPrimitive.content)
        assertEquals("p1", saved["place"]!!.jsonPrimitive.content)
    }

    /** A snapshot of the build before the stack: the chip and the one open record it knew. */
    @Test
    fun aSnapshotOfAnOlderBuildOpensTheSameCard() = run(
        restore = buildRestore("""{"place":"p1","chip":"all","query":"","open":"d2"}"""),
    ) {
        idle()
        assertShown("dishes:detail")
        assertEquals("Маргарита 30 см", screen.chrome.value.title)
        tap("dishes:back")
        idle()
        node("dishes:chip-all").assertIsSelected()
    }

    @Test
    fun aRecordWithoutActionsIsReadOnly() {
        // The same server, but every record arrives without `_actions`: no −, no +, no switch.
        val backend = DemoServer.demo()
        val readOnly = object : cx.m42.restoapp.protocol.MobileApi by backend {
            override suspend fun call(token: String, method: String, params: JsonObject, place: String?, lang: String) =
                backend.call(token, method, params, place, lang).let { answer -> answer.copy(value = stripActions(answer.value)) }
        }
        val server = DemoServer(readOnly, "dishes")
        runAppTest(ServerApp(server.app.value!!, "ru"), prepare = { it.provide(RestoServer.Key, server) }) {
            idle()
            val first = runBlocking { ListPage.of(readOnly.call("demo", "dishes.list", JsonObject(emptyMap()), "p1", "ru").value) }.items.first().id()
            inner("dishes:row-$first:value").assertExists()
            assertAbsent("dishes:row-$first:dishes.setBalance:minus")
            assertAbsent("dishes:row-$first:dishes.setEnabled")
            click("dishes:row-$first")
            idle()
            assertShown("dishes:detail")
            assertAbsent("dishes:dishes.setBalance:set")
            assertAbsent("dishes:dishes.setEnabled")
        }
    }

    private fun stripActions(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterKeys { it != "_actions" }.mapValues { stripActions(it.value) })
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(element.map(::stripActions))
        else -> element
    }

    @Test
    fun anAppTheServerSendsAsAScriptSaysItNeedsANewerClient() {
        val declared = ProtocolJson.decodeFromString(
            DeclaredApp.serializer(),
            """{"id":"later","version":"1.0.0","title":"Потом","home":"later.a","scope":"server","methods":[],"script":{"url":"/api/mobile/app-assets/later/abababababababababababababababababababababababababababababababab.js","sha256":"abababababababababababababababababababababababababababababababab","size":42}}""",
        )
        val server = object : RestoServer by DemoServer(DemoServer.demo(), "dishes") {
            override val app = kotlinx.coroutines.flow.MutableStateFlow<DeclaredApp?>(declared)
            override val places = kotlinx.coroutines.flow.MutableStateFlow(emptyList<cx.m42.restoapp.protocol.Place>())
        }
        runAppTest(ServerApp(declared, "ru"), prepare = { it.provide(RestoServer.Key, server) }) {
            assertShown("later:needs-update")
        }
    }

    @Test
    fun anUnknownViewIsTextNotACrash() {
        val declared = ProtocolJson.decodeFromString(
            DeclaredApp.serializer(),
            """{"id":"grid","version":"1.0.0","title":"Сетка","home":"grid.a","scope":"server","methods":[{"name":"grid.a","kind":"query","title":"A","input":{"type":"object"},"ui":{"view":"grid"}}]}""",
        )
        val server = object : RestoServer by DemoServer(DemoServer.demo(), "dishes") {
            override val app = kotlinx.coroutines.flow.MutableStateFlow<DeclaredApp?>(declared)
            override val places = kotlinx.coroutines.flow.MutableStateFlow(emptyList<cx.m42.restoapp.protocol.Place>())
        }
        runAppTest(ServerApp(declared, "ru"), prepare = { it.provide(RestoServer.Key, server) }) {
            assertShown("grid:unknown")
        }
    }

    private fun buildRestore(text: String): JsonObject = ProtocolJson.parseToJsonElement(text) as JsonObject

    @Suppress("unused")
    private fun Declaration.unused() = recordOf(JsonObject(emptyMap())) ?: assertFalse(false)
}
