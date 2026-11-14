package cx.m42.restoapp.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * The agent transport of adminizer's AI panel, as its own bundle speaks it (src/assets/js/ai-assistant/agent,
 * runtime.ts): `<base>/status|meta|history|conversations`, `POST <base>/runs` → `{runId, stream}`, and the run's
 * events over SSE. Everything is read leniently — the cloud runs adminizer 5.2, a RestoApp server 5.0, and both
 * add keys of their own.
 */

internal val AgentJson: Json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

/** The answer of `assistant.session`: where the panel's agent is and the cookie that opens it. */
public data class AgentSession(
    /** `adminizer-agent` — the routes of adminizer; `demo` — the canned assistant of the demo server. */
    val transport: String,
    val model: String,
    val title: String,
    /** `<routePrefix>/api/ai-assistant/agent/<model>`, a path of the context's address. */
    val base: String,
    val cookieName: String,
    val cookieValue: String,
    val csrfCookie: String?,
    val csrfHeader: String?,
    /** Epoch millis; 0 — unknown. */
    val expiresAt: Long,
) {
    public companion object {
        public const val ADMINIZER: String = "adminizer-agent"
        public const val DEMO: String = "demo"

        public fun of(json: JsonElement): AgentSession {
            val o = json.obj
            val cookie = o["cookie"].obj
            val csrf = o["csrf"].obj
            return AgentSession(
                transport = o.str("transport") ?: ADMINIZER,
                model = o.str("model") ?: "",
                title = o.str("title") ?: "",
                base = o.str("base") ?: "",
                cookieName = cookie.str("name") ?: "adminizer_jwt",
                cookieValue = cookie.str("value") ?: "",
                csrfCookie = csrf.str("cookie"),
                csrfHeader = csrf.str("header"),
                expiresAt = o.str("expiresAt")?.let(::parseIsoMillis) ?: 0L,
            )
        }
    }
}

/** `GET <base>/status`: whether the assistant is connected, and the words of its panel. */
public data class AgentStatus(
    /** `ready`, `setup_required`, `connecting`, `error`… */
    val state: String,
    val lastError: String? = null,
    val title: String? = null,
    val welcomeHint: String? = null,
    val composerPlaceholder: String? = null,
    val suggestions: List<String> = emptyList(),
    /** The screen the panel shows for this state (`schema.connectionScreens.<state>`). */
    val screenTitle: String? = null,
    val screenText: String? = null,
) {
    val ready: Boolean get() = state == "ready"

    public companion object {
        public fun of(json: JsonElement): AgentStatus {
            val o = json.obj
            val schema = o["schema"].obj
            val state = o.str("state") ?: "error"
            val screen = schema["connectionScreens"].obj[state].obj
            return AgentStatus(
                state = state,
                lastError = o.str("lastError"),
                title = schema.str("title"),
                welcomeHint = schema.str("welcomeHint"),
                composerPlaceholder = schema.str("composerPlaceholder"),
                suggestions = (schema["suggestions"] as? JsonArray).orEmpty().mapNotNull { it.textOrNull() },
                screenTitle = screen.str("title"),
                screenText = screen.str("description"),
            )
        }
    }
}

/** `GET <base>/meta`: the model and what the conversation used. */
public data class AgentMeta(
    val model: String? = null,
    val turns: Int = 0,
    val totalTokens: Long = 0,
    val contextTokens: Long = 0,
    val contextWindow: Long = 0,
) {
    public companion object {
        public fun of(json: JsonElement): AgentMeta {
            val o = json.obj
            return AgentMeta(
                model = o.str("model"),
                turns = o.num("turns")?.toInt() ?: 0,
                totalTokens = o["totalUsage"].obj.num("totalTokens") ?: 0,
                contextTokens = o.num("contextTokens") ?: 0,
                contextWindow = o.num("contextWindow") ?: 0,
            )
        }
    }
}

public data class Conversation(val id: String, val title: String, val updatedAt: String?)

public data class Conversations(val activeId: String?, val items: List<Conversation>) {
    public companion object {
        public fun of(json: JsonElement): Conversations {
            val o = json.obj
            val items = (o["conversations"] as? JsonArray).orEmpty().mapNotNull { c ->
                val co = c.obj
                val id = co.str("id") ?: return@mapNotNull null
                Conversation(id, co.str("title") ?: "", co.str("updatedAt"))
            }
            return Conversations(o.str("activeId"), items)
        }
    }
}

// ------------------------------------------------------------------ messages, as the panel draws them

public enum class Role { User, Assistant }

public sealed interface Part {
    /** [done] — closed by `text.done`: the next delta of the run starts another part. */
    public data class Text(val text: String, val done: Boolean = false) : Part
    public data class Reasoning(val text: String) : Part
    public data class Tool(
        val id: String,
        val name: String,
        val args: JsonElement?,
        val result: JsonElement? = null,
        val isError: Boolean = false,
    ) : Part {
        val finished: Boolean get() = result != null
    }
    /** `📎 name` — a file the person attached, as the panel shows `<attachment name="…">`. */
    public data class Attachment(val name: String) : Part
}

