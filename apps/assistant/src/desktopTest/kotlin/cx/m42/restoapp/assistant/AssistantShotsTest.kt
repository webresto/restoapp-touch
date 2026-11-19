package cx.m42.restoapp.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import cx.m42.superizer.ThemeMode
import cx.m42.superizer.app.AppId
import cx.m42.superizer.kit.KitAccent
import cx.m42.superizer.kit.WindowClassProvider
import cx.m42.superizer.kit.kitTypography
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.testing.FakeAppRuntime
import cx.m42.superizer.ui.shell.HostTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Not a check but a look (05-native-apps.md §5.3): the chat in 420 / 800 / 1280, light and dark, as PNGs in
 * `apps/assistant/build/screenshots-native/<width>-<theme>/` — a conversation with a tool card, a table and a link,
 * the welcome of an empty one, the history on a narrower window, and a reply being typed.
 */
@OptIn(ExperimentalTestApi::class)
class AssistantShotsTest {

    @Test fun phoneLight() = shots(420, ThemeMode.Light)
    @Test fun phoneDark() = shots(420, ThemeMode.Dark)
    @Test fun tabletLight() = shots(800, ThemeMode.Light)
    @Test fun tabletDark() = shots(800, ThemeMode.Dark)
    @Test fun desktopLight() = shots(1280, ThemeMode.Light)
    @Test fun desktopDark() = shots(1280, ThemeMode.Dark)

    /** The panel as a person sees it after a working morning: four conversations, the active one answered. */
    private class Canned : AssistantTransport {
        var messages = listOf(
            ChatMessage("u1", Role.User, listOf(Part.Text("Как идёт день? И что у нас на стопе?"))),
            ChatMessage(
                "a1",
                Role.Assistant,
                listOf(
                    Part.Tool("t1", "orders.list", JsonObject(mapOf("period" to JsonPrimitive("today"))), JsonObject(mapOf("count" to JsonPrimitive(214)))),
                    Part.Text(
                        "День идёт лучше обычного вторника: выручка **86 400 ₽**, это на 12% больше, чем неделю назад.\n\n" +
                            "| Точка | Заказов | Выручка | Средний чек |\n|---|---:|---:|---:|\n" +
                            "| Ленина, 5 | 142 | 57 900 ₽ | 1 420 ₽ |\n| Мира, 3 | 72 | 28 500 ₽ | 1 390 ₽ |\n\n" +
                            "На стопе три позиции:\n\n- **Чизкейк** — закончился в 14:20 на Ленина\n- **Лимонад домашний** — нет на обеих точках\n\n" +
                            "[Открыть стоп-лист](/admin/model/dish)",
                    ),
                ),
            ),
            ChatMessage("u2", Role.User, listOf(Part.Text("Сделай промокод на 10% для тех, кто давно не заказывал"))),
            ChatMessage(
                "a2",
                Role.Assistant,
                listOf(
                    Part.Tool("t2", "promotions.create", null, JsonPrimitive("draft"), isError = true),
                    Part.Text("Не получилось создать черновик: у вашей роли нет права на промокоды. Попросите администратора."),
                ),
            ),
        )

        override suspend fun status() = AgentStatus(
            state = "ready",
            title = "Ассистент",
            welcomeHint = "Спросите про заказы, выручку, стоп-лист или попросите подготовить акцию. Ассистент ничего не включает сам — только предлагает.",
            suggestions = listOf("Что на стопе прямо сейчас?", "Выручка за неделю по дням", "Почему опаздывает заказ № 1319?"),
        )
        override suspend fun meta() = AgentMeta(model = "claude", turns = 2, totalTokens = 18_400)
        override suspend fun conversations() = Conversations(
            "c1",
            listOf(
                Conversation("c1", "Выручка и стоп-лист", "2026-11-18T10:05:00Z"),
                Conversation("c2", "Промокод для рассылки", "2026-11-17T18:40:00Z"),
                Conversation("c3", "Зоны доставки на Мира", "2026-11-14T09:12:00Z"),
                Conversation("c4", "Права для нового кассира", "2026-11-02T16:00:00Z"),
            ),
        )
        override suspend fun newConversation() {
            messages = emptyList()
        }
        override suspend fun select(id: String) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun history() = messages
        override fun run(message: String): Flow<AgentEvent> = flow { kotlinx.coroutines.awaitCancellation() }
    }

    private fun shots(width: Int, theme: ThemeMode) = runDesktopComposeUiTest(width = width, height = 860) {
        val out = File("build/screenshots-native/$width-${theme.name.lowercase()}").apply { mkdirs() }
        fun shot(name: String) {
            waitForIdle()
            ImageIO.write(captureToImage().toAwtImage(), "png", File(out, "$name.png"))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtime = FakeAppRuntime(AppId("assistant"), scope)
        val transport = Canned()
        val instance = AssistantInstance(runtime, shotServer(), { transport }, "Ассистент")
        runBlocking { instance.onLaunch() }
        setContent {
            CompositionLocalProvider(LocalAppRuntime provides runtime) {
                HostTheme(theme, KitAccent.Violet, kitTypography()) {
                    WindowClassProvider { Box(Modifier.fillMaxSize()) { instance.Content() } }
                }
            }
        }
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("assistant:messages").assertExists() }.isSuccess }
        mainClock.advanceTimeBy(300)
        shot("01-assistant-chat")

        if (width < 1000) {
            onNodeWithTag("assistant:conversations").performClick()
            mainClock.advanceTimeBy(400)
            shot("02-assistant-history")
            instance.openList(false)
            mainClock.advanceTimeBy(400)
        }

        instance.newConversation()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithTag("assistant:welcome").assertExists() }.isSuccess }
        shot("03-assistant-welcome")

        onNodeWithTag("assistant:input").performTextInput("Выручка за неделю по дням")
        onNodeWithTag("assistant:send").performClick()
        mainClock.advanceTimeBy(500)
        shot("04-assistant-typing")
        instance.stop()
        scope.cancelChildren()
    }

    private fun CoroutineScope.cancelChildren() = coroutineContext[kotlinx.coroutines.Job]?.cancel()
}

private fun shotServer(): cx.m42.restoapp.render.RestoServer = object : cx.m42.restoapp.render.RestoServer {
    override val app = kotlinx.coroutines.flow.MutableStateFlow(null)
    override val facts = kotlinx.coroutines.flow.MutableStateFlow(null)
    override val places = kotlinx.coroutines.flow.MutableStateFlow(emptyList<cx.m42.restoapp.protocol.Place>())
    override val place = kotlinx.coroutines.flow.MutableStateFlow(null)
    override fun selectPlace(id: String) = Unit
    override val status = kotlinx.coroutines.flow.MutableStateFlow(cx.m42.restoapp.render.ServerStatus.Online)
    override val notices = kotlinx.coroutines.flow.MutableSharedFlow<String>()
    override suspend fun call(method: String, params: JsonObject): kotlinx.serialization.json.JsonElement =
        throw UnsupportedOperationException(method)
}
