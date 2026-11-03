package spike

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** A host service: (service, method, argsJson) -> resultJson; throw [RpcFailure] to reject. */
fun interface Services {
    suspend fun call(service: String, method: String, argsJson: String): String
}

class RpcFailure(val code: String) : Exception(code)

/**
 * One QuickJS runtime on its own thread. Kotlin owns the event loop: timers and RPC completions
 * come back into JS as separate evaluations, serialized by [mutex], so JS never blocks the UI
 * thread and never sees a Kotlin object.
 */
class JsRuntime(val tree: UiTree, private val services: Services) {
    private val jsThread = Executors.newSingleThreadExecutor { Thread(it, "quickjs").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + jsThread)
    private val mutex = Mutex()
    private val timers = ConcurrentHashMap<Int, Job>()
    private lateinit var qjs: QuickJs

    val errors = mutableListOf<String>()
    val log = mutableListOf<String>()

    suspend fun start(bundle: String) = withContext(jsThread) {
        qjs = QuickJs.create(jsThread)
        qjs.memoryLimit = 32L * 1024 * 1024
        qjs.maxStackSize = 512L * 1024
        qjs.evaluationTimeoutMillis = 250
        qjs.define("__host") {
            function("commit") { args -> tree.apply(args[0] as String) }
            function("scheduleTimer") { args ->
                val id = (args[0] as Number).toInt()
                val ms = (args[1] as Number).toLong()
                timers[id] = scope.launch {
                    delay(ms)
                    timers.remove(id)
                    eval("__fireTimer($id)")
                }
            }
            function("cancelTimer") { args -> timers.remove((args[0] as Number).toInt())?.cancel() }
            function("rpc") { args ->
                val id = (args[0] as Number).toInt()
                val service = args[1] as String
                val method = args[2] as String
                val argsJson = args[3] as String
                scope.launch {
                    val (ok, json) = try {
                        true to services.call(service, method, argsJson)
                    } catch (e: RpcFailure) {
                        false to """{"code":"${e.code}","message":"${e.code}"}"""
                    }
                    eval("__rpcSettle($id, $ok, ${js(json)})")
                }
                Unit // a Job is not a JS value
            }
            function("log") { args -> log += args.joinToString(" ") }
        }
        eval(bundle)
    }

    /** Every entry into JS goes through here: one at a time, on the JS thread. */
    suspend fun eval(code: String): Any? = mutex.withLock {
        withContext(jsThread) {
            try {
                qjs.evaluate<Any?>(code)
            } catch (e: Exception) {
                errors += "${e::class.simpleName}: ${e.message}"
                null
            }
        }
    }

    suspend fun mount(surface: String): Int = (eval("__mount(${js(surface)})") as Number).toInt()

    suspend fun dispatch(nodeId: Int, event: String, payloadJson: String = "null") {
        eval("__dispatch($nodeId, ${js(event)}, ${js(payloadJson)})")
    }

    fun dispatchAsync(nodeId: Int, event: String) {
        scope.launch { dispatch(nodeId, event) }
    }

    suspend fun memoryUsedBytes(): Long = mutex.withLock { withContext(jsThread) { qjs.memoryUsage.memoryUsedSize } }

    fun close() {
        scope.launch { mutex.withLock { qjs.close() } }
    }

    private fun js(s: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(s))
}
