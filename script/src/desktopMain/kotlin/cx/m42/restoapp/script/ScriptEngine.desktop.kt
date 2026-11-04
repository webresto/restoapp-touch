package cx.m42.restoapp.script

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * QuickJS (quickjs-kt), one runtime on one thread of its own — the shape the prototype in
 * `.ai-notes/quick/` was measured with: a tap round trip under a millisecond, a 500-row screen about
 * six, and a runaway loop cut off instead of hanging the app.
 *
 * The limits are deliberate and small: a script is a guest, not part of the app.
 */
public actual class ScriptEngine public actual constructor(private val host: ScriptHost) {
    private val thread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "restoapp-script").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var quickJs: QuickJs? = null

    public actual suspend fun load(prelude: String, bundle: String) {
        withContext(thread) {
            val engine = QuickJs.create(thread)
            engine.memoryLimit = MEMORY_LIMIT
            engine.maxStackSize = STACK_LIMIT
            engine.evaluationTimeoutMillis = TIMEOUT_MS
            engine.define("__host") {
                function("call") { args ->
                    host.call((args[0] as Number).toInt(), args[1] as String, args[2] as String)
                }
                function("ready") { host.ready() }
                function("failed") { args -> host.failed(args.firstOrNull() as? String ?: "failed") }
                function("toast") { args -> host.toast(args.firstOrNull() as? String ?: "") }
                function("log") { args -> host.log(args.joinToString(" ")) }
            }
            quickJs = engine
            evaluate(engine, prelude, "the prelude")
            evaluate(engine, bundle, "the script")
        }
    }

    public actual suspend fun eval(code: String): String? = mutex.withLock {
        withContext(thread) {
            val engine = quickJs ?: throw ScriptException("the engine is closed")
            when (val result = evaluate(engine, code, "a call")) {
                null -> null
                is String -> result
                else -> result.toString()
            }
        }
    }

    public actual fun close() {
        val engine = quickJs ?: return
        quickJs = null
        runCatching { engine.close() }
        thread.close()
    }

    private suspend fun evaluate(engine: QuickJs, code: String, what: String): Any? =
        try {
            engine.evaluate<Any?>(code)
        } catch (failure: Exception) {
            throw ScriptException("$what did not run: ${failure.message}", failure)
        }

    private companion object {
        const val MEMORY_LIMIT = 32L * 1024 * 1024
        const val STACK_LIMIT = 512L * 1024
        const val TIMEOUT_MS = 250L
    }
}
