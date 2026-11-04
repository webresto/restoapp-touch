package cx.m42.restoapp.script

import androidx.compose.runtime.Composable
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.render.RestoServer
import cx.m42.superizer.app.AppChrome
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.runtime.InstanceRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One screen of the stack: the route the script entered and the parameters it was given. */
internal data class ScriptFrame(val route: String, val params: JsonObject)

/**
 * One open script app: the stack of routes it pushed, the screen the script last drew, and what went
 * wrong if anything did.
 *
 * The script is never trusted with the flow: a tap goes into JS and what comes back is a screen, and
 * if JS throws, loops forever or asks for a method it may not call, the app shows the failure and
 * stays alive. Going back re-enters the previous route — stage 0 keeps no state of frames behind,
 * which is why `enter` is where a route reads what it needs.
 */
internal class ScriptAppInstance(
    private val runtime: InstanceRuntime,
    internal val server: RestoServer,
    private val source: ScriptSource,
    private val lang: String,
) : AppInstance() {

    private val _screen = MutableStateFlow<ScriptScreen?>(null)
    val screen: StateFlow<ScriptScreen?> get() = _screen.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> get() = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> get() = _notice.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> get() = _busy.asStateFlow()

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    private var script: ScriptRuntime? = null
    private var stack = listOf<ScriptFrame>()
    private var work: Job? = null
    private var started = false

    init {
        runtime.scope.launch { server.notices.collect { _notice.value = it } }
        runtime.analytics.screen("script-app")
    }

    /** From the first frame: the bundle the host downloaded, then the script's own first route. */
    fun start() {
        if (started) return
        started = true
        inScript {
            val app = server.app.value ?: throw ScriptException("the server no longer declares this app")
            val bundle = source.bundle(app)
            val made = ScriptRuntime(
                server = { method, params -> server.call(method, params) },
                scope = runtime.scope,
                lang = lang,
                onToast = { text -> _notice.value = text },
            )
            made.start(bundle)
            script = made
            val home = made.home()
            stack = listOf(ScriptFrame(home, JsonObject(emptyMap())))
            show(made.enter(home))
        }
    }

    /** A handler of the screen: the script changes its state and draws again. */
    fun handle(handler: Int, argument: JsonElement? = null) {
        val running = script ?: return
        inScript { show(running.handle(handler, argument)) }
    }

    /** `act.push`: a route of the script, with its parameters. */
    fun push(action: ScriptAction) {
        val running = script ?: return
        val route = action.route ?: return
        inScript {
            stack = stack + ScriptFrame(route, action.params)
            show(running.enter(route, action.params))
        }
    }

    /** `act.back`, the frame's arrow and the system's back button: the previous route, entered again. */
    fun back() {
        val running = script ?: return
        if (stack.size < 2) return
        inScript {
            stack = stack.dropLast(1)
            val frame = stack.last()
            show(running.enter(frame.route, frame.params))
        }
    }

    /** `act.refresh` and "pull to refresh": the route this screen is on, entered again. */
    fun refresh() {
        val running = script ?: return
        val frame = stack.lastOrNull() ?: return
        inScript { show(running.enter(frame.route, frame.params)) }
    }

    fun perform(action: ScriptAction) {
        when (action.kind) {
            ScriptAction.PUSH -> push(action)
            ScriptAction.BACK -> back()
            ScriptAction.REFRESH -> refresh()
            ScriptAction.TOAST -> action.text?.let { _notice.value = it }
            else -> Unit // A script one version ahead: an action this build has no idea about is ignored.
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun retry() {
        _error.value = null
        if (script == null) {
            started = false
            start()
        } else {
            refresh()
        }
    }

    /** Every entry into the script: one at a time, failures on the screen and never a crash. */
    private fun inScript(block: suspend () -> Unit) {
        work?.cancel()
        work = runtime.scope.launch {
            _busy.value = true
            try {
                block()
                _error.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ScriptException) {
                _error.value = failure.message
            } catch (failure: MobileApiException) {
                _error.value = failure.message
            } finally {
                _busy.value = false
            }
        }
    }

    private fun show(drawn: ScriptScreen) {
        _screen.value = drawn
        _chrome.value = AppChrome(title = drawn.title.takeIf { stack.size > 1 }, canGoBack = stack.size > 1)
        // What the script asked for while it was drawing: performed with the screen already on, so
        // «go back» after deleting a menu lands on a list that is read again.
        for (command in drawn.commands) {
            when (command) {
                ScriptAction.BACK -> back()
                ScriptAction.REFRESH -> refresh()
                else -> Unit
            }
        }
    }

    override fun onClose() {
        script?.close()
        script = null
    }

    @Composable
    override fun Content() {
        ScriptAppContent(this)
    }
}
