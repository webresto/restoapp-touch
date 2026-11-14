package cx.m42.restoapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.demo.DemoRole
import cx.m42.restoapp.demo.DemoStore
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.restoapp.protocol.HttpMobileApi
import cx.m42.restoapp.protocol.MobileApi
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.PowException
import cx.m42.restoapp.protocol.PowSolver
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.restoapp.protocol.ServerAbout
import cx.m42.restoapp.protocol.normalizeAddress
import cx.m42.restoapp.marketing.MarketingScreens
import cx.m42.restoapp.settings.SettingsScreens
import cx.m42.restoapp.system.SystemScreens
import cx.m42.restoapp.render.RestoServer
import cx.m42.restoapp.assistant.AssistantApp
import cx.m42.restoapp.pos.PosApp
import cx.m42.restoapp.script.ScriptApp
import cx.m42.restoapp.render.ServerApp
import cx.m42.superizer.ContextPort
import cx.m42.superizer.HostContext
import cx.m42.superizer.HostScreen
import cx.m42.superizer.LanguageOption
import cx.m42.superizer.Superizer
import cx.m42.superizer.SuperizerContract
import cx.m42.superizer.app.AppId
import cx.m42.superizer.event.SuperizerEvent
import cx.m42.superizer.host.SuperizerBuilder
import cx.m42.superizer.host.SystemClock
import cx.m42.superizer.host.build
import cx.m42.superizer.host.net.KtorNetworkService
import cx.m42.superizer.host.platform.PlatformLifecycle
import cx.m42.superizer.host.platform.currentPlatform
import cx.m42.superizer.runtime.HostInfo
import cx.m42.superizer.runtime.HostLifecycle
import cx.m42.superizer.runtime.NetworkService
import cx.m42.superizer.ui.shell.SuperizerShell
import kotlinx.coroutines.delay
import cx.m42.restoapp.render.ServerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** One server, connected: its profile, its session and the Superizer that is its whole app. */
public class Connection internal constructor(
    public val profile: ServerProfile,
    public val superizer: Superizer,
    internal val session: ServerSession,
    private val scope: CoroutineScope,
    private val leaving: MutableStateFlow<HostLifecycle>,
) {
    /**
     * Lets go of this Superizer the way a phone lets go of an app: as if it went to the background,
     * so the open app writes its snapshot into this server's storage and comes back when the server
     * does. Then everything this Superizer started is cancelled. The session is not: a rebuild of the
     * same server keeps it.
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
 * RestoApp: a list of servers, each its own Superizer (D6), whose apps are what the server declares
 * (D8, plan section 4) — a real RestoApp by its address, or the demo server in process.
 *
 * A server is a context — the library's word — and this is the [ContextPort] the drawer's switcher
 * talks to. Switching lets go of one Superizer and its session and builds the next on that server's
 * own storage. A new declaration that changes the menu rebuilds the same server's Superizer; the
 * open app comes back from its snapshot.
 */
public class RestoAppHost(
    private val scope: CoroutineScope,
    private val store: ServerStore,
    private val debug: Boolean,
    /** What the server's list of devices calls this one. */
    private val device: DeviceInfo = DeviceInfo("RestoApp", "desktop"),
    /** The login CAPTCHA's solver: the JVM's on desktop. */
    private val solver: PowSolver = PowSolver { _, _ -> throw PowException("This platform cannot solve the CAPTCHA yet") },
    /** The one network every server shares; Ktor by default. */
    network: NetworkService? = null,
    private val clock: () -> Long = { SystemClock.now() },
    /** Where «Resto Cloud» of "Подключить сервер" leads; another one for a cloud panel on a laptop. */
    internal val cloudAddress: String = ServerProfile.CLOUD_ADDRESS,
    /** «Сохранить в файл» and «Загрузить из файла» of the sign-ins; none — no «Перенос входов». Android sets it per activity. */
    files: AuthFiles? = null,
    private val extra: SuperizerBuilder.() -> Unit = {},
) : ContextPort {

    private val _files = MutableStateFlow(files)
    internal val filesFlow: StateFlow<AuthFiles?> get() = _files.asStateFlow()

    /** The platform's file dialogs: Android's are its activity's, so a new activity sets its own. */
    public var files: AuthFiles?
        get() = _files.value
        set(value) {
            _files.value = value
        }

    private val network: NetworkService by lazy { network ?: KtorNetworkService(KtorNetworkService.defaultClient()) }

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val list = MutableStateFlow(load())

    private val _servers = MutableStateFlow(list.value.servers)
    public val servers: StateFlow<List<ServerProfile>> get() = _servers.asStateFlow()

    /** The language of the switcher's words (statuses): the person's, followed from the Superizer in force. */
    private var lang: String = "ru"

    private val _contexts = MutableStateFlow(list.value.servers.map { it.toContext() })
    override val contexts: StateFlow<List<HostContext>> get() = _contexts.asStateFlow()

    private val _current = MutableStateFlow<HostContext?>(list.value.currentProfile().toContext())
    override val current: StateFlow<HostContext?> get() = _current.asStateFlow()

    override val addScreen: HostScreen = AddServerScreen(this, cloudAccount = false)

    /** «Подключить сервер» — a RestoApp by its address or the demo; «Подключить облачный аккаунт» — Resto Cloud. */
    override val addScreens: List<HostScreen> = listOf(addScreen, AddServerScreen(this, cloudAccount = true))

    /** Why a server of a cloud account could not be signed in to, by server id: the Home banner says it. */
    private val _cloudProblems = MutableStateFlow<Map<String, String>>(emptyMap())
    internal val cloudProblems: StateFlow<Map<String, String>> get() = _cloudProblems.asStateFlow()

    /** One reconciliation of the list at a time: two syncs at once would add a server twice. */
    private val listing = Mutex()
    override val manageScreen: HostScreen = ManageServersScreen(this)

    /** One switch or rebuild at a time: two in a row must not build two Superizers on one storage. */
    private val switching = Mutex()

    private val _active = MutableStateFlow(connect(list.value.currentProfile(), language = null, session = null))

    /** The server in force and its Superizer. The root composable draws whichever this is. */
    public val active: StateFlow<Connection> get() = _active.asStateFlow()

    init {
        // The screens this build has for one *action* of a declared app: the editor of a promotion
        // (`marketing.plan.md`, Q43). A server asking for one this build has not got loses the button
        // and nothing else, which is the contract of `control: {type: "native"}`.
        MarketingScreens.register()
        // And the two of «Настройки»: the editor of a value by the schema it came with, and the transfer.
        SettingsScreens.register()
        SystemScreens.register()
        // Only now: what the server says may rebuild the Superizer, and that needs `active`.
        _active.value.session.refreshInBackground()
        // The servers of every cloud account, now and then every minute: one started in the cloud shows up by itself.
        scope.launch {
            while (true) {
                syncAllCloud()
                delay(CLOUD_SYNC_MS)
            }
        }
        signInToCloudServerIfNeeded(_active.value)
    }

    override fun title(langTag: String): String = if (langTag.startsWith("ru")) "Серверы" else "Servers"

    override fun previous(): HostContext? = list.value.find(list.value.previous)?.toContext(lang)

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
        old.session.close()
        save(list.value.copy(current = id, previous = old.profile.id))
        _active.value = connect(target, language, session = null)
        _active.value.session.refreshInBackground()
        signInToCloudServerIfNeeded(_active.value)
        scope.launch { syncAllCloud() }
    }

    // ------------------------------------------------------------------ cloud accounts and their servers

    /** Brings the servers of every cloud account on the list in step with the cloud. */
    public suspend fun syncAllCloud() {
        list.value.servers.filter { it.cloud }.forEach { account -> runCatching { syncCloud(account.id) } }
    }

    /**
     * The servers of one cloud account, all of them, right under it: new ones added, gone ones removed with what they
     * kept, names, addresses and statuses refreshed. The person does not pick them — an account brings its servers.
     */
    public suspend fun syncCloud(accountId: String) {
        val account = list.value.find(accountId)?.takeIf { it.cloud } ?: return
        val token = storedToken(accountId) ?: return
        val servers = HttpMobileApi(account.address, network).cloudServers(token)
        listing.withLock {
            val now = list.value
            val existing = now.servers.filter { it.parent == accountId }.associateBy { it.cloudId }
            val children = servers.map { server ->
                val known = existing[server.id]
                ServerProfile(
                    id = known?.id ?: childId(accountId, server.id, now),
                    name = server.title,
                    address = server.url ?: known?.address ?: "${account.address}/#${server.id}",
                    parent = accountId,
                    cloudId = server.id,
                    status = server.status,
                )
            }.sortedBy { it.name.lowercase() }
            val keep = children.map { it.id }.toSet()
            val gone = now.servers.filter { it.parent == accountId && it.id !in keep && it.id != now.current }
            // Every account, then its servers; servers of an account that is gone go with it.
            val ordered = buildList {
                now.servers.filter { it.parent == null }.forEach { profile ->
                    add(profile)
                    if (profile.id == accountId) addAll(children)
                    else addAll(now.servers.filter { it.parent == profile.id })
                }
                // The one in force that the cloud no longer has stays until the person leaves it.
                now.servers.firstOrNull { it.id == now.current && it.parent == accountId && it.id !in keep }?.let { add(it) }
            }
            if (ordered != now.servers) save(now.copy(servers = ordered))
            gone.forEach { store.erase(it.id) }
        }
    }

    /**
     * Signs in to a server of a cloud account: the cloud gives its address and its administrator, the server's own
     * mobile API takes them (with its CAPTCHA) and gives this device a token of its own. A failure is kept for the
     * Home banner, which offers to try again.
     */
    internal suspend fun connectCloudServer(serverId: String) {
        val connection = _active.value.takeIf { it.profile.id == serverId } ?: return
        val profile = connection.profile
        val account = profile.parent?.let { list.value.find(it) } ?: return
        val cloudId = profile.cloudId ?: return
        _cloudProblems.value = _cloudProblems.value - serverId
        try {
            val token = storedToken(account.id) ?: throw MobileApiException(MobileApiException.UNAUTHORIZED, "The cloud account is signed out")
            val access = HttpMobileApi(account.address, network).cloudServerAccess(token, cloudId)
            if (access.url.trimEnd('/') != profile.address.trimEnd('/')) {
                // The cloud moved it: the session is bound to the old address, so build the server again.
                listing.withLock { save(list.value.copy(servers = list.value.servers.map { if (it.id == serverId) it.copy(address = access.url) else it })) }
                rebuildFresh(serverId)
            }
            _active.value.takeIf { it.profile.id == serverId }?.session?.signIn(access.login, access.password) {}
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _cloudProblems.value = _cloudProblems.value + (serverId to HostWords(lang).cloudServerFailure(failure))
        }
    }

    private fun signInToCloudServerIfNeeded(connection: Connection) {
        if (connection.profile.parent != null && connection.session.status.value == ServerStatus.SignedOut) {
            scope.launch { connectCloudServer(connection.profile.id) }
        }
    }

    /** The server again, on a new session: its address changed. */
    private suspend fun rebuildFresh(serverId: String): Unit = switching.withLock {
        val old = _active.value
        if (old.profile.id != serverId) return@withLock
        val language = old.superizer.settings.langTag.value
        old.leave()
        old.session.close()
        _active.value = connect(list.value.find(serverId) ?: return@withLock, language, session = null)
    }

    private fun storedToken(serverId: String): String? = storedSession(serverId)?.token

    private fun childId(accountId: String, cloudId: String, now: ServerList): String {
        val base = "$accountId--${cloudId.take(8)}"
        val taken = now.servers.map { it.id }.toSet()
        return if (base !in taken) base else generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in taken }
    }

    /** Builds the server in force again on the same session — its menu changed. */
    private suspend fun rebuild(serverId: String): Unit = switching.withLock {
        val old = _active.value
        if (old.profile.id != serverId) return@withLock
        val language = old.superizer.settings.langTag.value
        old.leave()
        _active.value = connect(old.profile, language, old.session)
    }

    /** Adds one more demo server and goes to it, as GitHub goes to a new account. */
    public fun addDemo() {
        val taken = list.value.servers.map { it.id }.toSet()
        val n = generateSequence(2) { it + 1 }.first { "demo-$it" !in taken }
        add(ServerProfile("demo-$n", "Демо-сервер $n", "demo://demo-$n"))
    }

    /** A server at this address is on the list already — under another account, most likely. */
    internal fun hasServerAt(base: String): Boolean = list.value.servers.any { it.address.trimEnd('/') == base.trimEnd('/') }

    /** "Is there a RestoApp at this address?" — `server.about`, before anyone signs in. */
    internal suspend fun check(typed: String): Pair<String, ServerAbout> {
        val base = normalizeAddress(typed) ?: throw MobileApiException(MobileApiException.PROTOCOL, "Not an address")
        return base to HttpMobileApi(base, network).about()
    }

    /**
     * Signs in to the server at [base] and adds it, already signed in and with its declaration, then
     * goes to it. Throws what the sign-in throws; nothing is added then.
     */
    internal suspend fun addServer(base: String, name: String, login: String, password: String, cloud: Boolean = false, progress: (Float) -> Unit) {
        val api = HttpMobileApi(base, network)
        val result = signInTo(api, solver, login, password, device, progress)
        val profile = ServerProfile(id = uniqueId(slug(base)), name = name.trim().ifEmpty { hostOf(base) }, address = base, cloud = cloud)
        store.write(profile.id, ServerSession.SESSION, ProtocolJson.encodeToString(StoredSession.serializer(), StoredSession(result.token, result.user.login)), private = true)
        save(list.value.copy(servers = list.value.servers + profile))
        // A cloud account brings its servers at once, before the switch: the switcher shows them under it.
        if (cloud) runCatching { syncCloud(profile.id) }
        // On the host's scope: the screen that asked goes away with the Superizer it was drawn in.
        scope.launch { switchNow(profile.id) }.join()
    }

    /**
     * Signs up at [base] (Resto Cloud with registration open) and adds the new account, signed in, as [addServer]
     * does. The CAPTCHA is the sign-in's: the same task, solved here.
     */
    internal suspend fun registerServer(
        base: String,
        name: String,
        login: String,
        fullName: String,
        email: String?,
        password: String,
        progress: (Float) -> Unit,
    ) {
        val api = HttpMobileApi(base, network)
        val lang = _active.value.superizer.settings.langTag.value
        // A task that went stale while the person typed is answered `captcha_invalid`: one more, then give up.
        suspend fun attempt() = api.register(
            login.trim(), fullName.trim(), email?.trim(), password,
            api.challenge()?.let { task -> solver.solve(task, progress) }, device, lang,
        )
        val result = try {
            attempt()
        } catch (failure: MobileApiException) {
            if (failure.code != MobileApiException.CAPTCHA_INVALID) throw failure
            attempt()
        }
        val profile = ServerProfile(id = uniqueId(slug(base)), name = name.trim().ifEmpty { hostOf(base) }, address = base, cloud = true)
        store.write(profile.id, ServerSession.SESSION, ProtocolJson.encodeToString(StoredSession.serializer(), StoredSession(result.token, result.user.login)), private = true)
        save(list.value.copy(servers = list.value.servers + profile))
        runCatching { syncCloud(profile.id) }
        scope.launch { switchNow(profile.id) }.join()
    }

    public fun rename(id: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        save(list.value.copy(servers = list.value.servers.map { if (it.id == id) it.copy(name = clean) else it }))
    }

    /** Moves a server up ([by] < 0) or down the list, which is also the Ctrl+n order. */
    public fun move(id: String, by: Int) {
        // A cloud account moves with its servers; the servers themselves keep the cloud's order under it.
        val all = list.value.servers
        val groups = all.filter { it.parent == null }.map { top -> listOf(top) + all.filter { it.parent == top.id } }.toMutableList()
        val from = groups.indexOfFirst { it.first().id == id }
        val to = (from + by).coerceIn(0, groups.lastIndex)
        if (from < 0 || from == to) return
        groups.add(to, groups.removeAt(from))
        save(list.value.copy(servers = groups.flatten()))
    }

    /** Forgets a server and everything it kept. Not the one in force: switch away first. */
    public fun remove(id: String) {
        val now = list.value
        // A cloud account goes with its servers; none of them may be the one in force.
        val leaving = setOf(id) + now.servers.filter { it.parent == id }.map { it.id }
        if (now.current in leaving) return
        val next = now.copy(
            servers = now.servers.filterNot { it.id in leaving },
            previous = now.previous.takeIf { it !in leaving },
        )
        save(next)
        leaving.forEach { store.erase(it) }
    }

    // ------------------------------------------------------------------ the file of sign-ins

    /** What a file made now would carry: every server but the demo ones. */
    internal fun transferable(): List<ServerProfile> = list.value.servers.filter { !it.isDemo }

    /**
     * Every server but the demo ones, with the token it is signed in by, sealed by [password] ([AuthFile]). A copy:
     * this device stays signed in, and the other one is, to the servers, this same device.
     */
    internal suspend fun exportAuth(password: String): ByteArray {
        val entries = transferable().map { profile ->
            val session = storedSession(profile.id)
            AuthEntry(
                id = profile.id,
                name = profile.name,
                address = profile.address,
                cloud = profile.cloud,
                parent = profile.parent,
                cloudId = profile.cloudId,
                login = session?.login,
                token = session?.token,
            )
        }
        return withContext(Dispatchers.Default) { AuthFile.seal(AuthBundle(entries), password) }
    }

    /** What a file brought: servers new to the list, servers on it that were signed out and now are not, and the rest. */
    internal data class ImportReport(val added: Int, val signedIn: Int, val unchanged: Int)

    /**
     * The servers of a file of sign-ins, onto the list (nothing is switched to). A server already here under the same
     * login stays as it is; one here signed out takes the file's token; the rest are added, a cloud account's servers
     * under it. Throws [AuthFileException] for a wrong password or a file that is not one.
     */
    internal suspend fun importAuth(bytes: ByteArray, password: String): ImportReport {
        val bundle = withContext(Dispatchers.Default) { AuthFile.open(bytes, password) }
        var added = 0
        var signedIn = 0
        var unchanged = 0
        var activeSignedIn = false
        listing.withLock {
            val now = list.value
            val servers = now.servers.toMutableList()
            val taken = servers.map { it.id }.toMutableSet()
            fun unique(base: String): String =
                if (base !in taken) base else generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in taken }
            // The file's id of a server → its id here, so that a cloud account's servers find their account.
            val local = mutableMapOf<String, String>()
            val (accounts, children) = bundle.servers.filter { !it.address.startsWith(ServerProfile.DEMO_SCHEME) }.partition { it.parent == null }
            for (entry in accounts + children) {
                // A server of an account the file has not got: skipped, the account brings its servers itself.
                val parent = if (entry.parent == null) null else (local[entry.parent] ?: continue)
                val existing = servers.firstOrNull { server ->
                    if (parent != null) {
                        server.parent == parent && server.cloudId == entry.cloudId
                    } else {
                        server.parent == null && server.cloud == entry.cloud &&
                            server.address.trimEnd('/') == entry.address.trimEnd('/') &&
                            storedSession(server.id).let { it?.login == entry.login || (it == null && server.name == entry.name) }
                    }
                }
                if (existing != null) {
                    local[entry.id] = existing.id
                    if (entry.token != null && entry.login != null && storedSession(existing.id) == null) {
                        writeSession(existing.id, entry.token, entry.login)
                        signedIn++
                        if (existing.id == now.current) activeSignedIn = true
                    } else {
                        unchanged++
                    }
                    continue
                }
                val id = unique(if (parent != null) "$parent--${entry.cloudId.orEmpty().take(8)}" else slug(entry.address))
                taken += id
                local[entry.id] = id
                if (entry.token != null && entry.login != null) writeSession(id, entry.token, entry.login)
                val profile = ServerProfile(id = id, name = entry.name, address = entry.address, cloud = entry.cloud, parent = parent, cloudId = entry.cloudId)
                // A cloud account's server right after the account's last one.
                val at = if (parent == null) servers.size else servers.indexOfLast { it.id == parent || it.parent == parent } + 1
                servers.add(at, profile)
                added++
            }
            if (servers != now.servers) save(now.copy(servers = servers))
        }
        // The server in force was signed out: a session on the token the file brought.
        if (activeSignedIn) {
            rebuildFresh(list.value.current)
            _active.value.session.refreshInBackground()
        }
        scope.launch { syncAllCloud() }
        return ImportReport(added, signedIn, unchanged)
    }

    private fun storedSession(serverId: String): StoredSession? =
        store.read(serverId, ServerSession.SESSION)
            ?.let { runCatching { ProtocolJson.decodeFromString(StoredSession.serializer(), it) }.getOrNull() }

    private fun writeSession(serverId: String, token: String, login: String) {
        store.write(serverId, ServerSession.SESSION, ProtocolJson.encodeToString(StoredSession.serializer(), StoredSession(token, login)), private = true)
    }

    /** The demo's role switch: the declaration changes, and with it the menu. */
    internal fun setDemoRole(role: DemoRole) {
        val connection = _active.value
        val demo = connection.session.api as? DemoBackend ?: return
        scope.launch {
            demo.setRole(role)
            connection.session.refresh()
        }
    }

    internal fun resetDemo() {
        val connection = _active.value
        val demo = connection.session.api as? DemoBackend ?: return
        scope.launch {
            demo.reset()
            rebuild(connection.profile.id)
        }
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
        _contexts.value = next.servers.map { it.toContext(lang) }
        _current.value = next.currentProfile().toContext(lang)
    }

    private fun apiOf(profile: ServerProfile): MobileApi =
        if (!profile.isDemo) {
            HttpMobileApi(profile.address, network)
        } else {
            DemoBackend(
                store = object : DemoStore {
                    override suspend fun read(): String? = store.read(profile.id, ServerSession.DEMO)
                    override suspend fun write(text: String) = store.write(profile.id, ServerSession.DEMO, text)
                },
                places = if (profile.address == ServerProfile.DEMO_POINT) DemoBackend.ONE_POINT else DemoBackend.TWO_POINTS,
                clock = clock,
                serverName = profile.name,
                initialRole = if (profile.address == ServerProfile.DEMO_POINT) DemoRole.Stock else DemoRole.Admin,
            )
        }

    /**
     * Builds [profile]'s Superizer on its own storage, on [session] or a new one. The previous
     * Superizer must already be gone.
     */
    private fun connect(profile: ServerProfile, language: String?, session: ServerSession?): Connection {
        store.useStorageOf(profile.id)
        val serverScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val live = session ?: ServerSession(
            profile = profile,
            api = apiOf(profile),
            store = store,
            scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])),
            device = device,
            solver = solver,
            lang = language ?: "ru",
            afterMutation = { if (profile.cloud) scope.launch { runCatching { syncCloud(profile.id) } } },
        )
        val leaving = MutableStateFlow(HostLifecycle.Foreground)
        val lifecycle = combine(PlatformLifecycle.state, leaving) { platform, left ->
            if (left == HostLifecycle.Background) HostLifecycle.Background else platform
        }.stateIn(serverScope, SharingStarted.Eagerly, PlatformLifecycle.state.value)
        val declaration = live.declaration.value
        val built = shellOf(declaration)
        // Each declared app gets the class that can draw it: the POS its own (order-entry.plan.md),
        // an app that comes as JS the script runtime (js-module.plan.md), everything else the
        // universal renderer. An app asking for a native screen or a script version this build does
        // not have falls back to the renderer, which shows the «needs a newer version» tile.
        val apps = declaration?.apps.orEmpty().filter(ServerApp::canShow).map { app ->
            when {
                PosApp.canDraw(app) -> PosApp(app, live.lang)
                // The assistant of this context's panel — the cloud's or the server's, the same chat.
                AssistantApp.canDraw(app) -> AssistantApp(app, live.lang)
                ScriptApp.canRun(app) -> ScriptApp(app, live.lang) { declared -> live.script(declared) }
                else -> ServerApp(app, live.lang)
            }
        }

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
            network(network)
            contexts(this@RestoAppHost)
            hostSection(ServersSection(this@RestoAppHost))
            homeBanner(SessionBanner(this@RestoAppHost))
            // The network is the host's: each app gets its server bound to itself (plan section 4).
            service(RestoServer.Key) { caller: AppId -> live.bind(caller.value) }
            apps.forEach { register(it) }
            // A fresh server shows everything it has; after that Home is the person's.
            home(apps.map { it.id })
            extra()
        }
        // The language is the person's, not the server's: it follows them from server to server.
        if (language != null && superizer.settings.langTag.value != language) superizer.settings.chooseLanguage(language)
        val connection = Connection(profile, superizer, live, serverScope, leaving)

        serverScope.launch {
            superizer.settings.langTag.collect {
                live.changeLanguage(it)
                if (it != lang) {
                    lang = it
                    _contexts.value = list.value.servers.map { profile -> profile.toContext(lang) }
                    _current.value = list.value.currentProfile().toContext(lang)
                }
            }
        }
        serverScope.launch {
            // Another menu — apps granted or taken away, a title in another language — is another
            // Superizer; anything else in the declaration the open screens follow by themselves.
            live.declaration.map(::shellOf).distinctUntilChanged().drop(1).collect { shell ->
                // On the host's scope: the rebuild cancels this one.
                if (shell != built) scope.launch { rebuild(profile.id) }
            }
        }
        return connection
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
    // A new key per Superizer: nothing the previous one's shell remembered survives into the next.
    key(connection) {
        SuperizerShell(connection.superizer, swipeBack = true)
    }
}

public const val SCHEME: String = "restoapp"

/** How often the servers of the cloud accounts are re-read. */
internal const val CLOUD_SYNC_MS: Long = 60_000
