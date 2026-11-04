package cx.m42.restoapp.protocol

/**
 * Keeping the code of script apps (script-apps.plan.md, D12).
 *
 * The declaration carries a link and a hash ([ScriptRef]); the client downloads the bundle once and
 * keeps it under its hash. A new build is a new hash, so nothing is ever invalidated: the old file
 * simply stops being asked for. If the download fails and a bundle of that app is already kept, that
 * one is used — an app that worked yesterday keeps working without the network.
 */
public interface ScriptCache {
    /** The bundle with this hash, if it was kept. */
    public fun read(sha256: String): String?

    public fun write(sha256: String, body: String)

    /** The last bundle of [app] that loaded, by hash: what to fall back to when a download fails. */
    public fun lastOf(app: String): String?

    public fun remember(app: String, sha256: String)
}

/** The default: this run only. The host replaces it with one that writes into the server's storage. */
public class MemoryScriptCache : ScriptCache {
    private val bodies = mutableMapOf<String, String>()
    private val last = mutableMapOf<String, String>()

    override fun read(sha256: String): String? = bodies[sha256]

    override fun write(sha256: String, body: String) {
        bodies[sha256] = body
    }

    override fun lastOf(app: String): String? = last[app]

    override fun remember(app: String, sha256: String) {
        last[app] = sha256
    }
}

/** A bundle in the client's hands, and where it came from. */
public data class LoadedScript(
    val app: String,
    val sha256: String,
    val body: String,
    /** It was already kept: no request was made. */
    val fromCache: Boolean,
    /** The declared bundle could not be had, so this is the one that worked before. */
    val fallback: Boolean = false,
)

public class ScriptLoader(
    private val api: MobileApi,
    private val cache: ScriptCache = MemoryScriptCache(),
) {
    /**
     * The code of [app] as [ref] names it: from the cache when that hash is already there, otherwise
     * downloaded, checked against the hash and kept.
     *
     * [PROTOCOL_VERSION] is this client; a bundle that asks for a newer one is not run at all — the
     * app shows the "needs a newer version" tile instead, as it does for an unknown native screen.
     */
    public suspend fun load(app: String, token: String, ref: ScriptRef): LoadedScript {
        if (ref.min > PROTOCOL_VERSION) {
            throw MobileApiException(MobileApiException.UPDATE_REQUIRED, "The script of $app needs protocol ${ref.min}, this app $PROTOCOL_VERSION")
        }
        cache.read(ref.sha256)?.let { kept ->
            cache.remember(app, ref.sha256)
            return LoadedScript(app, ref.sha256, kept, fromCache = true)
        }
        return try {
            val body = api.script(token, ref)
            cache.write(ref.sha256, body)
            cache.remember(app, ref.sha256)
            LoadedScript(app, ref.sha256, body, fromCache = false)
        } catch (failure: MobileApiException) {
            val previous = cache.lastOf(app)?.let { sha -> cache.read(sha)?.let { sha to it } }
                ?: throw failure
            LoadedScript(app, previous.first, previous.second, fromCache = true, fallback = true)
        }
    }

    /** What is kept for [app] right now, without asking the server: for a screen opened offline. */
    public fun kept(app: String): LoadedScript? {
        val sha = cache.lastOf(app) ?: return null
        val body = cache.read(sha) ?: return null
        return LoadedScript(app, sha, body, fromCache = true)
    }
}
