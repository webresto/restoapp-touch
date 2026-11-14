package cx.m42.restoapp

import cx.m42.superizer.HostContext
import kotlinx.serialization.Serializable

/**
 * A server RestoApp knows about (D6): what the switcher shows. Which apps it has is not here any
 * more — the server declares them (plan section 4) and the demo server is one in process.
 */
@Serializable
public data class ServerProfile(
    /** Stable, and a directory name: `~/.restoapp/servers/<id>` holds this server's settings. */
    val id: String,
    val name: String,
    /** `https://admin.cafe.ru` — or `demo://<variant>` for the demo server in process. */
    val address: String,
) {
    val isDemo: Boolean get() = address.startsWith(DEMO_SCHEME)

    internal fun toContext(): HostContext = HostContext(id = id, title = name, subtitle = if (isDemo) null else hostOf(address))

    public companion object {
        public const val DEMO_SCHEME: String = "demo://"

        /**
         * Resto Cloud — the cloud's own mobile API (`layers/app-mobile-api` of cloud-panel): the account's
         * services, their start and stop, the balance. One more server to the app, as many accounts as wanted.
         */
        public const val CLOUD_ADDRESS: String = "https://dev.resto.cloud"

        /** The demo with one point: no point switch, a stock manager's menu. */
        public const val DEMO_POINT: String = "demo://point"
    }
}

/** Every server, the one in force, and the one before it — where a long press goes back to. */
@Serializable
internal data class ServerList(
    val servers: List<ServerProfile>,
    val current: String,
    val previous: String? = null,
) {
    fun currentProfile(): ServerProfile = servers.firstOrNull { it.id == current } ?: servers.first()
    fun find(id: String?): ServerProfile? = servers.firstOrNull { it.id == id }
}

/**
 * Where the server list lives and how a server gets storage of its own — the things that differ
 * per platform. The desktop's is `DesktopServerStore`: a JSON file and one directory per server.
 */
public interface ServerStore {
    /** The list as [save] wrote it, or null on a fresh install. */
    public fun load(): String?

    public fun save(text: String)

    /**
     * Points the platform's prefs at [serverId]'s own storage. Called right before that server's
     * Superizer is built, and only after the previous one is gone: Home, the session, the language
     * and every app's data are per server from here on.
     */
    public fun useStorageOf(serverId: String)

    /** Deletes everything [serverId] kept. The server is already off the list. */
    public fun erase(serverId: String)

    /** A file of [serverId]'s own — its cached declaration, its point, the demo's data. */
    public fun read(serverId: String, name: String): String?

    /**
     * Writes it whole or not at all. [private]: readable by this user only (the device token — plan
     * section 4: a file with mode 600 until a vault seals it).
     */
    public fun write(serverId: String, name: String, text: String, private: Boolean = false)

    public fun delete(serverId: String, name: String)
}

/**
 * What a fresh install starts with: the demo server twice — a restaurant with two points and both
 * apps, and one point seen by its stock manager — so switching shows its point at once.
 */
internal val DemoServers: ServerList = ServerList(
    servers = listOf(
        ServerProfile("demo-restaurant", "Демо-ресторан", "demo://restaurant"),
        ServerProfile("demo-point", "Демо-точка", ServerProfile.DEMO_POINT),
    ),
    current = "demo-restaurant",
)
