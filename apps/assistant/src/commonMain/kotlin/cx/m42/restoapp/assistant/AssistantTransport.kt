package cx.m42.restoapp.assistant

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.time.Clock

/** What the chat needs of an assistant; [AdminizerAgentTransport] is the panel's, [DemoAssistantTransport] canned. */
public interface AssistantTransport {
    public suspend fun status(): AgentStatus
    public suspend fun meta(): AgentMeta?
    public suspend fun conversations(): Conversations
    /** A new, empty conversation becomes the active one (the panel's «Новый диалог»). */
    public suspend fun newConversation()
    /** Makes [id] the active conversation; its messages are then [history]. */
    public suspend fun select(id: String)
    /** Only another than the active one: the panel refuses to delete the conversation it is in. */
    public suspend fun delete(id: String)
    /** The messages of the active conversation. */
    public suspend fun history(): List<ChatMessage>
    /** Sends [message] and streams the run's events; cancelling the collector stops reading, as the panel's Stop. */
    public fun run(message: String): Flow<AgentEvent>
}

/** A refusal of the assistant's routes: [status] is HTTP's (0 — no answer), [message] the panel's `error`. */
public class AssistantException(public val status: Int, message: String, cause: Throwable? = null) : Exception(message, cause) {
    public val signedOut: Boolean get() = status == 401
}

/**
 * adminizer's agent transport (Router.js: `<routePrefix>/api/ai-assistant/agent/:modelId/...`), on the context's
 * [address], as the account the device signed in as: [session] is `assistant.session` of the app, asked again when
 * it is about to run out or a route answers 401 — once per request.
 *
 * adminizer reads the user only from the `adminizer_jwt` cookie and checks a double-submit CSRF token on every
 * POST, so every request carries both cookies and the header; nothing is kept between requests but the session.
 */
