package cx.m42.restoapp.render

import androidx.compose.runtime.Composable
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.DeclaredMethod
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.RecordAction
import cx.m42.restoapp.protocol.ListPage
import cx.m42.restoapp.protocol.Views
import cx.m42.restoapp.protocol.defaultString
import cx.m42.restoapp.protocol.id
import cx.m42.restoapp.protocol.property
import cx.m42.restoapp.protocol.recordOf
import cx.m42.superizer.app.AppChrome
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.runtime.InstanceRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The list of the home method: what the chips, the search and the pages have brought. */
internal data class ListState(
    val chip: String? = null,
    val query: String = "",
    val items: List<JsonObject> = emptyList(),
    val nextCursor: String? = null,
    val counts: Map<String, Int> = emptyMap(),
    val total: Int? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val loadingMore: Boolean = false,
    /** How many pages are on screen: a quiet re-read replaces the list only when it is one. */
    val pages: Int = 0,
)

/** One record opened from the list; [record] is the row's copy until the open method answers. */
internal data class DetailState(val id: String, val record: JsonObject?, val loading: Boolean)

/** A failure the screen shows on top, by the protocol's code. */
internal data class ScreenError(val code: String, val text: String)

/**
 * One open server app: the home screen, at most one record opened from it, and the point.
 *
 * Changing the point resets everything (plan section 4): chips, search, pages and the open record
 * go, and the screen is read again — nothing of the previous point stays on it. A mutation's answer
 * replaces the record in place; the list is then re-read quietly, so counts and filters follow.
 */
