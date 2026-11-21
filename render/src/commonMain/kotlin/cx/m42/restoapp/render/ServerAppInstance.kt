package cx.m42.restoapp.render

import androidx.compose.runtime.Composable
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.DeclaredMethod
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.Open
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.restoapp.protocol.RecordAction
import cx.m42.restoapp.protocol.ListPage
import cx.m42.restoapp.protocol.Views
import cx.m42.restoapp.protocol.defaultString
import cx.m42.restoapp.protocol.id
import cx.m42.restoapp.protocol.open
import cx.m42.restoapp.protocol.property
import cx.m42.restoapp.protocol.recordOf
import cx.m42.superizer.app.AppChrome
import cx.m42.superizer.app.AppId
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppLayout
import cx.m42.superizer.app.AppWidth
import cx.m42.superizer.app.asConfig
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The list of a frame: what the chips, the search and the pages have brought. */
internal data class ListState(
    val chip: String? = null,
    val query: String = "",
    val items: List<JsonObject> = emptyList(),
    val nextCursor: String? = null,
    val counts: Map<String, Int> = emptyMap(),
    val total: Int? = null,
    val description: String? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val loadingMore: Boolean = false,
    /** How many pages are on screen: a quiet re-read replaces the list only when it is one. */
    val pages: Int = 0,
)

/**
 * One screen of the stack: the method it is read with, the params it was opened with, and what came back.
 *
 * The method's `ui.view` says whether a frame is a list or a card (`client-lookup.plan.md`, «Стек экранов»):
 * the home method is the bottom frame, and a link of a record (`_open`, `_links`) pushes the next one — the
 * summary of «Маркетинг» leads to the codes, a code to its applications, an application to one order.
 */
internal data class Frame(
    val method: String,
    /** What the frame was opened with: `{id}` of a card, `{code}` of a list of one code's records. */
    val params: JsonObject = JsonObject(emptyMap()),
    /** The words of the link that opened it: what the bar says while it is on top. */
    val title: String? = null,
    val list: ListState = ListState(),
    /** A card's record; the row's own copy until the method answers. */
    val record: JsonObject? = null,
    val loading: Boolean = false,
    /**
     * A tab of a record (a service's «События», «Логи»): the record of the card it belongs to — its header — and the
     * card's own method. Null on the card itself and on any other frame.
     */
    val card: JsonObject? = null,
    val cardMethod: String? = null,
    /** On the card's own method: the action shown alone as a tab («Настройки» — `services.update`). */
    val tab: String? = null,
)

/** One record opened from a list — kept for the screens, which ask for "the open card" and not for the stack. */
internal data class DetailState(val id: String, val record: JsonObject?, val loading: Boolean)

/** A failure the screen shows on top, by the protocol's code. */
internal data class ScreenError(val code: String, val text: String)

/** An action whose screen the client draws itself: what it acts on, and how its input is narrowed. */
internal data class NativeRequest(val record: JsonObject, val action: RecordAction, val method: DeclaredMethod)

/**
 * One open server app: a stack of frames, and the point.
 *
 * Changing the point resets everything (plan section 4): chips, search, pages and every frame above the home one
 * go, and the screen is read again — nothing of the previous point stays on it. A mutation's answer replaces the
 * record wherever it is shown; the frame on top is then re-read quietly, so counts and filters follow.
 */