public class AdminizerAgentTransport(
    private val address: String,
    private val client: HttpClient,
    private val session: suspend () -> AgentSession,
    /** The person's language: the panel's words (title, hint, placeholder, suggestions) come in it. */
    private val lang: String? = null,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : AssistantTransport {

    private val lock = Mutex()
    private var current: AgentSession? = null
    private val csrf = buildString { repeat(32) { append("0123456789abcdef"[Random.nextInt(16)]) } }

    private suspend fun session(fresh: Boolean = false): AgentSession = lock.withLock {
        val held = current
        if (!fresh && held != null && (held.expiresAt == 0L || held.expiresAt - now() > REFRESH_BEFORE_MS)) return held
        session.invoke().also {
            if (it.base.isBlank() || it.cookieValue.isBlank()) throw AssistantException(0, "assistant.session gave no address or session")
            current = it
        }
    }

    private fun url(s: AgentSession, path: String): String {
        val base = if (s.base.startsWith("http://") || s.base.startsWith("https://")) s.base else address.trimEnd('/') + s.base
        return if (path.startsWith("/")) base.trimEnd('/') + path else path
    }

    private fun HttpRequestBuilder.auth(s: AgentSession) {
        val cookies = buildList {
            add("${s.cookieName}=${s.cookieValue}")
            s.csrfCookie?.let { add("$it=$csrf") }
        }
        header("Cookie", cookies.joinToString("; "))
        s.csrfHeader?.let { header(it, csrf) }
        header("Accept", "application/json")
        lang?.let { header("Accept-Language", it) }
    }

    private suspend fun json(method: HttpMethod, path: String, body: JsonObject? = null): JsonElement {
        var s = session()
        repeat(2) { attempt ->
            val response = try {
                client.request(url(s, path)) {
                    this.method = method
                    auth(s)
                    timeout { requestTimeoutMillis = CALL_TIMEOUT_MS; socketTimeoutMillis = CALL_TIMEOUT_MS }
                    if (body != null) {
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                throw AssistantException(0, t.message ?: "no answer", t)
            }
            val text = response.bodyAsText()
            val status = response.status.value
            if (status == 401 && attempt == 0) {
                s = session(fresh = true)
                return@repeat
            }
            val parsed = runCatching { AgentJson.parseToJsonElement(text) }.getOrNull()
            if (status !in 200..299) throw AssistantException(status, errorOf(parsed) ?: "HTTP $status")
            return parsed ?: throw AssistantException(status, "not JSON: ${text.take(80)}")
        }
        throw AssistantException(401, "Unauthorized")
    }

    override suspend fun status(): AgentStatus = AgentStatus.of(json(HttpMethod.Get, "/status"))

    override suspend fun meta(): AgentMeta? = try {
        AgentMeta.of(json(HttpMethod.Get, "/meta"))
    } catch (e: AssistantException) {
        // 503 «not connected yet» — the status says why.
        if (e.status == 503) null else throw e
    }

    override suspend fun conversations(): Conversations = Conversations.of(json(HttpMethod.Get, "/conversations"))

    override suspend fun newConversation() {
        json(HttpMethod.Post, "/conversations", JsonObject(emptyMap()))
    }

    override suspend fun select(id: String) {
        json(HttpMethod.Post, "/conversations/${encode(id)}/select", JsonObject(emptyMap()))
    }

    override suspend fun delete(id: String) {
        json(HttpMethod.Delete, "/conversations/${encode(id)}")
    }

    override suspend fun history(): List<ChatMessage> =
        chatOf((json(HttpMethod.Get, "/history").obj["messages"] as? JsonArray).orEmpty())

    override fun run(message: String): Flow<AgentEvent> = channelFlow {
        val started = json(HttpMethod.Post, "/runs", JsonObject(mapOf("message" to JsonPrimitive(message))))
        val stream = started.obj.str("stream") ?: started.obj.str("runId")?.let { "/runs/$it/stream" }
            ?: throw AssistantException(0, "the run has no stream")
        // `stream` is a path of the server (`/console/api/…/runs/<id>/stream`), or one of the agent's base.
        var lastId: String? = null
        var reconnects = 0
        while (true) {
            val outcome = readStream(stream, lastId, emit = { send(it) }) { id -> lastId = id; reconnects = 0 }
            when (outcome) {
                StreamEnd.Finished -> return@channelFlow
                StreamEnd.Dropped -> {
                    // The panel keeps a run 10 minutes and replays it after Last-Event-ID.
                    if (++reconnects > MAX_RECONNECTS) {
                        throw AssistantException(0, "the connection to the assistant was lost" + (lastDrop?.let { ": ${it.message ?: it}" } ?: ""), lastDrop)
                    }
                    delay(RECONNECT_DELAY_MS * reconnects)
                }
            }
        }
    }

    private enum class StreamEnd { Finished, Dropped }

    /** Why the last stream broke off: the error a run gives up with. */
    private var lastDrop: Throwable? = null

    /** One connection to the run's stream; Ktor reads the body in a coroutine of its own, so events go by [emit]. */
    private suspend fun readStream(stream: String, lastId: String?, emit: suspend (AgentEvent) -> Unit, seen: (String) -> Unit): StreamEnd {
        val s = session()
        val target = when {
            stream.startsWith("http://") || stream.startsWith("https://") -> stream
            stream.startsWith("/runs/") -> url(s, stream)
            else -> address.trimEnd('/') + stream
        }
        // The agent's `done {result}` is the reply complete: a stream that closes after it closed on purpose.
        var replied = false
        try {
            var finished = false
            client.prepareGet(target) {
                auth(s)
                header("Accept", "text/event-stream")
                lastId?.let { header("Last-Event-ID", it) }
                timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS; socketTimeoutMillis = STREAM_IDLE_MS }
            }.execute { response ->
                if (response.status.value !in 200..299) {
                    val parsed = runCatching { AgentJson.parseToJsonElement(response.bodyAsText()) }.getOrNull()
                    throw AssistantException(response.status.value, errorOf(parsed) ?: "HTTP ${response.status.value}")
                }
                val channel = response.bodyAsChannel()
                val frame = SseFrame()
                while (!finished) {
                    val line = channel.readUTF8Line() ?: break
                    val event = frame.feed(line) ?: continue
                    event.id?.let(seen)
                    if (event.name != "agent") continue
                    val parsed = runCatching { AgentEvent.of(AgentJson.parseToJsonElement(event.data)) }.getOrNull() ?: continue
                    emit(parsed)
                    if (parsed is AgentEvent.AgentDone) replied = true
                    if (parsed is AgentEvent.RunDone || parsed is AgentEvent.Failed) finished = true
                }
            }
            return if (finished || replied) StreamEnd.Finished else StreamEnd.Dropped
        } catch (c: CancellationException) {
            throw c
        } catch (e: AssistantException) {
            throw e
        } catch (t: Throwable) {
            lastDrop = t
            return if (replied) StreamEnd.Finished else StreamEnd.Dropped
        }
    }

    private companion object {
        const val REFRESH_BEFORE_MS = 2 * 60_000L
        const val CALL_TIMEOUT_MS = 30_000L
        /** No frame for this long while a tool works: read again from the last event. */
        const val STREAM_IDLE_MS = 90_000L
        const val MAX_RECONNECTS = 5
        const val RECONNECT_DELAY_MS = 1_000L

        fun errorOf(json: JsonElement?): String? = json?.obj?.let { it.str("error") ?: it.str("message") }

        fun encode(segment: String): String = buildString {
            for (c in segment) if (c.isLetterOrDigit() || c in "-_.~") append(c) else c.toString().encodeToByteArray().forEach {
                append('%'); append("0123456789ABCDEF"[(it.toInt() shr 4) and 15]); append("0123456789ABCDEF"[it.toInt() and 15])
            }
        }
    }
}

/** One SSE frame at a time: `id:`, `event:`, `data:` lines up to an empty line (WHATWG event stream). */
internal class SseFrame {
    data class Event(val id: String?, val name: String, val data: String)

    private var id: String? = null
    private var name: String? = null
    private val data = StringBuilder()
    private var any = false

    /** The event an empty line completes, or null. */
    fun feed(line: String): Event? {
        if (line.isEmpty()) {
            if (!any) return null
            val event = Event(id, name ?: "message", data.toString())
            id = null; name = null; data.clear(); any = false
            return event
        }
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "id" -> { id = value; any = true }
            "event" -> { name = value; any = true }
            "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value); any = true }
        }
        return null
    }
}
