package cx.m42.restoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import cx.m42.superizer.ContextPort
import cx.m42.superizer.HostContext
import cx.m42.superizer.HostScreen
import cx.m42.superizer.LanguageOption
import cx.m42.superizer.Superizer
import cx.m42.superizer.SuperizerContract
import cx.m42.superizer.event.SuperizerEvent
import cx.m42.superizer.host.SuperizerBuilder
import cx.m42.superizer.host.build
import cx.m42.superizer.host.platform.PlatformLifecycle
import cx.m42.superizer.host.platform.currentPlatform
import cx.m42.superizer.runtime.HostInfo
import cx.m42.superizer.runtime.HostLifecycle
import cx.m42.superizer.ui.shell.SuperizerShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** One server, connected: its profile and the Superizer that is its whole app. */
public class Connection internal constructor(
    public val profile: ServerProfile,
    public val superizer: Superizer,
    private val scope: CoroutineScope,
    private val leaving: MutableStateFlow<HostLifecycle>,
) {
    /**
     * Lets go of this server the way a phone lets go of an app: as if it went to the background,
     * so the open app writes its snapshot into this server's storage and comes back when the server
     * does. Then everything this server started is cancelled.
     */
    internal suspend fun leave() {
        coroutineScope {
            val written = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(1_000) { superizer.events.first { it is SuperizerEvent.Background } }
            }
            leaving.value = HostLifecycle.Background
            written.await()
        }
        scope.cancel()
    }
}

/**
 * RestoApp: a list of servers, each its own Superizer (D6).
 *
 * A server is a context — the library's word — and this is the [ContextPort] the drawer's switcher
 * talks to. Switching does not filter one set of apps: it lets go of one Superizer and builds the
 * next on that server's own storage, so Home, the menu, the open app, the language choice and every
 * app's data belong to the server they were made on.
 */