internal class ServerAppInstance(
    private val runtime: InstanceRuntime,
    internal val server: RestoServer,
) : AppInstance() {

    private val _list = MutableStateFlow(ListState())
    val list: StateFlow<ListState> get() = _list.asStateFlow()

    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> get() = _detail.asStateFlow()

    private val _error = MutableStateFlow<ScreenError?>(null)
    val error: StateFlow<ScreenError?> get() = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> get() = _notice.asStateFlow()

    /** `<record id>:<method>` of the actions in flight: their controls wait. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> get() = _busy.asStateFlow()

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    private var started = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var quietJob: Job? = null
    private var detailJob: Job? = null
    private var restoredPlace: String? = null

    private val app: DeclaredApp? get() = server.app.value
    private val home: DeclaredMethod? get() = app?.let { it.method(it.home) }

    override suspend fun onLaunch() {
        runtime.scope.launch {
            // Another point: the screen of the previous one goes entirely.
            server.place.map { it?.id }.distinctUntilChanged().drop(1).collect { resetForPlace() }
        }
        runtime.scope.launch { server.notices.collect { _notice.value = it } }
        runtime.scope.launch {
            combine(server.app, server.place, _detail) { app, place, detail -> Triple(app, place, detail) }.collect { (app, place, detail) ->
                val titleField = app?.method(app.home)?.ui?.open?.let { app.method(it) }?.ui?.item?.title
                    ?: app?.method(app.home)?.ui?.item?.title
                val title = detail?.record?.get(titleField ?: "name")?.let { plain(it) }?.takeIf { it != NO_VALUE }
                _chrome.value = AppChrome(
                    title = if (detail != null) title else null,
                    subtitle = if (app?.byPlace == true) place?.title else null,
                    canGoBack = detail != null,
                )
            }
        }
        runtime.analytics.screen("server-app")
    }

    /** After [restore], from the first frame: reads the home screen, and the open record if any. */
    fun start() {
        if (started) return
        started = true
        restoredPlace?.let { id -> if (server.place.value == null && server.places.value.any { it.id == id }) server.selectPlace(id) }
        reload()
        _detail.value?.let { open(it.id, null) }
    }

    override fun saveState(): JsonObject = buildJsonObject {
        server.place.value?.let { put("place", JsonPrimitive(it.id)) }
        _list.value.chip?.let { put("chip", JsonPrimitive(it)) }
        if (_list.value.query.isNotEmpty()) put("query", JsonPrimitive(_list.value.query))
        _detail.value?.let { put("open", JsonPrimitive(it.id)) }
    }

    override fun restore(state: JsonObject) {
        fun text(key: String) = runCatching { state[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
        restoredPlace = text("place")
        _list.value = _list.value.copy(chip = text("chip"), query = text("query").orEmpty())
        text("open")?.let { _detail.value = DetailState(it, record = null, loading = true) }
    }

    override fun onForeground() {
        if (started) quietReload()
    }

    // ---- the list ----

    private fun params(cursor: String?): JsonObject {
        val ui = home?.ui
        val state = _list.value
        return buildJsonObject {
            ui?.chips?.let { chips -> chipOrDefault(state.chip)?.let { put(chips, JsonPrimitive(it)) } }
            ui?.search?.let { search -> state.query.trim().takeIf { it.isNotEmpty() }?.let { put(search, JsonPrimitive(it)) } }
            cursor?.let { put("cursor", JsonPrimitive(it)) }
        }
    }

    private fun chipOrDefault(chip: String?): String? {
        val chips = home?.ui?.chips ?: return null
        return chip ?: home?.input?.property(chips)?.defaultString()
    }

    /** The chip in force: the chosen one, or the input's default. */
    fun currentChip(): String? = chipOrDefault(_list.value.chip)

    fun reload() {
        val method = home ?: return
        if (method.ui?.view != Views.LIST) return reloadHomeRecord(method)
        loadJob?.cancel()
        _list.value = _list.value.copy(loading = true)
        loadJob = runtime.scope.launch {
            try {
                val page = ListPage.of(server.call(method.name, params(cursor = null)))
                _list.value = _list.value.copy(
                    items = page.items, nextCursor = page.nextCursor, counts = page.counts, total = page.total,
                    loading = false, loaded = true, pages = 1,
                )
                _error.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MobileApiException) {
                _list.value = _list.value.copy(loading = false)
                fail(failure)
            }
        }
    }

    /** A home method that is a card, not a list: its record lives in [detail] with no list under it. */
    private fun reloadHomeRecord(method: DeclaredMethod) {
        if (method.ui?.view != Views.DETAIL) return
        loadJob?.cancel()
        loadJob = runtime.scope.launch {
            try {
                val record = recordOf(server.call(method.name, JsonObject(emptyMap())))
                _list.value = _list.value.copy(items = listOfNotNull(record), loaded = true)
                _error.value = null
            } catch (failure: MobileApiException) {
                fail(failure)
            }
        }
    }

    /** Re-reads the list without a spinner — after an action, or back from a record. */
    fun quietReload(delayMs: Long = 0) {
        quietJob?.cancel()
        quietJob = runtime.scope.launch {
            if (delayMs > 0) delay(delayMs)
            val method = home?.takeIf { it.ui?.view == Views.LIST } ?: return@launch
            if (loadJob?.isActive == true) return@launch
            try {
                val page = ListPage.of(server.call(method.name, params(cursor = null)))
                val state = _list.value
                _list.value = if (state.pages <= 1) {
                    state.copy(items = page.items, nextCursor = page.nextCursor, counts = page.counts, total = page.total, loaded = true, pages = 1)
                } else {
                    // Pages the person scrolled to stay; the records the first page brings are refreshed.
                    val fresh = page.items.associateBy { it.id() }
                    state.copy(items = state.items.map { fresh[it.id()] ?: it }, counts = page.counts, total = page.total)
                }
                _error.value = null
            } catch (_: MobileApiException) {
                // Quiet: the list stays as it is; the next loud action shows the error.
            }
        }
    }

    fun more() {
        val method = home ?: return
        val cursor = _list.value.nextCursor ?: return
        if (_list.value.loadingMore) return
        _list.value = _list.value.copy(loadingMore = true)
        runtime.scope.launch {
            try {
                val page = ListPage.of(server.call(method.name, params(cursor)))
                _list.value = _list.value.copy(
                    items = _list.value.items + page.items, nextCursor = page.nextCursor, counts = page.counts, total = page.total,
                    loadingMore = false, pages = _list.value.pages + 1,
                )
            } catch (failure: MobileApiException) {
                _list.value = _list.value.copy(loadingMore = false)
                fail(failure)
            }
        }
    }

    fun chip(value: String) {
        if (value == currentChip()) return
        _list.value = _list.value.copy(chip = value, items = emptyList(), nextCursor = null, loaded = false)
        reload()
    }

    fun search(text: String) {
        _list.value = _list.value.copy(query = text)
        searchJob?.cancel()
        searchJob = runtime.scope.launch {
            delay(SEARCH_DELAY_MS)
            reload()
        }
    }

    private fun resetForPlace() {
        loadJob?.cancel()
        detailJob?.cancel()
        searchJob?.cancel()
        quietJob?.cancel()
        _list.value = ListState()
        _detail.value = null
        _error.value = null
        if (started) reload()
    }

    // ---- a record ----

    fun open(id: String, row: JsonObject?) {
        val method = home?.ui?.open?.let { app?.method(it) } ?: return
        _detail.value = DetailState(id, row ?: _detail.value?.record?.takeIf { it.id() == id }, loading = true)
        detailJob?.cancel()
        detailJob = runtime.scope.launch {
            try {
                val record = recordOf(server.call(method.name, buildJsonObject { put("id", JsonPrimitive(id)) }))
                if (_detail.value?.id == id) _detail.value = DetailState(id, record, loading = false)
                _error.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MobileApiException) {
                if (failure.code == MobileApiException.NOT_FOUND) {
                    back()
                } else {
                    _detail.value = _detail.value?.copy(loading = false)
                }
                fail(failure)
            }
        }
    }

    fun back() {
        if (_detail.value == null) return
        detailJob?.cancel()
        _detail.value = null
        quietReload()
    }

    // ---- actions ----

    /**
     * Runs [action] on [record] with [value] for the control's parameter. The record's id goes in as
     * `id` when the method takes one. The answer's record replaces this one wherever it is shown.
     */
    fun act(record: JsonObject, action: RecordAction, method: DeclaredMethod, value: JsonElement) {
        val id = record.id() ?: return
        val param = method.ui?.control?.param ?: return
        val key = "$id:${method.name}"
        if (key in _busy.value) return
        val takesId = (action.input ?: method.input).property("id") != null
        val params = buildJsonObject {
            if (takesId) put("id", JsonPrimitive(id))
            put(param, value)
        }
        _busy.value = _busy.value + key
        runtime.scope.launch {
            try {
                val updated = recordOf(server.call(method.name, params))
                if (updated != null) replace(updated)
                _error.value = null
                quietReload(delayMs = QUIET_RELOAD_MS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MobileApiException) {
                fail(failure)
            } finally {
                _busy.value = _busy.value - key
            }
        }
    }

    private fun replace(record: JsonObject) {
        val id = record.id() ?: return
        _list.value = _list.value.copy(items = _list.value.items.map { if (it.id() == id) record else it })
        _detail.value?.takeIf { it.id == id }?.let { _detail.value = it.copy(record = record) }
    }

    private fun fail(failure: MobileApiException) {
        val words = RenderWords(runtime.locale.langTag.value)
        _error.value = when (failure.code) {
            // The connection's own states are drawn from server.status; nothing more to say here.
            MobileApiException.NETWORK, MobileApiException.UNAUTHORIZED, MobileApiException.UPDATE_REQUIRED -> ScreenError(failure.code, "")
            // The host re-read the declaration (and moved to another point); its notice says so.
            MobileApiException.FORBIDDEN, MobileApiException.PLACE_FORBIDDEN -> null
            MobileApiException.RATE_LIMITED -> ScreenError(failure.code, words.tooManyAttempts(failure.retryAfterSeconds))
            else -> ScreenError(failure.code, failure.message ?: failure.code)
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun retry() {
        _error.value = null
        reload()
        _detail.value?.let { open(it.id, null) }
    }

    @Composable
    override fun Content() {
        ServerAppContent(this)
    }

    private companion object {
        const val SEARCH_DELAY_MS = 300L

        /** Taps on − and + close together are one re-read of the list, not one each. */
        const val QUIET_RELOAD_MS = 400L
    }
}
