package cx.m42.restoapp.assistant

import cx.m42.restoapp.protocol.CryptoPuzzleSolver
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.restoapp.protocol.HttpMobileApi
import cx.m42.superizer.host.net.KtorNetworkService
import cx.m42.superizer.host.net.defaultHttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The transport against a real panel, as the app talks to it: sign in to the mobile API (its CAPTCHA solved here),
 * `assistant.session`, then the panel's own routes — status, a new conversation, a run streamed to its end, the
 * history that has it. Skipped without `-Passistant.url`:
 *
 *     node tools/fake-llm/serve.mjs 17321 &   # and the cloud panel on :17320 with CLOUD_AI_* on it
 *     ./gradlew :apps:assistant:desktopTest --rerun --tests '*AssistantLiveTest*' \
 *       -Passistant.url=http://127.0.0.1:17320 -Passistant.login=user1 -Passistant.password=user1
 */
class AssistantLiveTest {

    private val url = System.getProperty("assistant.url")?.trimEnd('/')
    private val login = System.getProperty("assistant.login") ?: "user1"
    private val password = System.getProperty("assistant.password") ?: login

    @Test
    fun `a run of the panel's assistant through assistant session`() = runBlocking {
        assumeTrue("no -Passistant.url", url != null)
        val api = HttpMobileApi(url!!, KtorNetworkService(KtorNetworkService.defaultClient()))
        val token = api.login(login, password, api.challenge()?.let { CryptoPuzzleSolver.solve(it) {} }, DeviceInfo("AssistantLiveTest", "desktop")).token
        val declaration = api.describe(token, "ru").value
        val app = declaration.apps.single { it.id == "assistant" }
        assertTrue(AssistantApp.canDraw(app), "declared: ${app.client}")

        var sessions = 0
        val http = defaultHttpClient { expectSuccess = false; install(HttpTimeout) }
        val transport = AdminizerAgentTransport(url, http, session = {
            sessions++
            AgentSession.of(api.call(token, AssistantApp.SESSION_METHOD, JsonObject(emptyMap()), null, "ru").value)
        })
        val status = transport.status()
        assertTrue(status.ready, "status: $status")
        transport.newConversation()
        assertEquals(emptyList(), transport.history())

        val events = transport.run("Привет из теста").toList()
        assertTrue(events.any { it is AgentEvent.TextDelta }, "events: $events")
        assertTrue(events.last() is AgentEvent.RunDone || events.any { it is AgentEvent.AgentDone }, "events: $events")
        val reply = events.fold(emptyList<Part>()) { acc, e -> acc.apply(e) }
        assertTrue(reply.filterIsInstance<Part.Text>().joinToString { it.text }.isNotBlank())

        val history = transport.history()
        assertEquals(Role.User, history.first().role)
        assertEquals("Привет из теста", history.first().text)
        assertEquals(Role.Assistant, history.last().role)

        val list = transport.conversations()
        assertEquals(list.activeId, list.items.first().id)
        assertEquals(1, sessions, "one session for the whole walk")
        http.close()
        api.logout(token)
    }
}
