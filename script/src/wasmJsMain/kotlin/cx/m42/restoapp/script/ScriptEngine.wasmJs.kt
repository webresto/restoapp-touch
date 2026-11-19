package cx.m42.restoapp.script

/**
 * The web build has no sandboxed engine yet: QuickJS-kt is JVM and native only, and the page's own JavaScript
 * would run the server's bundle with the page's rights (its storage, its tokens). So a script app says it cannot
 * run here — the screen shows that instead of the app — rather than run unconfined.
 */
public actual class ScriptEngine public actual constructor(private val host: ScriptHost) {
    public actual suspend fun load(prelude: String, bundle: String) {
        throw ScriptException("Приложения-скрипты пока не работают в веб-версии")
    }

    public actual suspend fun eval(code: String): String? = throw ScriptException("the engine is not available in the web build")

    public actual fun close() {}
}
