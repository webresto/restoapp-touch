package cx.m42.restoapp.assistant

import cx.m42.restoapp.render.RestoServer
import cx.m42.superizer.app.AppId
import cx.m42.superizer.testing.FakeAppRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Found on dev.resto.cloud: «Новый диалог», then a message sent before the history of the new conversation came back —
 * the late history (empty) wiped the person's own message off the screen. A load older than a send keeps the screen.
 */
class AssistantRaceTest {

    private class SlowHistory(private val demo: DemoAssistantTransport) : AssistantTransport by demo {
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun history(): List<ChatMessage> {
            val before = demo.history()
            gate?.await()
            return before
        }
        override fun run(message: String): Flow<AgentEvent> = demo.run(message)
    }

    @Test
    fun `nothing is sent while a new conversation is being made`() = runTest(UnconfinedTestDispatcher()) {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val transport = SlowHistory(DemoAssistantTransport("Демо", "ru"))
        val instance = AssistantInstance(FakeAppRuntime(AppId("assistant"), scope), stubServer(), { transport }, "Ассистент")
        instance.onLaunch()
        instance.start()
        transport.gate = CompletableDeferred()
        instance.newConversation()
        assertEquals(true, instance.state.value.busy)
        instance.send("рано")
        assertEquals(emptyList(), instance.state.value.messages)
        transport.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(false, instance.state.value.busy)
        instance.send("Привет")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(Role.User, Role.Assistant), instance.state.value.messages.map { it.role })
        scope.cancel()
    }

    @Test
    fun `a history that comes after a send does not wipe the message`() = runTest(UnconfinedTestDispatcher()) {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val runtime = FakeAppRuntime(AppId("assistant"), scope)
        val transport = SlowHistory(DemoAssistantTransport("Демо", "ru"))
        val server = object : RestoServer by stubServer() {}
        val instance = AssistantInstance(runtime, server, { transport }, "Ассистент")
        instance.onLaunch()
        instance.start()
        testScheduler.advanceUntilIdle()

        transport.gate = CompletableDeferred()
        instance.reload()
        instance.send("Привет")
        testScheduler.advanceUntilIdle()
        transport.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()

        val roles = instance.state.value.messages.map { it.role }
        assertEquals(listOf(Role.User, Role.Assistant), roles)
        assertEquals("Привет", instance.state.value.messages.first().text)
        scope.cancel()
    }
}

private fun kotlinx.coroutines.CoroutineScope.cancel() = (this.coroutineContext[kotlinx.coroutines.Job])?.cancel()

private fun stubServer(): RestoServer = object : RestoServer {
    override val app = kotlinx.coroutines.flow.MutableStateFlow(null)
    override val facts = kotlinx.coroutines.flow.MutableStateFlow(null)
    override val places = kotlinx.coroutines.flow.MutableStateFlow(emptyList<cx.m42.restoapp.protocol.Place>())
    override val place = kotlinx.coroutines.flow.MutableStateFlow(null)
    override fun selectPlace(id: String) = Unit
    override val status = kotlinx.coroutines.flow.MutableStateFlow(cx.m42.restoapp.render.ServerStatus.Online)
    override val notices = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    override suspend fun call(method: String, params: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonElement =
        throw UnsupportedOperationException(method)
}
