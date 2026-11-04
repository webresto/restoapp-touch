package cx.m42.restoapp.render

import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.demo.DemoRole
import cx.m42.restoapp.demo.DemoStore
import cx.m42.restoapp.protocol.Declaration
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.MobileApi
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.protocol.ServerFacts
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The demo's data in memory. */
class MemoryDemoStore : DemoStore {
    var text: String? = null
    override suspend fun read(): String? = text
    override suspend fun write(text: String) {
        this.text = text
    }
}

/**
 * [RestoServer] as the host binds it, cut to what the renderer needs: the declaration of one app,
 * the points, the current point added to every call. The host's own one is tested in composeApp.
 */
class DemoServer(
    val api: MobileApi,
    private val appId: String,
    private val lang: String = "ru",
    override val address: String = "",
) : RestoServer {
    private var declaration: Declaration = runBlocking { api.describe("demo", lang).value }

    private val _app = MutableStateFlow(declaration.app(appId))
    override val app: StateFlow<DeclaredApp?> = _app
    override val facts: StateFlow<ServerFacts?> = MutableStateFlow(declaration.server)
    private val _places = MutableStateFlow(placesOf(declaration))
    override val places: StateFlow<List<Place>> = _places
    private val _place = MutableStateFlow(_places.value.firstOrNull())
    override val place: StateFlow<Place?> = _place
    override val status: MutableStateFlow<ServerStatus> = MutableStateFlow(ServerStatus.Online)
    override val notices: MutableSharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 8)
    val calls = mutableListOf<Pair<String, String?>>()

    /** Every call with what it asked for: the polling test reads `since` out of it. */
    val requests = mutableListOf<Triple<String, String?, JsonObject>>()

    private fun placesOf(declaration: Declaration): List<Place> {
        val ids = declaration.app(appId)?.places.orEmpty()
        return declaration.places.filter { it.id in ids }
    }

    override fun selectPlace(id: String) {
        _places.value.firstOrNull { it.id == id }?.let { _place.value = it }
    }

    override suspend fun call(method: String, params: JsonObject): JsonElement {
        val place = if (_app.value?.byPlace == true) _place.value?.id else null
        calls += method to place
        requests += Triple(method, place, params)
        return api.call("demo", method, params, place, lang).value
    }

    /** What the host does on a new revision: read the declaration again. */
    fun redescribe() {
        declaration = runBlocking { api.describe("demo", lang).value }
        _app.value = declaration.app(appId)
        _places.value = placesOf(declaration)
        if (_place.value?.id !in _places.value.map { it.id }) _place.value = _places.value.firstOrNull()
    }

    companion object {
        fun demo(role: DemoRole = DemoRole.Admin, places: List<Place> = DemoBackend.TWO_POINTS): DemoBackend {
            val backend = DemoBackend(MemoryDemoStore(), places, clock = { 1_700_000_000_000 }, serverName = "Демо")
            runBlocking { backend.setRole(role) }
            return backend
        }
    }
}
