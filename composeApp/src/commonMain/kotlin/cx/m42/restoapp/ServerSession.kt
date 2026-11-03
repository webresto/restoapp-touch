package cx.m42.restoapp

import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.protocol.Declaration
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.restoapp.protocol.LoginResult
import cx.m42.restoapp.protocol.MobileApi
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.protocol.PowException
import cx.m42.restoapp.protocol.PowSolver
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.restoapp.render.RestoServer
import cx.m42.restoapp.render.ServerApp
import cx.m42.restoapp.render.ServerStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The device token and who it is for: `session.json`, readable by this user only. */
@Serializable
internal data class StoredSession(val token: String, val login: String)

/** The declaration as last read, and in which language: the menu without a network. */
@Serializable
internal data class StoredDeclaration(val lang: String, val declaration: Declaration)

/** What one server remembers besides: the point last picked (plan section 4, one per server). */
@Serializable
internal data class StoredPlace(val place: String? = null)

/**
 * One server, connected: its API, the token, the declaration and the point (plan section 4).
 *
 * Outlives the Superizer it feeds — a new revision that changes the menu rebuilds the Superizer, not
 * this — and is let go of when the person switches to another server. All network goes through it:
 * an app reaches its server through [bind], which only lets that app's own methods through and adds
 * the token and the point itself.
 */
