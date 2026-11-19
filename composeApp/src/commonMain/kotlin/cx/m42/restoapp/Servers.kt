package cx.m42.restoapp

import cx.m42.superizer.ContextStatus
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
    /** A Resto Cloud account: its servers ([parent] = this id) are listed under it and kept in step with the cloud. */
    val cloud: Boolean = false,
    /** A server of a cloud account — that account's id. Added and removed by the cloud, not by the person. */
    val parent: String? = null,
    /** The cloud's id of this server (a service of the account). */
    val cloudId: String? = null,
    /** The cloud's status of this server: online, starting, stopped, error… */
    val status: String? = null,
    /** A cloud account: the money left on it (P10), as its `describe` last said — the switcher and the strip show it. */
    val balance: cx.m42.restoapp.protocol.AccountBalance? = null,
    /** A cloud account: the currency of [balance]. */
    val currency: String? = null,
) {
    val isDemo: Boolean get() = address.startsWith(DEMO_SCHEME)

    internal fun toContext(lang: String = "ru"): HostContext {
        val ru = lang.startsWith("ru")
        return HostContext(
            id = id,
            // «Resto Cloud · login» stays the title: it is what tells two accounts apart (CloudServerTest).
            title = name,
            subtitle = when {
                isDemo -> if (ru) "Демо · данные только на этом устройстве" else "Demo · the data stays on this device"
                // «1 840 ₽ · хватит на 42 дня» — what an account is about, once it said it.
                cloud && balance != null -> balanceLine(balance, currency, ru)
                cloud -> (if (ru) "Аккаунт облака · " else "Cloud account · ") + hostOf(address)
                else -> hostOf(address)
            },
            parent = parent,
            status = status?.let { statusOf(it, ru) },
        )
    }

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

/** The cloud's status of a server as the switcher shows it. */
internal fun statusOf(status: String, ru: Boolean): ContextStatus = when (status) {
    "online" -> ContextStatus(if (ru) "работает" else "online", "success")
    "starting" -> ContextStatus(if (ru) "запускается" else "starting", "warning")
    "stopping" -> ContextStatus(if (ru) "останавливается" else "stopping", "warning")
    "stopped" -> ContextStatus(if (ru) "остановлен" else "stopped", "info")
    "error" -> ContextStatus(if (ru) "ошибка" else "error", "danger")
    "deleting" -> ContextStatus(if (ru) "удаляется" else "deleting", "warning")
    else -> ContextStatus(status, null)
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
