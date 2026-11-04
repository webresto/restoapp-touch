package cx.m42.restoapp.script

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The one way a script reaches its server: the host adds the token and, for an app by points, the point. */
public fun interface ScriptServer {
    public suspend fun call(method: String, params: JsonObject): JsonElement
}

/** A screen as the script returned it: a tree of nodes, with the handlers kept in JS under numbers. */
public data class ScriptScreen(val route: String, val title: String?, val tree: ScriptNode)

@Serializable
public data class ScriptNode(
    val type: String = "",
    val title: String? = null,
    val subtitle: String? = null,
    val value: String? = null,
    val tone: String? = null,
    val children: List<ScriptNode> = emptyList(),
    val items: List<ScriptRow> = emptyList(),
    val empty: String? = null,
    /** An action the host performs itself. */
    val press: ScriptAction? = null,
    /** A handler in JS: `handle(it)` runs it and gives back the new screen. */
    val onPress: Int? = null,
)

@Serializable
public data class ScriptRow(
    val key: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val value: String? = null,
    val tone: String? = null,
    val toggle: Boolean? = null,
    val onToggle: Int? = null,
    val press: ScriptAction? = null,
    val onPress: Int? = null,
    val actions: List<ScriptRowAction> = emptyList(),
)

@Serializable
public data class ScriptRowAction(
    val title: String? = null,
    val tone: String? = null,
    val press: ScriptAction? = null,
    val onPress: Int? = null,
)

/** `act.*`: what the host does without asking JS again. */
@Serializable
public data class ScriptAction(
    @SerialName("do") val kind: String,
    val route: String? = null,
    val params: JsonObject = JsonObject(emptyMap()),
    val text: String? = null,
) {
    public companion object {
        public const val PUSH: String = "push"
        public const val BACK: String = "back"
        public const val TOAST: String = "toast"
        public const val REFRESH: String = "refresh"
    }
}

@Serializable
private data class RenderedScreen(
    val route: String = "",
    val title: String? = null,
    val tree: ScriptNode = ScriptNode(),
    val error: String? = null,
)

/**
 * One script app, running (js-module.plan.md, stage 0).
 *
 * The script is a guest: it never sees the token, the point or the network — `ctx.server.call` is
 * this class calling [server], and nothing else leaves the engine. What the script returns is a
 * screen as data; what the host sends back are the numbers of the handlers it drew.
 *
 * Every entry into JS is serialised: a handler cannot start while `enter` is still waiting for the
 * server, and the screen the host draws is always the one the last finished call produced.
 */
public class ScriptRuntime(
    private val server: ScriptServer,
    private val scope: CoroutineScope,
    private val lang: String = "en",
    /** Messages the script asked to show — `ctx.toast`. */
    public val onToast: (String) -> Unit = {},
    private val onLog: (String) -> Unit = {},
    engine: (ScriptHost) -> ScriptEngine = { ScriptEngine(it) },
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val turns = Mutex()

    /** Completed by `__host.ready()`, failed by `__host.failed(message)`: one per entry into JS. */
    private var turn: CompletableDeferred<Unit>? = null

    private val host = object : ScriptHost {
        override fun call(id: Int, method: String, paramsJson: String) {
            scope.launch {
                var ok = true
                var payload: String
                try {
                    val params: JsonObject = json.parseToJsonElement(paramsJson) as? JsonObject ?: JsonObject(emptyMap())
                    payload = server.call(method, params).toString()
                } catch (failure: Throwable) {
                    ok = false
                    payload = JsonPrimitive(failure.message ?: failure::class.simpleName ?: "failed").toString()
                }
                try {
                    api.eval("__settle($id, $ok, ${jsText(payload)})")
                } catch (failure: Throwable) {
                    turn?.completeExceptionally(ScriptException(failure.message ?: "the script broke", failure))
                }
            }
        }

        override fun ready() {
            turn?.complete(Unit)
        }

        override fun failed(message: String) {
            turn?.completeExceptionally(ScriptException(message))
        }

        override fun toast(text: String) {
            onToast(text)
        }

        override fun log(text: String) {
            onLog(text)
        }
    }

    private val api: ScriptEngine = engine(host)

    /** Runs the bundle. A script that throws while loading never becomes a screen. */
    public suspend fun start(bundle: String) {
        api.load(PRELUDE, bundle)
        api.eval("__setLang(${jsText(lang)})")
    }

    /** Opens a route: `enter` runs, which may call the server, and the screen comes back drawn as data. */
    public suspend fun enter(route: String, params: JsonObject = JsonObject(emptyMap())): ScriptScreen =
        turns.withLock {
            await { api.eval("__enter(${jsText(route)}, ${jsText(params.toString())})") }
            render()
        }

    /** Runs one handler of the screen and gives back the screen as it became. */
    public suspend fun handle(handler: Int, argument: JsonElement? = null): ScriptScreen =
        turns.withLock {
            val argText = argument?.let { jsText(it.toString()) } ?: "null"
            await { api.eval("__handle($handler, $argText)") }
            render()
        }

    public fun close(): Unit = api.close()

    /** Enter JS, then wait for the `ready` the prelude sends when the promise chain settles. */
    private suspend fun await(enter: suspend () -> Unit) {
        val waiting = CompletableDeferred<Unit>()
        turn = waiting
        try {
            enter()
            waiting.await()
        } finally {
            turn = null
        }
    }

    private suspend fun render(): ScriptScreen {
        val text = api.eval("__render()") ?: throw ScriptException("the script drew nothing")
        val screen = json.decodeFromString(RenderedScreen.serializer(), text)
        screen.error?.let { throw ScriptException(it) }
        return ScriptScreen(screen.route, screen.title, screen.tree)
    }
}

/** A JSON string literal: the only way a value goes into an expression the engine evaluates. */
internal fun jsText(value: String): String = JsonPrimitive(value).toString()