internal class ServerSession(
    val profile: ServerProfile,
    val api: MobileApi,
    private val store: ServerStore,
    private val scope: CoroutineScope,
    private val device: DeviceInfo,
    private val solver: PowSolver,
    lang: String,
) {
    /** The language declarations are asked in: the person's, set by the host when they change it. */
    var lang: String = lang
        private set

    private val words get() = HostWords(lang)

    private var stored: StoredSession? = if (api is DemoBackend) StoredSession(DemoBackend.TOKEN, "demo") else read(SESSION, StoredSession.serializer())

    private val _login = MutableStateFlow(stored?.login)
    val login: StateFlow<String?> get() = _login.asStateFlow()

    private val _declaration = MutableStateFlow(read(DECLARATION, StoredDeclaration.serializer())?.declaration?.takeIf { stored != null })
    val declaration: StateFlow<Declaration?> get() = _declaration.asStateFlow()

    private val _status = MutableStateFlow(if (stored == null) ServerStatus.SignedOut else ServerStatus.Online)
    val status: StateFlow<ServerStatus> get() = _status.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** The point last picked by hand, on any app of this server. */
    private val lastPlace = MutableStateFlow(read(PLACE, StoredPlace.serializer())?.place)

    private val refreshing = Mutex()
    private val bound = mutableMapOf<String, Bound>()

    val signedIn: Boolean get() = stored != null

    /** Reads the declaration again. False when it could not — the status says why. */
    suspend fun refresh(): Boolean = refreshing.withLock {
        val token = stored?.token ?: return@withLock false
        try {
            val answer = api.describe(token, lang)
            _declaration.value = answer.value
            write(DECLARATION, StoredDeclaration.serializer(), StoredDeclaration(lang, answer.value))
            _status.value = ServerStatus.Online
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: MobileApiException) {
            noteFailure(failure)
            false
        }
    }

    fun refreshInBackground() {
        scope.launch { refresh() }
    }

    /** The person changed the language: titles come from the server, so ask it again. */
    fun changeLanguage(next: String) {
        if (next == lang) return
        lang = next
        refreshInBackground()
    }

    /**
     * Signs in (plan section 2): a CAPTCHA task, solved here, then the login. A task that went stale
     * while it was being solved is asked again once. [progress] gets the solver's share, 0 to 1.
     */
    suspend fun signIn(login: String, password: String, progress: (Float) -> Unit) {
        val result = signInTo(api, solver, login, password, device, progress)
        stored = StoredSession(result.token, result.user.login)
        store.write(profile.id, SESSION, ProtocolJson.encodeToString(StoredSession.serializer(), stored!!), private = true)
        _login.value = result.user.login
        _status.value = ServerStatus.Online
        refresh()
    }

    /** Revokes this device on the server, when it answers, and forgets the token either way. */
    suspend fun signOut() {
        val token = stored?.token
        forget(clearMenu = true)
        if (token != null && api !is DemoBackend) runCatching { api.logout(token) }
    }

    private fun forget(clearMenu: Boolean) {
        if (api is DemoBackend) return
        stored = null
        store.delete(profile.id, SESSION)
        _status.value = ServerStatus.SignedOut
        if (clearMenu) {
            store.delete(profile.id, DECLARATION)
            _declaration.value = null
        }
    }

    private fun noteFailure(failure: MobileApiException) {
        when (failure.code) {
            MobileApiException.NETWORK -> _status.value = ServerStatus.Offline
            // The server no longer knows the device: the menu stays, the apps say "sign in again".
            MobileApiException.UNAUTHORIZED -> forget(clearMenu = false)
            MobileApiException.UPDATE_REQUIRED -> _status.value = ServerStatus.UpdateRequired
        }
    }

    /**
     * One method of [appId]: refused here unless the declaration gives it to that app; the token and,
     * for an app by points, the point are added here. What the answer says about the declaration is
     * acted on before the app hears of it.
     */
    suspend fun call(appId: String, method: String, params: JsonObject): JsonElement {
        val declaration = _declaration.value ?: throw MobileApiException(MobileApiException.UNAVAILABLE, "No declaration yet")
        val app = declaration.app(appId)?.takeIf { it.method(method) != null }
            ?: throw MobileApiException(MobileApiException.FORBIDDEN, "$method is not a method of $appId here")
        val token = stored?.token ?: throw MobileApiException(MobileApiException.UNAUTHORIZED, "Not signed in")
        val place = if (app.byPlace) {
            bind(appId).place.value ?: throw MobileApiException(MobileApiException.PLACE_FORBIDDEN, "No point of $appId")
        } else {
            null
        }
        try {
            val answer = api.call(token, method, params, place?.id, lang)
            if (_status.value != ServerStatus.Online) _status.value = ServerStatus.Online
            if (answer.revision != null && answer.revision != declaration.revision) refreshInBackground()
            return answer.value
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: MobileApiException) {
            noteFailure(failure)
            when (failure.code) {
                MobileApiException.FORBIDDEN -> {
                    refresh()
                    _notices.tryEmit(words.rightsChanged)
                }
                MobileApiException.PLACE_FORBIDDEN -> {
                    // The point went from this person's rights: the next declaration lacks it, and
                    // the app falls back to its first point.
                    refresh()
                    _notices.tryEmit(words.placeGone(place?.title ?: "?"))
                }
            }
            throw failure
        }
    }

    fun selectPlace(id: String) {
        lastPlace.value = id
        write(PLACE, StoredPlace.serializer(), StoredPlace(id))
    }

    /** What [appId] sees of this server; one per app for the session's life. */
    fun bind(appId: String): Bound = bound.getOrPut(appId) { Bound(appId) }

    fun close() {
        scope.cancel()
    }

    inner class Bound(private val appId: String) : RestoServer {
        override val app: StateFlow<DeclaredApp?> =
            _declaration.map { it?.app(appId) }.stateIn(scope, SharingStarted.Eagerly, _declaration.value?.app(appId))

        override val facts: StateFlow<ServerFacts?> =
            _declaration.map { it?.server }.stateIn(scope, SharingStarted.Eagerly, _declaration.value?.server)

        override val places: StateFlow<List<Place>> =
            _declaration.map(::placesOf).stateIn(scope, SharingStarted.Eagerly, placesOf(_declaration.value))

        /** The server's last point when this app has it, else the app's first. */
        override val place: StateFlow<Place?> =
            combine(places, lastPlace) { list, last -> list.firstOrNull { it.id == last } ?: list.firstOrNull() }
                .stateIn(scope, SharingStarted.Eagerly, placesOf(_declaration.value).let { list -> list.firstOrNull { it.id == lastPlace.value } ?: list.firstOrNull() })

        override val status: StateFlow<ServerStatus> get() = this@ServerSession.status

        override val notices: SharedFlow<String> get() = _notices.asSharedFlow()

        override fun selectPlace(id: String) {
            if (places.value.any { it.id == id }) this@ServerSession.selectPlace(id)
        }

        override suspend fun call(method: String, params: JsonObject): JsonElement = this@ServerSession.call(appId, method, params)

        private fun placesOf(declaration: Declaration?): List<Place> {
            val app = declaration?.app(appId)?.takeIf { it.byPlace } ?: return emptyList()
            return app.places.mapNotNull { id -> declaration.place(id) }
        }
    }

    private fun <T> read(name: String, serializer: kotlinx.serialization.KSerializer<T>): T? =
        store.read(profile.id, name)?.let { text -> runCatching { ProtocolJson.decodeFromString(serializer, text) }.getOrNull() }

    private fun <T> write(name: String, serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        store.write(profile.id, name, ProtocolJson.encodeToString(serializer, value))
    }

    internal companion object {
        const val SESSION = "session.json"
        const val DECLARATION = "declaration.json"
        const val PLACE = "place.json"
        const val DEMO = "demo.json"
    }
}

/** What the menu is built from: when this changes, the server's Superizer is built again. */
internal fun shellOf(declaration: Declaration?): List<List<Any?>> =
    declaration?.apps.orEmpty().filter(ServerApp::canShow).map {
        listOf(it.id, it.title, it.icon?.path, it.category, it.version, it.script != null, it.native)
    }

/**
 * Login with the CAPTCHA (plan section 2), for a server the person has and for one being added. A
 * stale or unopenable task is replaced once.
 */
internal suspend fun signInTo(
    api: MobileApi,
    solver: PowSolver,
    login: String,
    password: String,
    device: DeviceInfo,
    progress: (Float) -> Unit,
): LoginResult {
    var attempt = 0
    while (true) {
        attempt++
        val solution = try {
            api.challenge()?.let { task -> solver.solve(task, progress) }
        } catch (failure: PowException) {
            if (attempt < 2) continue
            throw failure
        }
        try {
            return api.login(login.trim(), password, solution, device)
        } catch (failure: MobileApiException) {
            if (failure.code != MobileApiException.CAPTCHA_INVALID || attempt >= 2) throw failure
        }
    }
}