public data class ChatMessage(val id: String, val role: Role, val parts: List<Part>) {
    val text: String get() = parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
}

/**
 * `history` / `conversation.messages` (ai-sdk ModelMessage[]) into what the panel draws (runtime.ts): system
 * messages are not shown, an assistant message and the tool messages after it are one bubble, a tool result is
 * attached to its call by `toolCallId`, and `output: {type, value}` is unwrapped.
 */
public fun chatOf(messages: List<JsonElement>): List<ChatMessage> {
    val out = mutableListOf<ChatMessage>()
    var assistant: MutableList<Part>? = null
    fun flush() {
        assistant?.takeIf { it.isNotEmpty() }?.let { out += ChatMessage("h${out.size}", Role.Assistant, it.toList()) }
        assistant = null
    }
    for (m in messages) {
        val o = m.obj
        // Lower case: the cloud's stored history has `User` where ai-sdk writes `user`.
        when (o.str("role")?.lowercase()) {
            "user" -> {
                flush()
                val parts = contentParts(o["content"]).mapNotNull { p ->
                    when (p.str("type")) {
                        "text" -> {
                            val text = p.str("text") ?: return@mapNotNull null
                            ATTACHMENT.find(text)?.let { Part.Attachment(it.groupValues[1]) } ?: Part.Text(text)
                        }
                        "image", "file" -> Part.Attachment(p.str("name") ?: p.str("filename") ?: "image")
                        else -> null
                    }
                }
                if (parts.isNotEmpty()) out += ChatMessage("h${out.size}", Role.User, parts)
            }
            "assistant" -> {
                val parts = assistant ?: mutableListOf<Part>().also { assistant = it }
                for (p in contentParts(o["content"])) {
                    when (p.str("type")) {
                        "text" -> p.str("text")?.takeIf { it.isNotBlank() }?.let { parts += Part.Text(it) }
                        "reasoning" -> p.str("text")?.takeIf { it.isNotBlank() }?.let { parts += Part.Reasoning(it) }
                        "tool-call" -> parts += Part.Tool(
                            id = p.str("toolCallId") ?: "t${parts.size}",
                            name = p.str("toolName") ?: "tool",
                            args = p["input"] ?: p["args"],
                        )
                    }
                }
            }
            "tool" -> {
                val parts = assistant ?: mutableListOf<Part>().also { assistant = it }
                for (p in contentParts(o["content"])) {
                    if (p.str("type") != "tool-result") continue
                    val id = p.str("toolCallId")
                    val output = unwrapOutput(p["output"] ?: p["result"])
                    val error = p.str("isError") == "true" || (p["output"].obj.str("type")?.startsWith("error") == true)
                    val at = parts.indexOfFirst { it is Part.Tool && it.id == id }
                    if (at >= 0) parts[at] = (parts[at] as Part.Tool).copy(result = output ?: JsonNull, isError = error)
                    else parts += Part.Tool(id ?: "t${parts.size}", p.str("toolName") ?: "tool", null, output ?: JsonNull, error)
                }
            }
        }
    }
    flush()
    return out
}

private val ATTACHMENT = Regex("""^<attachment name="([^"]*)"""")

private fun contentParts(content: JsonElement?): List<JsonObject> = when (content) {
    is JsonPrimitive -> content.contentOrNull?.let { listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(it)))) }.orEmpty()
    is JsonArray -> content.mapNotNull { it as? JsonObject }
    else -> emptyList()
}

private fun unwrapOutput(output: JsonElement?): JsonElement? {
    val o = output as? JsonObject ?: return output
    return if (o.containsKey("type") && o.containsKey("value")) o["value"] else o
}

// ------------------------------------------------------------------ the events of a run

/** One `event: agent` of the run's stream, as runtime.ts reads it; anything else is [Other]. */
public sealed interface AgentEvent {
    public data class TextDelta(val text: String) : AgentEvent
    public data class TextDone(val text: String) : AgentEvent
    public data class ReasoningDelta(val text: String) : AgentEvent
    public data object ReasoningDone : AgentEvent
    public data class ToolStart(val id: String, val name: String, val input: JsonElement?) : AgentEvent
    public data class ToolDone(val id: String, val name: String, val output: JsonElement?) : AgentEvent
    public data class ToolError(val id: String, val name: String, val error: String) : AgentEvent
    /** `ui.method navigate`: the panel opens `href` — a path of the panel. */
    public data class Navigate(val href: String) : AgentEvent
    public data class Usage(val totalTokens: Long) : AgentEvent
    /** The agent's `done {result}`: the reply is complete. */
    public data class AgentDone(val result: String) : AgentEvent
    public data class Failed(val error: String) : AgentEvent
    /** The transport's `done {}`: nothing more comes. */
    public data object RunDone : AgentEvent
    public data class Other(val type: String) : AgentEvent

