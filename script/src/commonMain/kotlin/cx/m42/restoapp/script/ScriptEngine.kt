package cx.m42.restoapp.script

/**
 * The JS engine, as little of it as the runtime needs: evaluate text, and let JS call four functions
 * of the host. QuickJS on the desktop (and on Android and iOS, the same library); the web host runs
 * the same script in the browser and needs no engine at all.
 *
 * Every entry is one at a time and off the UI thread — that is the engine's own business, not the
 * runtime's.
 */
public interface ScriptHost {
    /** `ctx.server.call`: the host calls the method and settles the promise by [id]. */
    public fun call(id: Int, method: String, paramsJson: String)

    /** `enter` or a handler finished: the screen may be rendered again. */
    public fun ready()

    public fun failed(message: String)

    public fun toast(text: String)

    /** `ctx.back()` and `ctx.refresh()`: the stack is the host's, so the script only asks. */
    public fun command(kind: String)

    public fun log(text: String)
}

public expect class ScriptEngine(host: ScriptHost) {
    /** Evaluates the prelude and then the script; throws [ScriptException] if either refuses to load. */
    public suspend fun load(prelude: String, bundle: String)

    /** Evaluates one expression and gives back what it returned as text, if anything. */
    public suspend fun eval(code: String): String?

    public fun close()
}

public class ScriptException(message: String, cause: Throwable? = null) : Exception(message, cause)