internal class ServerAppInstance(
    private val runtime: InstanceRuntime,
    internal val server: RestoServer,
    private val target: Open? = null,
    /** After each mutation of this app that the server took: the mark in the menu asks again (P4). */
    private val mutated: () -> Unit = {},
) : AppInstance() {

    private val _frames = MutableStateFlow<List<Frame>>(emptyList())
    val frames: StateFlow<List<Frame>> get() = _frames.asStateFlow()

    /** The list of the topmost frame that is one: what the list screen draws. */
    private val _list = MutableStateFlow(ListState())
    val list: StateFlow<ListState> get() = _list.asStateFlow()

    /** The card on top, when the top frame is one. */
    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> get() = _detail.asStateFlow()

    private val _error = MutableStateFlow<ScreenError?>(null)
    val error: StateFlow<ScreenError?> get() = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> get() = _notice.asStateFlow()

    /** `<record id>:<method>` of the actions in flight: their controls wait. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> get() = _busy.asStateFlow()

    /** The native screen of an action that is open on top of everything (`control: native`), and what it acts on. */
    private val _native = MutableStateFlow<NativeRequest?>(null)
    val native: StateFlow<NativeRequest?> get() = _native.asStateFlow()

    /**
     * Why the server refused what a native screen submitted. Such a screen covers `TopNotices`, so
     * its refusal belongs to it and not to the app's error line — otherwise a save that was thrown
     * out looks like nothing happening, and the line turns up later behind a screen already closed.
     */
    private val _nativeFailure = MutableStateFlow<String?>(null)
    val nativeFailure: StateFlow<String?> get() = _nativeFailure.asStateFlow()

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    /**
     * As wide as the window: every screen here is a `Page` that keeps its own width. «Back» says where
     * it leads — the frame under the top one.
     */
    private val _layout = MutableStateFlow(AppLayout(AppWidth.Adaptive))
    override val layout: StateFlow<AppLayout> get() = _layout.asStateFlow()

    private var started = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var quietJob: Job? = null
    private var pollJob: Job? = null
    private var restoredPlace: String? = null

    private val app: DeclaredApp? get() = server.app.value
    private val home: DeclaredMethod? get() = app?.let { it.method(it.home) }

    /** The frame on top: the screen the person is looking at. */
    internal val top: Frame? get() = _frames.value.lastOrNull()

    private fun methodOf(frame: Frame?): DeclaredMethod? = frame?.let { app?.method(it.method) }

    private fun viewOf(frame: Frame?): String? = methodOf(frame)?.ui?.view

    override suspend fun onLaunch() {
        runtime.scope.launch {
            // Another point: the screen of the previous one goes entirely.
            server.place.map { it?.id }.distinctUntilChanged().drop(1).collect { resetForPlace() }
        }
        runtime.scope.launch { server.notices.collect { _notice.value = it } }
        runtime.scope.launch {
            combine(server.app, server.place, _frames) { app, place, frames -> Triple(app, place, frames) }.collect { (app, place, frames) ->
                _chrome.value = AppChrome(
                    title = frames.size.takeIf { it > 1 }?.let { titleOf(app, frames.last()) },
                    subtitle = if (app?.byPlace == true) place?.title else null,
                    canGoBack = frames.size > 1,
                )
                _layout.value = AppLayout(AppWidth.Adaptive, upTitle = frames.getOrNull(frames.size - 2)?.let { titleAt(app, frames, frames.size - 2) })
            }
        }
        runtime.analytics.screen("server-app")
    }

    /** What the bar says about a frame: the words of the link that opened it, or the title of its record. */
    private fun titleOf(app: DeclaredApp?, frame: Frame): String? {
        frame.title?.let { return it }
        val method = app?.method(frame.method)
        val field = method?.ui?.item?.title
        val record = frame.record
        if (record != null && field != null) {
            plain(record[field]).takeIf { it != NO_VALUE }?.let { return it }
        }
        return method?.title?.takeIf { it.isNotEmpty() }
    }

    /**
     * The words of frame [index] in the way back: the app's own name for the bottom one, which is the app;
     * the bar's words for any other.
     */
    internal fun titleAt(app: DeclaredApp?, frames: List<Frame>, index: Int): String? =
        if (index == 0) app?.title?.takeIf { it.isNotEmpty() } ?: frames.getOrNull(0)?.let { titleOf(app, it) }
        else frames.getOrNull(index)?.let { titleOf(app, it) }

    /** After [restore], from the first frame: reads the stack, and the screen on top of it. */
    fun start() {
        if (started) return
        started = true
        restoredPlace?.let { id -> if (server.place.value == null && server.places.value.any { it.id == id }) server.selectPlace(id) }
        if (_frames.value.isEmpty()) home?.let { setFrames(listOf(Frame(it.name))) }
        target?.let(::openTarget)
        loadTop()
        startPolling()
    }

    /**
     * The screen a link of another app leads to, on top of whatever this app had open: back from it is this
     * app's own stack. The link was a hint and not a permission, so a method this person does not have now is
     * one line of words over the home screen, and never a screen that opens empty.
     */
    private fun openTarget(open: Open) {
        val method = open.method ?: return
        if (app?.method(method) == null) {
            _notice.value = RenderWords(runtime.locale.langTag.value).linkGone
            return
        }
        open.place?.takeIf { id -> server.places.value.any { it.id == id } }?.let(server::selectPlace)
        val current = top
        if (current != null && current.method == method && current.params == open.params) return
        setFrames(_frames.value + Frame(method = method, params = open.params, title = open.title, loading = true))
    }

    override fun saveState(): JsonObject = buildJsonObject {
        server.place.value?.let { put("place", JsonPrimitive(it.id)) }
        // Nothing is written for a stack that was never opened: a snapshot has to survive a round trip
        // through a fresh instance, and a frame invented on the way back would not be in it.
        if (_frames.value.isNotEmpty()) {
            put(
                "frames",
                buildJsonArray {
                    for (frame in _frames.value) {
                        add(
                            buildJsonObject {
                                put("method", JsonPrimitive(frame.method))
                                if (frame.params.isNotEmpty()) put("params", frame.params)
                                frame.title?.let { put("title", JsonPrimitive(it)) }
                                frame.list.chip?.let { put("chip", JsonPrimitive(it)) }
                                if (frame.list.query.isNotEmpty()) put("query", JsonPrimitive(frame.list.query))
                            },
                        )
                    }
                },
            )
        }
    }

    /**
     * The snapshot of the stack. A snapshot of an older build has no stack in it — a chip, a search and the id of
     * one open record — and is read as the two frames it stood for, so an update does not throw a screen away.
     */
    override fun restore(state: JsonObject) {
        fun text(key: String) = runCatching { state[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
        restoredPlace = text("place")
        val saved = runCatching { (state["frames"] as? JsonArray)?.map { it.jsonObject } }.getOrNull()
        if (saved != null && saved.isNotEmpty()) {
            setFrames(
                saved.map { frame ->
                    Frame(
                        method = frame["method"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        params = frame["params"] as? JsonObject ?: JsonObject(emptyMap()),
                        title = frame["title"]?.jsonPrimitive?.contentOrNull,
                        list = ListState(
                            chip = frame["chip"]?.jsonPrimitive?.contentOrNull,
                            query = frame["query"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        ),
                    )
                }.filter { it.method.isNotEmpty() },
            )
            return
        }
        val chip = text("chip")
        val query = text("query").orEmpty()
        val open = text("open")
        // Nothing of a screen in it: `start` opens the home frame, and this snapshot has nothing to add.
        if (chip == null && query.isEmpty() && open == null) return
        val homeFrame = Frame(method = home?.name ?: return, list = ListState(chip = chip, query = query))
        val opened = open?.let { id ->
            home?.ui?.open?.let { Frame(method = it, params = buildJsonObject { put("id", JsonPrimitive(id)) }, loading = true) }
        }
        setFrames(listOfNotNull(homeFrame, opened))
    }

    override fun onForeground() {
        if (!started) return
        quietReload()
        startPolling()
    }

    /** Off screen nothing is asked: the plan's polling runs only while the app is in front. */
    override fun onBackground() {
        pollJob?.cancel()
        pollJob = null
    }

    // ---- the stack ----

    private fun setFrames(frames: List<Frame>) {
        _frames.value = frames
        val top = frames.lastOrNull()
        // The screens ask for "the list" and "the card": the top frame, by what its method draws.
        _list.value = frames.lastOrNull { viewOf(it) == Views.LIST }?.list ?: ListState()
        _detail.value = top
            ?.takeIf { viewOf(it) == Views.DETAIL && frames.size > 1 }
            ?.let { DetailState(it.params["id"]?.jsonPrimitive?.contentOrNull ?: it.record?.id() ?: "", it.record, it.loading) }
    }

    private fun updateTop(change: (Frame) -> Frame) {
        val frames = _frames.value
        if (frames.isEmpty()) return
        setFrames(frames.dropLast(1) + change(frames.last()))
    }

    private fun updateList(change: (ListState) -> ListState) = updateTop { frame -> frame.copy(list = change(frame.list)) }

    /** Params of a call of [frame]: what it was opened with, plus its chip, its search and the paging. */
    private fun params(frame: Frame, cursor: String? = null, since: Long? = null): JsonObject {
        val ui = methodOf(frame)?.ui
        return buildJsonObject {
            for ((key, value) in frame.params) put(key, value)
            ui?.chips?.let { chips -> chipOrDefault(frame, chips)?.let { put(chips, JsonPrimitive(it)) } }
            ui?.search?.let { search -> frame.list.query.trim().takeIf { it.isNotEmpty() }?.let { put(search, JsonPrimitive(it)) } }
            cursor?.let { put("cursor", JsonPrimitive(it)) }
            since?.let { put(SINCE, JsonPrimitive(it)) }
        }
    }

    private fun chipOrDefault(frame: Frame, chips: String? = null): String? {
        val key = chips ?: methodOf(frame)?.ui?.chips ?: return null
        return frame.list.chip ?: methodOf(frame)?.input?.property(key)?.defaultString()
    }

    /** The chip in force on the top frame: the chosen one, or the input's default. */
    fun currentChip(): String? = top?.let { chipOrDefault(it) }

    /** Reads the frame on top — loudly: with a spinner, and an error if it fails. */
    private fun loadTop() {
        val frame = top ?: return
        val method = methodOf(frame) ?: return
        loadJob?.cancel()
        when (method.ui?.view) {
            Views.LIST -> {
                updateList { it.copy(loading = true) }
                loadJob = runtime.scope.launch {
                    try {
                        val page = ListPage.of(server.call(method.name, params(frame, cursor = null)))
                        if (top?.method == frame.method) {
                            updateList {
                                it.copy(
                                    items = page.items, nextCursor = page.nextCursor, counts = page.counts, total = page.total, description = page.description,
                                    loading = false, loaded = true, pages = 1,
                                )
                            }
                        }
                        _error.value = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: MobileApiException) {
                        updateList { it.copy(loading = false) }
                        fail(failure)
                    }
                }
            }
            Views.DETAIL -> {
                updateTop { it.copy(loading = true) }
                loadJob = runtime.scope.launch {
                    try {
                        val record = recordOf(server.call(method.name, params(frame)))
                        if (top?.method == frame.method) updateTop { it.copy(record = record ?: it.record, loading = false) }
                        _error.value = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: MobileApiException) {
                        updateTop { it.copy(loading = false) }
                        if (failure.code == MobileApiException.NOT_FOUND && _frames.value.size > 1) back()
                        fail(failure)
                    }
                }
            }
            else -> Unit
        }
    }

    fun reload() {
        loadTop()
    }

    /** Re-reads the frame on top without a spinner — after an action, or back from a frame above it. */
    fun quietReload(delayMs: Long = 0) {
        quietJob?.cancel()
        quietJob = runtime.scope.launch {
            if (delayMs > 0) delay(delayMs)
            val frame = top ?: return@launch
            val method = methodOf(frame) ?: return@launch
            if (loadJob?.isActive == true) return@launch
            try {
                when (method.ui?.view) {
                    Views.LIST -> {
                        val page = ListPage.of(server.call(method.name, params(frame, cursor = null)))
                        if (top?.method != frame.method) return@launch
                        updateList { state ->
                            if (state.pages <= 1) {
                                state.copy(items = page.items, nextCursor = page.nextCursor, counts = page.counts, total = page.total, description = page.description, loaded = true, pages = 1)
                            } else {
                                // Pages the person scrolled to stay; the records the first page brings are refreshed.
                                val fresh = page.items.associateBy { it.id() }
                                state.copy(items = state.items.map { fresh[it.id()] ?: it }, counts = page.counts, total = page.total)
                            }
                        }
                    }
                    Views.DETAIL -> {
                        val record = recordOf(server.call(method.name, params(frame)))
                        if (record != null && top?.method == frame.method) updateTop { it.copy(record = record, loading = false) }
                    }
                    else -> Unit
                }
                _error.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: MobileApiException) {
                // Quiet: the screen stays as it is; the next loud action shows the error.
            }
        }
    }

    /**
     * Keeps the list fresh while the app is on screen (plan section 3, «Обновления без SSE»).
     *
     * Only for a list whose method takes `since` — «Заказы» do, «Блюда» do not and are re-read on return to the
     * screen. Every [POLL_MS] the probe asks for what changed since the newest change on screen: an empty answer
     * costs nothing, and anything at all is followed by a full re-read, so counts settle and a record that left
     * the tab leaves the screen with them.
     *
     * The probe cannot see a record leaving the tab — it is no longer in the tab's answer — so one poll in
     * [FULL_RELOAD_EVERY] is a full re-read regardless.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        val frame = top ?: return
        val method = methodOf(frame)?.takeIf { it.ui?.view == Views.LIST } ?: return
        if (method.input.property(SINCE) == null) return
        pollJob = runtime.scope.launch {
            var tick = 0
            while (true) {
                delay(POLL_MS)
                val current = top ?: continue
                if (current.method != frame.method) continue
                if (loadJob?.isActive == true || current.list.loading || !current.list.loaded) continue
                tick += 1
                val since = newestChange()
                if (since == null || tick % FULL_RELOAD_EVERY == 0) {
                    quietReload()
                    continue
                }
                try {
                    val page = ListPage.of(server.call(method.name, params(current, cursor = null, since = since + 1)))
                    if (page.items.isNotEmpty()) quietReload()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: MobileApiException) {
                    // Quiet, as the re-reads are: the connection's own state is drawn from the host.
                }
            }
        }
    }

    /** The newest `updatedAt` on screen — what `since` is measured against; null when records have none. */
    private fun newestChange(): Long? =
        _list.value.items.mapNotNull { runCatching { it[SINCE_FIELD]?.jsonPrimitive?.longOrNull }.getOrNull() }.maxOrNull()

    fun more() {
        val frame = top ?: return
        val method = methodOf(frame) ?: return
        val cursor = frame.list.nextCursor ?: return
        if (frame.list.loadingMore) return
        updateList { it.copy(loadingMore = true) }
        runtime.scope.launch {
            try {
                val page = ListPage.of(server.call(method.name, params(frame, cursor)))
                updateList {
                    it.copy(
                        items = it.items + page.items, nextCursor = page.nextCursor, counts = page.counts, total = page.total, description = page.description,
                        loadingMore = false, pages = it.pages + 1,
                    )
                }
            } catch (failure: MobileApiException) {
                updateList { it.copy(loadingMore = false) }
                fail(failure)
            }
        }
    }

    fun chip(value: String) {
        if (value == currentChip()) return
        updateList { it.copy(chip = value, items = emptyList(), nextCursor = null, loaded = false) }
        loadTop()
    }

    fun search(text: String) {
        updateList { it.copy(query = text) }
        searchJob?.cancel()
        searchJob = runtime.scope.launch {
            delay(SEARCH_DELAY_MS)
            loadTop()
        }
    }

    private fun resetForPlace() {
        loadJob?.cancel()
        searchJob?.cancel()
        quietJob?.cancel()
        _error.value = null
        setFrames(listOfNotNull(home?.let { Frame(it.name) }))
        if (started) {
            loadTop()
            startPolling()
        }
    }

    // ---- a record ----

    /**
     * Opens the record of a row: its own link when the server gave it one (`_open`), the default link of the list
     * otherwise (`ui.open`). The row travels with the frame, so the card shows what the list knew while it reads.
     */
    fun openRow(record: JsonObject) {
        val own = record.open()
        if (own != null) return openLink(own, row = record)
        val id = record.id() ?: return
        open(id, record)
    }

    /** The default link of a uniform list: the method of `ui.open`, by id. */
    fun open(id: String, row: JsonObject?) {
        val method = methodOf(top)?.ui?.open ?: return
        push(Frame(method = method, params = buildJsonObject { put("id", JsonPrimitive(id)) }, record = row, loading = true))
    }

    /**
     * Follows a link of a record — a row's `_open` or a line of a card's `_links`.
     *
     * A link into this app is one more frame on the stack; one into another app is that app opened, and the host
     * decides whether it may be (`client-lookup.plan.md`, «Переход в другое приложение»). A link the server built
     * is a hint and not a permission: the call it leads to is checked like any other.
     */
    fun openLink(open: Open, title: String? = null, row: JsonObject? = null) {
        // An address outside the apps: the settings page of a provider, the site of a channel, a page
        // of the marketplace. The platform opens it in its own browser — and says so when it will not.
        open.url?.let { url ->
            val address = absoluteUrl(url)
            if (address == null || !runtime.navigation.openUrl(address)) {
                _notice.value = RenderWords(runtime.locale.langTag.value).linkNotOpened
            }
            return
        }
        // P9: a server of this cloud account — the host opens it in the app, the way its switcher does.
        open.server?.let { id ->
            if (!server.openServer(id)) _notice.value = RenderWords(runtime.locale.langTag.value).serverNotOpened
            return
        }
        val method = open.method ?: run {
            _notice.value = RenderWords(runtime.locale.langTag.value).linkGone
            return
        }
        val target = open.app
        if (target != null && target != app?.id) {
            runtime.scope.launch {
                val id = AppId.parseOrNull(target)
                val words = RenderWords(runtime.locale.langTag.value)
                if (id == null) {
                    _notice.value = words.linkGone
                    return@launch
                }
                // Where to, along with whom: without `target` the other app opens on its home screen, and a
                // link to one module in the catalog would lead to no module at all.
                val config = buildJsonObject { put("target", ProtocolJson.encodeToJsonElement(Open.serializer(), open.copy(app = null))) }
                runtime.navigation.openApp(id, config.asConfig()).onFailure { _notice.value = words.linkGone }
            }
            return
        }
        if (app?.method(method) == null) {
            _notice.value = RenderWords(runtime.locale.langTag.value).linkGone
            return
        }
        push(Frame(method = method, params = open.params, title = open.title ?: title, record = row, loading = true))
    }

    /**
     * A link of the server is usually a path of the server itself («/admin/frontend/setup?channel=…»):
     * the hook cannot know the address it is reached by — behind a proxy it is not its own — so the
     * client, which does know it, puts the two together. An address is opened as it is, and only if it is
     * http(s): the browser of the system is asked on the word of the server, and a `file:` or a scheme of some
     * other program is not what the line said it was. Null is such an address, or a path with nothing to
     * resolve it against.
     */
    private fun absoluteUrl(url: String): String? {
        if (url.startsWith("/")) return server.address.trimEnd('/').takeIf { it.isNotEmpty() }?.plus(url)
        val scheme = url.substringBefore("://", "").lowercase()
        return url.takeIf { scheme == "http" || scheme == "https" }
    }

    private fun push(frame: Frame) {
        pollJob?.cancel()
        pollJob = null
        loadJob?.cancel()
        searchJob?.cancel()
        quietJob?.cancel()
        setFrames(_frames.value + frame)
        loadTop()
        startPolling()
    }

    // ---- edits the server has not got ----

    private val _unsaved = MutableStateFlow<UnsavedWork?>(null)
    val unsaved: StateFlow<UnsavedWork?> get() = _unsaved.asStateFlow()

    /** Where the person was going when the question went up; null while it is not up. */
    private val _leaving = MutableStateFlow<(() -> Unit)?>(null)
    val leaving: StateFlow<(() -> Unit)?> get() = _leaving.asStateFlow()

    fun hold(work: UnsavedWork?) {
        _unsaved.value = work
        if (work == null) _leaving.value = null
    }

    /**
     * Every way off a screen that holds edits goes through here. While something is being sent it does
     * nothing: the answer decides whether there is still anything to ask about.
     */
    fun leave(then: () -> Unit) {
        when {
            _unsaved.value == null -> then()
            _busy.value.isEmpty() -> _leaving.value = then
        }
    }

    fun stay() {
        _leaving.value = null
    }

    fun leaveWithoutSaving() {
        val then = _leaving.value ?: return
        hold(null)
        then()
    }

    /** Only the server's yes goes on to [leave]'s destination; on a refusal the screen stays, saying why. */
    fun saveAndLeave() {
        val then = _leaving.value ?: return
        _leaving.value = null
        _unsaved.value?.save?.invoke(then)
    }

    /** Leaving through the menu or for another server asks in the host's dialog: the shell owns those ways out. */
    override suspend fun onCloseRequested(): Boolean = _unsaved.value == null

    override val canSaveBeforeClose: Boolean get() = _unsaved.value?.save != null

    /** True only on the server's yes. A save that started is in [busy] from the call on; an invalid form never gets there. */
    override suspend fun saveBeforeClose(): Boolean {
        val save = _unsaved.value?.save ?: return false
        var done = false
        save { done = true }
        _busy.first { it.isEmpty() }
        return done
    }

    /** Back one frame; the bottom one is the app's own screen, and the host closes the app from there. */
    fun back() {
        if (_frames.value.size <= 1) return
        loadJob?.cancel()
        searchJob?.cancel()
        quietJob?.cancel()
        pollJob?.cancel()
        pollJob = null
        setFrames(_frames.value.dropLast(1))
        quietReload()
        startPolling()
    }

    /**
     * A section of the app (P5): its method's screen at the bottom of the stack, everything above it gone — the
     * way a tab of the home screen is. Asks first when there are edits on screen.
     */
    /**
     * Opens [tab] of the card on top in the card's place — the way back stays the list's. A query of the record
     * («События», «Логи») is a frame of its own under the card's header; an action with a section («Настройки») is
     * the card's own method showing only that action's form.
     */
    fun openTab(tab: String) {
        val current = top ?: return
        val cardMethod = current.cardMethod ?: current.method
        val card = current.card ?: current.record ?: return
        val id = card.id() ?: return
        val target = app?.method(tab) ?: return
        val selected = current.tab ?: current.method
        if (selected == tab) return
        val params = buildJsonObject { put("id", JsonPrimitive(id)) }
        val frame = when {
            tab == cardMethod -> Frame(cardMethod, params, record = card, loading = true)
            target.kind == DeclaredMethod.KIND_QUERY -> Frame(tab, params, title = current.title, card = card, cardMethod = cardMethod, loading = target.ui?.view == Views.DETAIL)
            else -> Frame(cardMethod, params, record = card, loading = true, tab = tab)
        }
        leave {
            loadJob?.cancel()
            searchJob?.cancel()
            quietJob?.cancel()
            pollJob?.cancel()
            pollJob = null
            _error.value = null
            setFrames(_frames.value.dropLast(1) + frame)
            loadTop()
            startPolling()
        }
    }

    fun openSection(method: String) {
        val section = app?.method(method) ?: return
        if (_frames.value.size == 1 && _frames.value.single().method == section.name) return
        leave {
            loadJob?.cancel()
            searchJob?.cancel()
            quietJob?.cancel()
            pollJob?.cancel()
            pollJob = null
            _error.value = null
            setFrames(listOf(Frame(section.name, loading = section.ui?.view == Views.DETAIL)))
            loadTop()
            startPolling()
        }
    }

    /** Back to the frame a crumb stands for: the stack keeps its [size] bottom frames. */
    fun backTo(size: Int) {
        while (_frames.value.size > maxOf(size, 1)) back()
    }

    // ---- actions ----

    /**
     * Runs [action] on [record] with [value] for the control's parameter. The record's id goes in as `id` when the
     * method takes one. The answer's record replaces this one wherever it is shown.
     */
    fun act(record: JsonObject, action: RecordAction, method: DeclaredMethod, value: JsonElement) {
        val param = method.ui?.control?.param ?: return
        actWith(record, action, method, buildJsonObject {
            val id = record.id()
            if (id != null && (action.input ?: method.input).property("id") != null) put("id", JsonPrimitive(id))
            put(param, value)
        })
    }

    /**
     * P12: a button of a record's notice — «Запустить на версии «next»»: the method of this app with the notice's params
     * as they are. Like [actWith], the answer's record replaces this one, and the card is re-read.
     */
    fun noticeAction(record: JsonObject, methodName: String, params: JsonObject) {
        val id = record.id() ?: return
        val key = "$id:$methodName"
        if (key in _busy.value) return
        _busy.value = _busy.value + key
        runtime.scope.launch {
            try {
                val updated = recordOf(server.call(methodName, params))
                if (updated != null) replace(updated)
                _error.value = null
                mutated()
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

    /** The same, with the params given whole: a native screen of an action builds them itself (`control: native`). */
    fun actWith(
        record: JsonObject,
        action: RecordAction,
        method: DeclaredMethod,
        params: JsonObject,
        native: Boolean = false,
        onDone: () -> Unit = {},
    ) {
        val id = record.id() ?: return
        val key = "$id:${method.name}"
        if (key in _busy.value) return
        _busy.value = _busy.value + key
        if (native) _nativeFailure.value = null
        runtime.scope.launch {
            try {
                val updated = recordOf(server.call(method.name, params))
                if (updated != null) replace(updated)
                _error.value = null
                mutated()
                onDone()
                quietReload(delayMs = QUIET_RELOAD_MS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MobileApiException) {
                if (native) _nativeFailure.value = refusalText(failure) else fail(failure)
            } finally {
                _busy.value = _busy.value - key
            }
        }
    }

    /**
     * An action of the list screen itself (`ui.on: "list"`) — «+ Новый клиент»: no record under it, the form's
     * values are the params. The answer may be a record, and then the list is re-read so it appears where the
     * server puts it.
     */
    fun actOnList(method: DeclaredMethod, params: JsonObject, native: Boolean = false, onDone: () -> Unit = {}) {
        val key = "list:${method.name}"
        if (key in _busy.value) return
        _busy.value = _busy.value + key
        if (native) _nativeFailure.value = null
        runtime.scope.launch {
            try {
                val created = recordOf(server.call(method.name, params))
                _error.value = null
                mutated()
                onDone()
                quietReload()
                // Straight to the card of what was just made: that is what the person is after.
                created?.id()?.let { id -> open(id, created) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MobileApiException) {
                if (native) _nativeFailure.value = refusalText(failure) else fail(failure)
            } finally {
                _busy.value = _busy.value - key
            }
        }
    }

    /** Opens the screen the client has for this action, and closes it again once it has run. */
    fun openNative(record: JsonObject, action: RecordAction, method: DeclaredMethod) {
        _nativeFailure.value = null
        _native.value = NativeRequest(record, action, method)
    }

    fun closeNative() {
        _nativeFailure.value = null
        _native.value = null
    }

    /** A line for the screen underneath, left by a native screen that is closing (`NativeActionScope.notice`). */
    fun showNotice(text: String) {
        _notice.value = text
    }

    /** One method of this app, for a native screen that needs more than the action it runs (a catalog). */
    suspend fun callForNative(method: String, params: JsonObject): JsonElement = server.call(method, params)

    /** The record, wherever the stack shows it: in a list of it, and as a card of it. */
    private fun replace(record: JsonObject) {
        val id = record.id() ?: return
        setFrames(
            _frames.value.map { frame ->
                frame.copy(
                    list = frame.list.copy(items = frame.list.items.map { if (it.id() == id) record else it }),
                    record = if (frame.record?.id() == id) record else frame.record,
                )
            },
        )
    }

    /**
     * A refusal as a screen of its own shows it: the server's sentence when there is one, and the
     * connection's own words when there is not.
     */
    private fun refusalText(failure: MobileApiException): String {
        val words = RenderWords(runtime.locale.langTag.value)
        return when (failure.code) {
            MobileApiException.NETWORK -> words.offline
            MobileApiException.UNAUTHORIZED -> words.signedOut
            MobileApiException.UPDATE_REQUIRED -> words.updateRequired
            MobileApiException.RATE_LIMITED -> words.tooManyAttempts(failure.retryAfterSeconds)
            else -> failure.message?.takeIf { it.isNotBlank() } ?: failure.code
        }
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
        loadTop()
    }

    @Composable
    override fun Content() {
        ServerAppContent(this)
    }

    private companion object {
        const val SEARCH_DELAY_MS = 300L

        /** The input parameter and the record field the plan's polling is built on. */
        const val SINCE = "since"
        const val SINCE_FIELD = "updatedAt"

        /** How often the screen asks what moved, and how often it re-reads everything anyway. */
        const val POLL_MS = 10_000L
        const val FULL_RELOAD_EVERY = 6

        /** Taps on − and + close together are one re-read of the list, not one each. */
        const val QUIET_RELOAD_MS = 400L
    }
}
