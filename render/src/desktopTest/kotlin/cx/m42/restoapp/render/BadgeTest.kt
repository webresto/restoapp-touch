package cx.m42.restoapp.render

import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.superizer.app.AppConfig
import cx.m42.superizer.app.AppSetupContext
import cx.m42.superizer.event.SuperizerEvent
import cx.m42.superizer.runtime.AppRuntime
import cx.m42.superizer.runtime.HostLifecycle
import cx.m42.superizer.testing.FakeAppRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

/**
 * The mark of an app in the menu (P4): asked on connecting, after the app's own mutations and once a minute
 * while the host is in front — on the test's virtual clock, so a minute takes no time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BadgeTest {

    private fun declaration(badge: String?, input: String = """{"type":"object"}""") = """
        {"id":"b","version":"1.0.0","title":"Счётчик","home":"b.list",${badge?.let { """"badge":"$it",""" } ?: ""}
         "methods":[
          {"name":"b.list","kind":"query","title":"Вещи","ui":{"view":"list","item":{"title":"name"}}},
          {"name":"b.count","kind":"query","title":"Сколько","input":$input},
          {"name":"b.touch","kind":"mutation","title":"Тронуть","input":{"type":"object"}}
         ]}
    """.trimIndent()

    private class Counter {
        var answer: Any = 8
        val asked = mutableListOf<Long>()
    }

    private fun server(badge: String?, counter: Counter, input: String = """{"type":"object"}""") = HintServer(declaration(badge, input)) { method, _ ->
        when (method) {
            "b.count" -> when (val answer = counter.answer) {
                is MobileApiException -> throw answer
                is String -> JsonPrimitive(answer)
                else -> JsonPrimitive(answer as Int)
            }
            else -> ProtocolJson.parseToJsonElement("""{"items":[]}""")
        }
    }

    @Test
    fun theMarkIsAskedOnConnectingThenEveryMinuteInFrontAndAfterAMutation() = runTest {
        val counter = Counter()
        val server = server("b.count", counter)
        val lifecycle = MutableStateFlow(HostLifecycle.Foreground)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val poller = BadgePoller(scope, server, lifecycle).start()
        fun asked() = server.calls.count { it.first == "b.count" }

        scope.runCurrent()
        assertEquals(1, asked())
        assertEquals("8", poller.mark.value)

        scope.advanceTimeBy(59_000)
        scope.runCurrent()
        assertEquals(1, asked())
        scope.advanceTimeBy(1_001)
        scope.runCurrent()
        assertEquals(2, asked())

        // The app's own mutation: at once, not in a minute.
        counter.answer = 7
        poller.refresh()
        scope.runCurrent()
        assertEquals(3, asked())
        assertEquals("7", poller.mark.value)

        // In the background nothing is asked, and the mark stays.
        lifecycle.value = HostLifecycle.Background
        scope.runCurrent()
        scope.advanceTimeBy(300_000)
        scope.runCurrent()
        assertEquals(3, asked())
        assertEquals("7", poller.mark.value)

        // Back in front: asked again straight away.
        counter.answer = 0
        lifecycle.value = HostLifecycle.Foreground
        scope.runCurrent()
        assertEquals(4, asked())
        // Zero is no mark.
        assertNull(poller.mark.value)

        counter.answer = 250
        poller.refresh()
        scope.runCurrent()
        assertEquals("99+", poller.mark.value)

        // No answer, or one that is no number: no mark.
        counter.answer = MobileApiException(MobileApiException.NETWORK, "down")
        poller.refresh()
        scope.runCurrent()
        assertNull(poller.mark.value)
        counter.answer = "8"
        poller.refresh()
        scope.runCurrent()
        assertNull(poller.mark.value)
        scope.cancel()
    }

    @Test
    fun aBadgeTheClientCannotAskIsNoBadge() = runTest {
        for ((badge, input) in listOf(
            null to """{"type":"object"}""",
            "b.missing" to """{"type":"object"}""",
            "b.touch" to """{"type":"object"}""",
            "b.count" to """{"type":"object","required":["id"],"properties":{"id":{"type":"string"}}}""",
        )) {
            val counter = Counter()
            val server = server(badge, counter, input)
            val scope = TestScope(StandardTestDispatcher(testScheduler))
            val poller = BadgePoller(scope, server, MutableStateFlow(HostLifecycle.Foreground)).start()
            scope.runCurrent()
            scope.advanceTimeBy(120_000)
            scope.runCurrent()
            assertEquals(0, server.calls.size, "$badge asked")
            assertNull(poller.mark.value)
            scope.cancel()
        }
    }

    @Test
    fun theAppGivesTheMenuItsMarkAndAMutationOfItsScreenAsksAgain() = runTest {
        val counter = Counter()
        val server = server("b.count", counter)
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val app = ServerApp(server.app.value!!, "ru")
        val runtime = FakeAppRuntime(app.id, scope, declaredServices = app.manifest.requires).also { it.provide(RestoServer.Key, server) }
        var mark: StateFlow<String?>? = null
        app.setup(object : AppSetupContext {
            override val runtime: AppRuntime = runtime
            override fun settingsSection(section: @androidx.compose.runtime.Composable (runtime: AppRuntime) -> Unit) = Unit
            override fun deepLink(path: String, toConfig: (params: Map<String, String>) -> AppConfig) = Unit
            override fun listener(handler: suspend (event: SuperizerEvent) -> Unit) = Unit
            override fun badge(value: StateFlow<String?>) {
                mark = value
            }
        })
        scope.runCurrent()
        assertEquals("8", mark?.value)

        val instance = app.launch(runtime, app.configSpec.default) as ServerAppInstance
        counter.answer = 3
        instance.actOnList(server.app.value!!.method("b.touch")!!, kotlinx.serialization.json.JsonObject(emptyMap()))
        scope.runCurrent()
        assertEquals("3", mark?.value)
        assertEquals(2, server.calls.count { it.first == "b.count" })
        scope.cancel()
    }
}
