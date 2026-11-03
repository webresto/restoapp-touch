package cx.m42.restoapp.pos

import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.restoapp.render.RestoServer
import cx.m42.superizer.app.AppChrome
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.runtime.InstanceRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What is on the screen: the drafts of this point, or one receipt. */
public data class PosState(
    val drafts: List<PosOrder> = emptyList(),
    val order: PosOrder? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** A step in flight — the buttons wait for the server rather than guess. */
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

/**
 * One open POS: the drafts of the point, or the receipt of one of them.
 *
 * Every step is a call and every answer replaces the whole order, because the server is what counts
 * it: a line changes the discounts, the discounts change the delivery, and nothing on the device may
 * disagree with the receipt the kitchen will see. Changing the point drops the open receipt — a
 * draft belongs to the point it was started at.
 *
 * Slice A has what proves that path: the drafts, a new one, the receipt and dropping it. The catalog,
 * the lines, receiving, the check and the placing are slice B of `order-entry.plan.md`.
 */
public class PosInstance(
    private val runtime: InstanceRuntime,
    internal val server: RestoServer,
) : AppInstance() {

    private val _state = MutableStateFlow(PosState())
    public val state: StateFlow<PosState> get() = _state.asStateFlow()

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    private var started = false
    private var loadJob: Job? = null
    private var restoredOrder: String? = null
    private var restoredPlace: String? = null

    override suspend fun onLaunch() {
        runtime.scope.launch {
            // Another point: its drafts are not this point's, and neither is the open receipt.
            server.place.map { it?.id }.distinctUntilChanged().drop(1).collect {
                _state.value = PosState()
                reload()
            }
        }
        runtime.scope.launch { server.notices.collect { notice -> _state.value = _state.value.copy(notice = notice) } }
        runtime.scope.launch {
            combine(server.place, _state) { place, state -> place to state }.collect { (place, state) ->
                _chrome.value = AppChrome(
                    title = state.order?.let { "№ ${it.number}" },
                    subtitle = place?.title,
                    canGoBack = state.order != null,
                )
            }
        }
        runtime.analytics.screen("pos")
    }

    /** From the first frame, after [restore]: the point that was chosen, then what was open. */
    public fun start() {
        if (started) return
        started = true
        restoredPlace?.let { id -> if (server.place.value == null && server.places.value.any { it.id == id }) server.selectPlace(id) }
        reload()
    }

    override fun saveState(): JsonObject = buildJsonObject {
        server.place.value?.let { put("place", JsonPrimitive(it.id)) }
        _state.value.order?.let { put("order", JsonPrimitive(it.id)) }
    }

    override fun restore(state: JsonObject) {
        fun text(key: String) = runCatching { state[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
        restoredPlace = text("place")
        restoredOrder = text("order")
    }

    override fun onForeground() {
        if (started) reload(quiet = true)
    }

    /** The drafts of this point, and the open receipt again — the server may have moved it on. */
    public fun reload(quiet: Boolean = false) {
        loadJob?.cancel()
        if (!quiet) _state.value = _state.value.copy(loading = true)
        loadJob = runtime.scope.launch {
            val open = _state.value.order?.id ?: restoredOrder
            try {
                val answer = call("pos.order", buildJsonObject { open?.let { put("id", JsonPrimitive(it)) } })
                restoredOrder = null
                _state.value = _state.value.copy(
                    order = answer.order,
                    // Asked by id the server answers with that one order only; the list is the other call.
                    drafts = if (open == null) answer.drafts else _state.value.drafts,
                    loading = false,
                    loaded = true,
                    error = null,
                )
                if (open != null) reloadDrafts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MobileApiException) {
                // A draft that is gone — placed, dropped on another device — is not an error worth a
                // banner: the receipt simply closes and the drafts are read again.
                if (open != null && failure.code == MobileApiException.NOT_FOUND) {
                    restoredOrder = null
                    _state.value = _state.value.copy(order = null, loading = false)
                    reload()
                } else {
                    _state.value = _state.value.copy(loading = false, error = failure.message)
                }
            }
        }
    }

    private suspend fun reloadDrafts() {
        try {
            val answer = call("pos.order", JsonObject(emptyMap()))
            _state.value = _state.value.copy(drafts = answer.drafts)
        } catch (_: MobileApiException) {
            // Quiet: the receipt on screen is what matters, the list behind it can wait.
        }
    }

    /** A new draft at this point; it opens at once, as a terminal expects. */
    public fun newOrder(clientId: String? = null) {
        step { call("pos.start", buildJsonObject { clientId?.let { put("clientId", JsonPrimitive(it)) } }) }
    }

    /** Opens a draft from the list. */
    public fun open(id: String) {
        step { call("pos.order", buildJsonObject { put("id", JsonPrimitive(id)) }) }
    }

    /** Back to the drafts of the point. */
    public fun close() {
        _state.value = _state.value.copy(order = null, error = null)
        reload()
    }

    /** Drops the open draft: the row goes, the cart stays core's. */
    public fun cancel() {
        val id = _state.value.order?.id ?: return
        _state.value = _state.value.copy(busy = true, error = null)
        runtime.scope.launch {
            try {
                server.call("pos.cancel", buildJsonObject { put("id", JsonPrimitive(id)) })
                _state.value = _state.value.copy(order = null, busy = false)
                reload()
            } catch (failure: MobileApiException) {
                _state.value = _state.value.copy(busy = false, error = failure.message)
                reload(quiet = true)
            }
        }
    }

    public fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    @androidx.compose.runtime.Composable
    override fun Content() {
        PosScreen(this)
    }

    /** One step: the answer is the whole order, and it is what the screen shows afterwards. */
    private fun step(action: suspend () -> PosOrderAnswer) {
        _state.value = _state.value.copy(busy = true, error = null)
        runtime.scope.launch {
            try {
                val answer = action()
                _state.value = _state.value.copy(order = answer.order, busy = false, loaded = true, error = null)
                reloadDrafts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MobileApiException) {
                _state.value = _state.value.copy(busy = false, error = failure.message)
            }
        }
    }

    private suspend fun call(method: String, params: JsonObject): PosOrderAnswer =
        ProtocolJson.decodeFromJsonElement(PosOrderAnswer.serializer(), server.call(method, params))
}