public class RestoAppHost(
    private val scope: CoroutineScope,
    private val store: ServerStore,
    private val debug: Boolean,
    /** How long "checking the connection" pretends to take (D7). Zero in a test. */
    internal val checkDelayMs: Long = 900,
    private val extra: SuperizerBuilder.() -> Unit = {},
) : ContextPort {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val list = MutableStateFlow(load())

    private val _servers = MutableStateFlow(list.value.servers)
    public val servers: StateFlow<List<ServerProfile>> get() = _servers.asStateFlow()

    private val _contexts = MutableStateFlow(list.value.servers.map { it.toContext() })
    override val contexts: StateFlow<List<HostContext>> get() = _contexts.asStateFlow()

    private val _current = MutableStateFlow<HostContext?>(list.value.currentProfile().toContext())
    override val current: StateFlow<HostContext?> get() = _current.asStateFlow()

    override val addScreen: HostScreen = AddServerScreen(this)
    override val manageScreen: HostScreen = ManageServersScreen(this)

    private val _active = MutableStateFlow(connect(list.value.currentProfile(), language = null))

    /** The server in force and its Superizer. The root composable draws whichever this is. */
    public val active: StateFlow<Connection> get() = _active.asStateFlow()

    /** One switch at a time: two taps in a row must not build two Superizers on one storage. */
    private val switching = Mutex()

    override fun title(langTag: String): String = if (langTag.startsWith("ru")) "Серверы" else "Servers"

    override fun previous(): HostContext? = list.value.find(list.value.previous)?.toContext()

    override fun switchTo(id: String) {
        scope.launch { switchNow(id) }
    }

    /** Ctrl/⌘ + 1…9 on the desktop: the n-th server in the person's order. */
    public fun switchToIndex(index: Int) {
        list.value.servers.getOrNull(index)?.let { switchTo(it.id) }
    }

    public suspend fun switchNow(id: String): Unit = switching.withLock {
        val target = list.value.find(id) ?: return@withLock
        val old = _active.value
        if (old.profile.id == id) return@withLock
        val language = old.superizer.settings.langTag.value
        old.leave()
        save(list.value.copy(current = id, previous = old.profile.id))
        _active.value = connect(target, language)
    }

    /** Adds a ready-made demo server with both apps and goes to it, as GitHub goes to a new account. */
    public fun addDemo() {
        val taken = list.value.servers.map { it.id }.toSet()
        val n = generateSequence(2) { it + 1 }.first { "demo-$it" !in taken }
        add(ServerProfile("demo-$n", "Демо-сервер $n", "demo://demo-$n", ServerApps.ids))
    }

    /**
     * Adds a server by address after a pretend check (D7), and goes to it. [onDone] runs once it
     * is in force — or never, if this host is gone first.
     */
    public fun connectNew(name: String, address: String, apps: List<String>, onDone: () -> Unit = {}) {
        scope.launch {
            delay(checkDelayMs)
            val cleanAddress = address.trim()
            val profile = ServerProfile(
                id = uniqueId(slug(cleanAddress)),
                name = name.trim().ifEmpty { hostOf(cleanAddress) },
                address = cleanAddress,
                apps = ServerApps.ids.filter { it in apps },
            )
            save(list.value.copy(servers = list.value.servers + profile))
            switchNow(profile.id)
            onDone()
        }
    }

    public fun rename(id: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        save(list.value.copy(servers = list.value.servers.map { if (it.id == id) it.copy(name = clean) else it }))
    }

    /** Moves a server up ([by] < 0) or down the list, which is also the Ctrl+n order. */
    public fun move(id: String, by: Int) {
        val servers = list.value.servers.toMutableList()
        val from = servers.indexOfFirst { it.id == id }
        val to = (from + by).coerceIn(0, servers.lastIndex)
        if (from < 0 || from == to) return
        servers.add(to, servers.removeAt(from))
        save(list.value.copy(servers = servers))
    }

    /** Forgets a server and everything it kept. Not the one in force: switch away first. */
    public fun remove(id: String) {
        if (id == list.value.current) return
        val next = list.value.copy(
            servers = list.value.servers.filterNot { it.id == id },
            previous = list.value.previous.takeIf { it != id },
        )
        save(next)
        store.erase(id)
    }

    private fun add(profile: ServerProfile) {
        save(list.value.copy(servers = list.value.servers + profile))
        switchTo(profile.id)
    }

    private fun load(): ServerList =
        store.load()
            ?.let { text -> runCatching { json.decodeFromString(ServerList.serializer(), text) }.getOrNull() }
            ?.takeIf { it.servers.isNotEmpty() }
            ?: DemoServers.also { store.save(json.encodeToString(ServerList.serializer(), it)) }

    private fun save(next: ServerList) {
        list.value = next
        store.save(json.encodeToString(ServerList.serializer(), next))
        _servers.value = next.servers
        _contexts.value = next.servers.map { it.toContext() }
        _current.value = next.currentProfile().toContext()
    }

    /** Builds [profile]'s Superizer on its own storage. The previous one must already be gone. */
    private fun connect(profile: ServerProfile, language: String?): Connection {
        store.useStorageOf(profile.id)
        val serverScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val leaving = MutableStateFlow(HostLifecycle.Foreground)
        val lifecycle = combine(PlatformLifecycle.state, leaving) { platform, left ->
            if (left == HostLifecycle.Background) HostLifecycle.Background else platform
        }.stateIn(serverScope, SharingStarted.Eagerly, PlatformLifecycle.state.value)
        val apps = ServerApps.create(profile.apps)

        val superizer = Superizer.build(serverScope) {
            host(
                HostInfo(
                    name = profile.name,
                    version = BuildInfo.VERSION,
                    build = BuildInfo.COMMIT + if (BuildInfo.DIRTY) "*" else "",
                    platform = currentPlatform(),
                    contractVersion = SuperizerContract.VERSION,
                    debug = debug,
                ),
            )
            // `restoapp://app/<id>/<path>` and `restoapp://activate?…` — on the desktop, `--link=`.
            scheme(SCHEME)
            languages(
                LanguageOption("ru", "Русский"),
                LanguageOption("en", "English"),
                // Russian: the people who run these servers read it first.
                fallback = "ru",
            )
            lifecycle(lifecycle)
            contexts(this@RestoAppHost)
            hostSection(ServersSection(this@RestoAppHost))
            apps.forEach { register(it) }
            // A fresh server shows everything it has; after that Home is the person's.
            home(apps.map { it.id })
            extra()
        }
        // The language is the person's, not the server's: it follows them from server to server.
        if (language != null && superizer.settings.langTag.value != language) superizer.settings.chooseLanguage(language)
        return Connection(profile, superizer, serverScope, leaving)
    }

    private fun uniqueId(base: String): String {
        val taken = list.value.servers.map { it.id }.toSet()
        return if (base !in taken) base else generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in taken }
    }
}

/** "https://admin.cafe.ru/x" → "admin.cafe.ru"; what a server is called when nobody named it. */
internal fun hostOf(address: String): String =
    address.substringAfter("://").substringBefore('/').ifEmpty { address }

/** A directory-safe id from an address: "https://admin.cafe.ru" → "admin-cafe-ru". */
internal fun slug(address: String): String =
    hostOf(address).lowercase()
        .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-+"), "-")
        .ifEmpty { "server" }

/** The root composable every entry point calls: the shell of whichever server is in force. */
@Composable
public fun RestoApp(host: RestoAppHost) {
    val connection by host.active.collectAsState()
    // A new key per server: nothing the previous server's shell remembered survives into the next.
    key(connection) {
        SuperizerShell(connection.superizer)
    }
}

public const val SCHEME: String = "restoapp"
