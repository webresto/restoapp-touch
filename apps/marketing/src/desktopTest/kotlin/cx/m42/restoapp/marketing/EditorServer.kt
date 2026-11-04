package cx.m42.restoapp.marketing

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

/** [RestoServer] bound to «Маркетинг» of the demo — what the host gives one app of one server. */
internal class EditorServer(private val api: MobileApi, private val lang: String = "ru") : RestoServer {
    private val declaration: Declaration = runBlocking { api.describe("demo", lang).value }

    override val app: StateFlow<DeclaredApp?> = MutableStateFlow(declaration.app("marketing"))
    override val facts: StateFlow<ServerFacts?> = MutableStateFlow(declaration.server)
    override val places: StateFlow<List<Place>> = MutableStateFlow(emptyList())
    override val place: StateFlow<Place?> = MutableStateFlow(null)
    override val status: StateFlow<ServerStatus> = MutableStateFlow(ServerStatus.Online)
    override val notices: SharedFlow<String> = MutableSharedFlow(extraBufferCapacity = 8)

    override fun selectPlace(id: String) = Unit

    override suspend fun call(method: String, params: JsonObject): JsonElement = api.call("demo", method, params, null, lang).value
}