    public companion object {
        public fun of(data: JsonElement): AgentEvent {
            val o = data.obj
            val type = o.str("type") ?: return Other("")
            fun id() = o.str("toolCallId") ?: ""
            fun name() = o.str("toolName") ?: "tool"
            return when (type) {
                "text.delta" -> TextDelta(o.str("text") ?: "")
                "text.done" -> TextDone(o.str("text") ?: "")
                "reasoning.delta" -> ReasoningDelta(o.str("text") ?: "")
                "reasoning.done" -> ReasoningDone
                "tool.start" -> ToolStart(id(), name(), o["input"])
                "tool.done" -> ToolDone(id(), name(), o["output"])
                "tool.error" -> ToolError(id(), name(), o.str("error") ?: "error")
                "ui.method" -> {
                    val href = o["input"].obj.str("href")
                    if (o.str("action") == "navigate" && href != null) Navigate(href) else Other(type)
                }
                "step.done", "turn.done" -> Usage(o["usage"].obj.num("totalTokens") ?: 0)
                "done" -> o.str("result")?.let { AgentDone(it) } ?: RunDone
                "error" -> Failed(o.str("error") ?: o.str("message") ?: "error")
                else -> Other(type)
            }
        }
    }
}

/**
 * The reply being written: the events of [AgentEvent] applied to the parts of one assistant bubble, the way
 * runtime.ts does — deltas grow the open text part, `text.done` closes it (the next step writes a new one), a tool
 * call is a part of its own that gets its result.
 */
public fun List<Part>.apply(event: AgentEvent): List<Part> {
    val parts = toMutableList()
    val last = parts.lastOrNull()
    when (event) {
        is AgentEvent.TextDelta ->
            if (last is Part.Text && !last.done) parts[parts.lastIndex] = last.copy(text = last.text + event.text)
            else parts += Part.Text(event.text)
        is AgentEvent.TextDone ->
            if (last is Part.Text && !last.done) parts[parts.lastIndex] = Part.Text(event.text.ifEmpty { last.text }, done = true)
            else if (event.text.isNotEmpty()) parts += Part.Text(event.text, done = true)
        is AgentEvent.ReasoningDelta ->
            if (last is Part.Reasoning) parts[parts.lastIndex] = Part.Reasoning(last.text + event.text)
            else parts += Part.Reasoning(event.text)
        is AgentEvent.ToolStart -> parts += Part.Tool(event.id, event.name, event.input)
        is AgentEvent.ToolDone -> parts.replaceTool(event.id, event.name) { it.copy(result = event.output ?: JsonNull) }
        is AgentEvent.ToolError -> parts.replaceTool(event.id, event.name) { it.copy(result = JsonPrimitive(event.error), isError = true) }
        else -> return this
    }
    return parts
}

private fun MutableList<Part>.replaceTool(id: String, name: String, change: (Part.Tool) -> Part.Tool) {
    val at = indexOfFirst { it is Part.Tool && it.id == id }
    if (at >= 0) this[at] = change(this[at] as Part.Tool) else this += change(Part.Tool(id, name, null))
}

// ------------------------------------------------------------------ JSON helpers

internal val JsonElement?.obj: JsonObject get() = this as? JsonObject ?: JsonObject(emptyMap())

internal fun JsonObject.str(key: String): String? = this[key]?.textOrNull()

internal fun JsonObject.num(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }

internal fun JsonElement.textOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let {
    if (it.isString) it.content else it.booleanOrNull?.toString() ?: it.intOrNull?.toString() ?: it.contentOrNull
}

/** `2026-11-14T11:47:01.357Z` → epoch millis, without a date library (commonMain). */
internal fun parseIsoMillis(text: String): Long? {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:?\d{2})?$""").find(text.trim()) ?: return null
    val g = m.groupValues
    val y = g[1].toInt(); val mo = g[2].toInt(); val d = g[3].toInt()
    // Days from the civil date (H. Hinnant's algorithm).
    val yy = if (mo <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val doy = (153 * (mo + (if (mo > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097L + doe - 719468
    val millis = g[7].padEnd(3, '0').take(3).ifEmpty { "0" }.toLong()
    var offset = 0L
    val zone = g[8]
    if (zone.isNotEmpty() && zone != "Z") {
        val sign = if (zone[0] == '-') -1 else 1
        val digits = zone.drop(1).replace(":", "")
        offset = sign * (digits.take(2).toLong() * 3600 + digits.drop(2).toLong() * 60) * 1000
    }
    return ((days * 86400 + g[4].toLong() * 3600 + g[5].toLong() * 60 + g[6].toLong()) * 1000 + millis) - offset
}
