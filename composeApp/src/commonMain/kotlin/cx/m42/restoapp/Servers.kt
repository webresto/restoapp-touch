package cx.m42.restoapp

import cx.m42.restoapp.orders.OrdersApp
import cx.m42.restoapp.stock.StockApp
import cx.m42.superizer.HostContext
import cx.m42.superizer.app.SuperizerApp
import kotlinx.serialization.Serializable

/**
 * A server RestoApp knows about (D6): what the switcher shows and which apps its menu has.
 *
 * Nothing is connected yet (D7): [address] is shown, not dialled, and [apps] is what a real server
 * would report about itself — here the person ticked it when adding the server.
 */
@Serializable
public data class ServerProfile(
    /** Stable, and a directory name: `~/.restoapp/servers/<id>` holds this server's settings. */
    val id: String,
    val name: String,
    val address: String,
    /** App ids, in the order Home shows them on a fresh install. */
    val apps: List<String>,
) {
    internal fun toContext(): HostContext = HostContext(id = id, title = name, subtitle = address)
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
 * Where the server list lives and how a server gets storage of its own — the two things that
 * differ per platform. The desktop's is `DesktopServerStore`: a JSON file and one prefs directory
 * per server.
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
}

/** The apps a server can have. A real server will say which of these it serves (Q1). */
internal object ServerApps {
    private val factories: List<Pair<String, () -> SuperizerApp<*>>> = listOf(
        OrdersApp.ID to { OrdersApp() },
        StockApp.ID to { StockApp() },
    )

    val ids: List<String> = factories.map { it.first }

    fun create(ids: List<String>): List<SuperizerApp<*>> =
        factories.filter { it.first in ids }.map { it.second() }

    fun title(id: String, langTag: String): String =
        create(listOf(id)).firstOrNull()?.metadata?.title?.resolve(langTag) ?: id
}

/**
 * What a fresh install starts with: two demo servers with different menus, so switching shows its
 * point at once.
 */
internal val DemoServers: ServerList = ServerList(
    servers = listOf(
        ServerProfile("demo-restaurant", "Демо-ресторан", "demo://restaurant", listOf(OrdersApp.ID, StockApp.ID)),
        ServerProfile("demo-point", "Демо-точка", "demo://point", listOf(StockApp.ID)),
    ),
    current = "demo-restaurant",
)
