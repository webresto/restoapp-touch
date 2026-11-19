package cx.m42.restoapp.assistant

import androidx.compose.runtime.Composable
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.render.RestoServer
import cx.m42.superizer.app.AppChrome
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppLayout
import cx.m42.superizer.app.AppWidth
import cx.m42.superizer.runtime.InstanceRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

public data class AssistantState(
    val loading: Boolean = true,
    val status: AgentStatus? = null,
    /** The assistant's name: the panel's `schema.title`, else the declaration's. */
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    /** The reply being written, while [running]. */
    val reply: ChatMessage? = null,
    val running: Boolean = false,
    val error: String? = null,
    val conversations: List<Conversation> = emptyList(),
    val activeId: String? = null,
    /** The list of conversations is open over the chat (the panel's «История»). */
    val listOpen: Boolean = false,
    val meta: AgentMeta? = null,
    val draft: String = "",
    /** A conversation is being created, picked or deleted: nothing is sent until it is done. */
    val busy: Boolean = false,
)

/**
 * One open «Ассистент»: the active conversation of the panel, the reply as it streams, the list of conversations.
 *
 * The state is the panel's: the conversations, the active one and their messages are kept by the server per
 * account, so the phone and the browser of one person are in the same dialog. Nothing is saved here but the draft.
 */
public class AssistantInstance(
    private val runtime: InstanceRuntime,
    private val server: RestoServer,
    private val transportOf: suspend (RestoServer) -> AssistantTransport,
    private val fallbackTitle: String,
    private val onDispose: () -> Unit = {},
) : AppInstance() {

    private val _state = MutableStateFlow(AssistantState(title = fallbackTitle))
    public val state: StateFlow<AssistantState> get() = _state.asStateFlow()

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    /** As wide as the window: the conversations sit beside the chat on a desktop, and the chat keeps its own width. */
    override val layout: StateFlow<AppLayout> = MutableStateFlow(AppLayout(width = AppWidth.Adaptive)).asStateFlow()

    private var transport: AssistantTransport? = null
    private var started = false
    private var runJob: Job? = null
    /** Bumped by every send: a load that began before it must not put the older history over the new message. */
    private var sends = 0

    override suspend fun onLaunch() {
        runtime.scope.launch {
            _state.collect { s -> _chrome.value = AppChrome(title = s.title.ifBlank { null }, subtitle = s.meta?.model, canGoBack = s.listOpen) }
        }
        runtime.analytics.screen("assistant")
    }

    /** From the first frame: the session, the status of the assistant, the active conversation. */
    public fun start() {
        if (started) return
        started = true
        reload()
    }

    public fun reload() {
        runtime.scope.launch { load() }
    }

    private suspend fun ensureTransport(): AssistantTransport = transport ?: transportOf(server).also { transport = it }

    private suspend fun load() {
        val since = sends
        _state.update { it.copy(loading = true, error = null) }
        try {
            val t = ensureTransport()
            val status = t.status()
            _state.update { it.copy(status = status, title = status.title ?: it.title) }
            if (!status.ready) {
                _state.update { it.copy(loading = false) }
                return
            }
            val list = t.conversations()
            val messages = t.history()
            val meta = t.meta()
            _state.update {
                // A message sent meanwhile is newer than this history: keep what is on screen.
                val fresh = since == sends && !it.running
                it.copy(loading = false, messages = if (fresh) messages else it.messages, conversations = list.items, activeId = list.activeId, meta = meta)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            _state.update { it.copy(loading = false, error = describe(e)) }
        }
    }

    public fun edit(text: String) {
        _state.update { it.copy(draft = text) }
    }

    /** Sends the draft (or [text]: a suggestion) as the panel's composer does, and streams the reply. */
    public fun send(text: String = _state.value.draft) {
        val message = text.trim()
        if (message.isEmpty() || _state.value.running || _state.value.busy) return
        sends++
        val user = ChatMessage("local-${_state.value.messages.size}", Role.User, listOf(Part.Text(message)))
        _state.update {
            it.copy(messages = it.messages + user, draft = "", running = true, error = null,
                reply = ChatMessage("reply", Role.Assistant, emptyList()))
        }
        runJob = runtime.scope.launch {
            try {
                ensureTransport().run(message).collect { event -> onEvent(event) }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                _state.update { it.copy(error = describe(e)) }
            } finally {
                finishReply()
            }
        }
    }

    private fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Navigate -> open(event.href)
            is AgentEvent.Failed -> _state.update { it.copy(error = event.error) }
            else -> _state.update { s -> s.copy(reply = s.reply?.let { r -> r.copy(parts = r.parts.apply(event)) }) }
        }
    }

    /** The reply joins the conversation; the server's own copy is read again with the next history. */
    private fun finishReply() {
        _state.update { s ->
            val reply = s.reply?.takeIf { it.parts.isNotEmpty() }
            s.copy(running = false, reply = null, messages = if (reply != null) s.messages + reply.copy(id = "reply-${s.messages.size}") else s.messages)
        }
        runJob = null
        runtime.scope.launch {
            val t = transport ?: return@launch
            runCatching { t.meta() }.getOrNull()?.let { meta -> _state.update { it.copy(meta = meta) } }
            runCatching { t.conversations() }.getOrNull()?.let { list -> _state.update { it.copy(conversations = list.items, activeId = list.activeId) } }
        }
    }

    /** The panel's Stop: the reading stops; the server finishes the run on its own (it has no abort). */
    public fun stop() {
        runJob?.cancel()
    }

    public fun openList(open: Boolean = true) {
        _state.update { it.copy(listOpen = open) }
        if (open) runtime.scope.launch {
            runCatching { ensureTransport().conversations() }
                .onSuccess { list -> _state.update { it.copy(conversations = list.items, activeId = list.activeId) } }
                .onFailure { e -> _state.update { it.copy(error = describe(e)) } }
        }
    }

    public fun newConversation() = act {
        it.newConversation()
        _state.update { s -> s.copy(messages = emptyList(), listOpen = false) }
    }

    public fun select(id: String) = act {
        it.select(id)
        _state.update { s -> s.copy(listOpen = false) }
    }

    public fun delete(id: String) = act { it.delete(id) }

    private fun act(block: suspend (AssistantTransport) -> Unit) {
        if (_state.value.running || _state.value.busy) return
        _state.update { it.copy(busy = true) }
        runtime.scope.launch {
            try {
                val t = ensureTransport()
                block(t)
                load()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                _state.update { it.copy(error = describe(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    public fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** A link of the assistant: a path of the panel opens in the browser on the context's address. */
    public fun open(href: String) {
        val url = when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("/") && server.address.isNotBlank() -> server.address.trimEnd('/') + href
            else -> return
        }
        runtime.navigation.openUrl(url)
    }

    private fun describe(e: Throwable): String = when (e) {
        is MobileApiException -> e.message ?: e.code.toString()
        is AssistantException -> e.message ?: "HTTP ${e.status}"
        else -> e.message ?: e.toString()
    }

    override fun onForeground() {
        if (started && !_state.value.running) reload()
    }

    override fun saveState(): JsonObject = buildJsonObject {
        _state.value.draft.takeIf { it.isNotBlank() }?.let { put("draft", JsonPrimitive(it)) }
    }

    override fun restore(state: JsonObject) {
        val draft = runCatching { state["draft"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return
        _state.update { it.copy(draft = draft) }
    }

    override fun dispose() {
        runJob?.cancel()
        onDispose()
    }

    @Composable
    override fun Content() {
        AssistantScreen(this)
    }
}
