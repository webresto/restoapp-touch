@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.render

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import cx.m42.restoapp.demo.DemoRole
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * «Клиенты» drawn by the renderer: a list with a search, a card, and the one thing slice A adds to
 * it — a «+» on the list screen whose form comes from the schema of `clients.create`.
 */
class ClientsScreenTest {

    private val AppTestScope<*>.screen get() = instance as ServerAppInstance

    private fun AppTestScope<*>.tap(tag: String) {
        runCatching { node(tag).performScrollTo() }
        click(tag)
    }

    /** The search is debounced in the instance: its scope is virtual time, not the UI's clock. */
    private fun AppTestScope<*>.advance(millis: Long) {
        val scheduler = (runtime.scope as kotlinx.coroutines.test.TestScope).testScheduler
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
        idle()
    }

    private fun clients(role: DemoRole = DemoRole.Admin, block: AppTestScope<ServerAppConfig>.() -> Unit) {
        val backend = DemoServer.demo(role)
        val server = DemoServer(backend, "clients")
        runAppTest(ServerApp(server.app.value!!, "ru"), prepare = { it.provide(RestoServer.Key, server) }, block = block)
    }

    @Test
    fun theListSearchesByDigitsOfTheNumber() = clients {
        idle()
        assertShown("clients:list")
        ui.onNodeWithText("Анна Смирнова").assertExists()
        type("clients:search", "0033")
        advance(1_000)
        assertEquals(1, screen.list.value.items.size)
        ui.onNodeWithText("Игорь Петров").assertExists()
    }

    @Test
    fun theFormOnTheListMakesACustomerAndOpensTheirCard() = clients {
        idle()
        // The «+» is there because the declaration has an action with `ui.on: "list"`.
        assertShown("clients:add")
        tap("clients:add")
        assertShown("clients:form")
        type("clients:form:name", "Сергей Крылов")
        type("clients:form:phone", "+7 999 123-45-67")
        tap("clients:form:submit")
        idle()
        // The server made it, and the card of what was made is what opens.
        assertShown("clients:detail")
        assertEquals("Сергей Крылов", screen.chrome.value.title)
    }

    /** `required` of the schema is what the button waits for — the server is not asked half a form. */
    @Test
    fun theFormWaitsForWhatTheSchemaRequires() = clients {
        idle()
        val before = (runtime.service(RestoServer.Key) as DemoServer).calls.size
        tap("clients:add")
        type("clients:form:name", "Без телефона")
        tap("clients:form:submit")
        idle()
        // Nothing went to the server and the form is still open, waiting for the number.
        assertEquals(before, (runtime.service(RestoServer.Key) as DemoServer).calls.size)
        assertShown("clients:form")
        type("clients:form:phone", "+7 999 000-00-00")
        tap("clients:form:submit")
        idle()
        assertShown("clients:detail")
    }

    @Test
    fun anEmptyFormClosesWithoutAsking() = clients {
        idle()
        tap("clients:add")
        tap("clients:add")
        idle()
        assertAbsent("unsaved:question")
        assertAbsent("clients:form")
    }

    @Test
    fun aFilledFormAsksBeforeItIsThrownAway() = clients {
        idle()
        tap("clients:add")
        type("clients:form:name", "Сергей Крылов")
        tap("clients:form:cancel")
        idle()
        assertShown("unsaved:question")
        tap("unsaved:stay")
        idle()
        assertAbsent("unsaved:question")
        node("clients:form:name").assertTextContains("Сергей Крылов")
        tap("clients:form:cancel")
        idle()
        tap("unsaved:leave")
        idle()
        assertAbsent("clients:form")
    }

    @Test
    fun saveAndLeaveWaitsForWhatTheSchemaRequires() = clients {
        idle()
        tap("clients:add")
        type("clients:form:name", "Без телефона")
        tap("clients:form:cancel")
        idle()
        tap("unsaved:save")
        idle()
        assertAbsent("unsaved:question")
        assertShown("clients:form")
        type("clients:form:phone", "+7 999 000-00-00")
        tap("clients:form:cancel")
        idle()
        tap("unsaved:save")
        idle()
        assertAbsent("clients:form")
        assertShown("clients:detail")
    }

    /** The ways out the shell owns: the menu and another server ask through the veto. */
    @Test
    fun theHostsQuestionCanSaveToo() = clients {
        idle()
        assertTrue(runBlocking { screen.onCloseRequested() })
        tap("clients:add")
        type("clients:form:name", "Без телефона")
        idle()
        assertFalse(runBlocking { screen.onCloseRequested() })
        assertTrue(screen.canSaveBeforeClose)
        assertFalse(runBlocking { screen.saveBeforeClose() })
        type("clients:form:phone", "+7 999 000-00-00")
        idle()
        assertTrue(runBlocking { screen.saveBeforeClose() })
        idle()
        assertTrue(runBlocking { screen.onCloseRequested() })
    }

    @Test
    fun anOperatorSeesNoPlusBecauseTheServerDeclaresNoSuchMethod() = clients(DemoRole.Operator) {
        idle()
        assertShown("clients:list")
        assertAbsent("clients:add")
    }

    /** An app the client is supposed to draw itself, in a build that cannot: a tile, not a crash. */
    @Test
    fun anAppForTheClientToDrawShowsTheTile() {
        val declared = ProtocolJson.decodeFromString(
            DeclaredApp.serializer(),
            """{"id":"pos","version":"1.0.0","title":"Касса","home":"pos.order","scope":"server","methods":[],"client":{"native":"pos","min":1}}""",
        )
        val server = DemoServer(DemoServer.demo(), "pos")
        runAppTest(ServerApp(declared, "ru"), prepare = { it.provide(RestoServer.Key, server) }) {
            idle()
            assertShown("pos:needs-update")
            ui.onNodeWithText("Нужна новая версия приложения").assertExists()
        }
    }
}
