package cx.m42.restoapp.pos

import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.demo.DemoRole
import cx.m42.restoapp.demo.DemoStore
import cx.m42.restoapp.protocol.Declaration
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.MobileApi
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.restoapp.render.RestoServer
import cx.m42.restoapp.render.ServerStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The demo's data in memory. */
class MemoryStore : DemoStore {
    private var text: String? = null
    override suspend fun read(): String? = text
    override suspend fun write(text: String) {
        this.text = text
    }
}

/**
 * [RestoServer] as the host binds it, cut to what the POS needs: the declaration of the POS, its
 * points, and the current point added to every call. The host's own is tested in composeApp; the
 * renderer has its own copy of this for the declarative apps.
 */
class PosServerStub(
    private val api: MobileApi,
    private val lang: String = "ru",
    /** false — no point chosen yet, as on a server just connected: the app picks one. */
    selectFirst: Boolean = true,
) : RestoServer {
    private var declaration: Declaration = runBlocking { api.describe(DemoBackend.TOKEN, lang).value }

    private val _app = MutableStateFlow(declaration.app("pos"))
    override val app: StateFlow<DeclaredApp?> = _app
    override val facts: StateFlow<ServerFacts?> = MutableStateFlow(declaration.server)
    private val _places = MutableStateFlow(placesOf())
    override val places: StateFlow<List<Place>> = _places
    private val _place = MutableStateFlow(if (selectFirst) _places.value.firstOrNull() else null)
    override val place: StateFlow<Place?> = _place
    override val status: MutableStateFlow<ServerStatus> = MutableStateFlow(ServerStatus.Online)
    override val notices: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 8)

    /** Every call with the point it carried: the POS must never ask without one. */
    val calls = mutableListOf<Pair<String, String?>>()

    private fun placesOf(): List<Place> {
        val ids = declaration.app("pos")?.places.orEmpty()
        return declaration.places.filter { it.id in ids }
    }

    override fun selectPlace(id: String) {
        _places.value.firstOrNull { it.id == id }?.let { _place.value = it }
    }

    override suspend fun call(method: String, params: JsonObject): JsonElement {
        val point = if (_app.value?.byPlace == true) _place.value?.id else null
        calls += method to point
        return api.call(DemoBackend.TOKEN, method, params, point, lang).value
    }

    companion object {
        fun demo(places: List<Place> = DemoBackend.TWO_POINTS): DemoBackend {
            val backend = DemoBackend(MemoryStore(), places, clock = { 1_700_000_000_000 }, serverName = "Демо")
            // The POS belongs to whoever may take orders; on the demo that is the administrator.
            runBlocking { backend.setRole(DemoRole.Admin) }
            return backend
        }

        fun declared(backend: DemoBackend = demo(), lang: String = "ru"): DeclaredApp =
            runBlocking { backend.describe(DemoBackend.TOKEN, lang).value.app("pos")!! }
    }
}
