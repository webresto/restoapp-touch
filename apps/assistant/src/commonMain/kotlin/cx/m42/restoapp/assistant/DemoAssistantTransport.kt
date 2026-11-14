package cx.m42.restoapp.assistant

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The assistant of the demo server (`assistant.session` → `transport: "demo"`): no model and no network, the same
 * events a panel streams — a call of a tool when asked about tools, an echo in Markdown otherwise — so the chat is
 * walked on the demo and in tests exactly as against a real panel.
 */
public class DemoAssistantTransport(
    private val title: String,
    private val lang: String = "ru",
    private val stepDelayMs: Long = 0,
) : AssistantTransport {

    private data class Dialog(val id: String, var title: String, val messages: MutableList<ChatMessage> = mutableListOf())

    private var counter = 0
    private val dialogs = mutableListOf(Dialog("demo-1", newTitle()))
    private var active = dialogs.first()

    private fun ru(ru: String, en: String) = if (lang.startsWith("ru")) ru else en
    private fun newTitle() = ru("Новый диалог", "New conversation")

    override suspend fun status(): AgentStatus = AgentStatus(
        state = "ready",
        title = title,
        welcomeHint = ru("Спросите о сервере или попросите что-нибудь сделать.", "Ask about the server or ask to do something."),
        composerPlaceholder = ru("Спросите ассистента…", "Ask the assistant…"),
        suggestions = listOf(ru("Какие у тебя инструменты?", "What tools do you have?"), ru("Привет!", "Hello!")),
    )

    override suspend fun meta(): AgentMeta = AgentMeta(model = "demo", turns = active.messages.count { it.role == Role.User })

    override suspend fun conversations(): Conversations =
        Conversations(active.id, dialogs.reversed().map { Conversation(it.id, it.title, null) })

    override suspend fun newConversation() {
        if (active.messages.isEmpty()) return
        active = Dialog("demo-${dialogs.size + 1}", newTitle()).also { dialogs += it }
    }

    override suspend fun select(id: String) {
        active = dialogs.firstOrNull { it.id == id } ?: throw AssistantException(404, "Conversation not found")
    }

    override suspend fun delete(id: String) {
        if (id == active.id) throw AssistantException(400, "Create or select another conversation before deleting this one")
        dialogs.removeAll { it.id == id }
    }

    override suspend fun history(): List<ChatMessage> = active.messages.toList()

    override fun run(message: String): Flow<AgentEvent> = flow {
        if (message.isBlank()) throw AssistantException(400, "Message is required")
        val dialog = active
        if (dialog.messages.isEmpty()) dialog.title = message.take(120)
        dialog.messages += ChatMessage("u${++counter}", Role.User, listOf(Part.Text(message)))
        var parts: List<Part> = emptyList()
        suspend fun send(event: AgentEvent) {
            if (stepDelayMs > 0) delay(stepDelayMs)
            parts = parts.apply(event)
            emit(event)
        }
        if (Regex("tool|инструмент", RegexOption.IGNORE_CASE).containsMatchIn(message)) {
            send(AgentEvent.ToolStart("call-$counter", "list_tools", JsonObject(emptyMap())))
            send(AgentEvent.ToolDone("call-$counter", "list_tools", JsonObject(mapOf("groups" to JsonPrimitive("demo")))))
        }
        val reply = ru("**Демо:** вы написали «$message».\n\n- это ответ без модели\n- как в панели", "**Demo:** you wrote “$message”.\n\n- a reply without a model\n- as in the panel")
        for (word in reply.split(" ")) send(AgentEvent.TextDelta(if (parts.lastOrNull() is Part.Text) " $word" else word))
        send(AgentEvent.TextDone(reply))
        send(AgentEvent.AgentDone("complete"))
        dialog.messages += ChatMessage("a$counter", Role.Assistant, parts)
        emit(AgentEvent.RunDone)
    }
}
