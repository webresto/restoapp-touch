package cx.m42.restoapp.pos

import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.restoapp.render.RestoServer
import cx.m42.superizer.app.AppChrome
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.app.AppLayout
import cx.m42.superizer.app.AppWidth
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
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Which question the screen is asking right now; null — the receipt itself. */
public enum class PosSheet {
    Service,
    Guest,
    Clients,
    Address,
    Time,
    Payment,
    Promocode,
    Details,
    LineComment,
    /** What goes with a dish: the modifier groups of the menu. */
    Options,
}

/** The catalog as it is open: where we are, what is in it, what is being looked for. */
public data class PosCatalogState(
    val catalog: PosCatalog = PosCatalog(),
    val group: String? = null,
    val query: String = "",
    val loading: Boolean = false,
)

/** The lists the sheets choose from; each is read when its sheet opens. */
public data class PosOptions(
    val service: List<PosServiceOption> = emptyList(),
    val time: PosTimeOptions = PosTimeOptions(),
    val payments: List<PosPaymentOption> = emptyList(),
    val cities: List<PosNamed> = emptyList(),
    val streets: List<PosNamed> = emptyList(),
    val clients: List<PosClient> = emptyList(),
    val savedAddresses: List<PosSavedAddress> = emptyList(),
)

/**
 * The chooser of a dish's options as it stands: what the server offered, and what is ticked.
 *
 * The amounts live here and not in the sheet, so a refusal of the server («выберите соус») leaves
 * the operator looking at their own choice rather than at the form reset.
 */
public data class PosChooser(
    val options: PosDishOptions,
    /** optionId → how many of it. */
    val chosen: Map<String, Int> = emptyMap(),
    val loading: Boolean = false,
) {
    /** Which group an option belongs to — what the server is sent back. */
    internal fun groupOf(optionId: String): String? =
        options.groups.firstOrNull { group -> group.options.any { it.id == optionId } }?.id
}

/** What is on the screen. */
public data class PosState(
    val drafts: List<PosOrder> = emptyList(),
    val order: PosOrder? = null,
    val catalog: PosCatalogState? = null,
    val sheet: PosSheet? = null,
    /** The line a comment is being written for. */
    val lineId: Int? = null,
    /** The open chooser of a dish's options; null — no dish is being composed. */
    val chooser: PosChooser? = null,
    val options: PosOptions = PosOptions(),
    /** The order that has just been placed: the «accepted» screen. */
    val placed: PosOrder? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** A step is in flight — the buttons wait for the server rather than guess. */
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** The server's last word about a step: shown under the receipt. */
    val message: PosMessage? = null,
)

/**
 * One open POS: the drafts of the point, the receipt of one of them, the catalog behind the «+»,
 * and the questions of the checkout as sheets.
 *
 * Every step is a call, and every answer is the whole order as the server counted it — nothing is
 * added up here, not a line total and not the change from a note. A refusal of the server (an empty
 * basket, a guest without a phone, a point that has no dining room) is a message under the receipt,
 * not an error screen: it is the operator's next move, and the receipt has to stay where it was.
 */
public class PosInstance(
    private val runtime: InstanceRuntime,
    internal val server: RestoServer,
) : AppInstance() {

    private val _state = MutableStateFlow(PosState())
    public val state: StateFlow<PosState> get() = _state.asStateFlow()

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    // As wide as the window: the till lays itself out by the window class (catalog and receipt side by
    // side), and says where its "back" leads.
    private val _layout = MutableStateFlow(AppLayout(AppWidth.Adaptive))
    override val layout: StateFlow<AppLayout> get() = _layout.asStateFlow()

    private var started = false
    private var loadJob: Job? = null
    private var searchJob: Job? = null
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
        runtime.scope.launch {
            combine(runtime.locale.langTag, _state) { lang, state -> lang to state }.collect { (lang, state) ->
                val words = PosWords(lang)
                _layout.value = AppLayout(
                    AppWidth.Adaptive,
                    upTitle = when {
                        state.order != null && state.catalog != null -> words.receiptTab
                        state.order != null || state.placed != null -> words.receiptsTab
                        else -> null
                    },
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

    // ---- the drafts and the receipt ----

    /** The drafts of this point, and the open receipt again — the server may have moved it on. */
    public fun reload(quiet: Boolean = false) {
        loadJob?.cancel()
        if (!quiet) _state.value = _state.value.copy(loading = true)
        loadJob = runtime.scope.launch {
            val open = _state.value.order?.id ?: restoredOrder
            try {
                val answer = call("pos.order", params { open?.let { put("id", JsonPrimitive(it)) } }, PosOrderAnswer.serializer())
                restoredOrder = null
                _state.value = _state.value.copy(
                    order = answer.order,
                    drafts = if (open == null) answer.drafts else _state.value.drafts,
                    loading = false,
                    loaded = true,
                    error = null,
                )
                if (open != null) reloadDrafts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MobileApiException) {
                // A draft that is gone — placed, dropped on another device — closes the receipt
                // instead of shouting: the list is what is left to show.
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
            val answer = call("pos.order", JsonObject(emptyMap()), PosOrderAnswer.serializer())
            _state.value = _state.value.copy(drafts = answer.drafts)
        } catch (_: MobileApiException) {
            // Quiet: the receipt on screen is what matters, the list behind it can wait.
        }
    }

    /** A new draft at this point; it opens at once, as a terminal expects. */
    public fun newOrder(clientId: String? = null) {
        _state.value = _state.value.copy(placed = null)
        step("pos.start", params { clientId?.let { put("clientId", JsonPrimitive(it)) } })
    }

    public fun open(id: String) {
        _state.value = _state.value.copy(placed = null, message = null)
        runtime.scope.launch {
            _state.value = _state.value.copy(busy = true)
            try {
                val answer = call("pos.order", params { put("id", JsonPrimitive(id)) }, PosOrderAnswer.serializer())
                _state.value = _state.value.copy(order = answer.order, busy = false, loaded = true, error = null)
            } catch (failure: MobileApiException) {
                _state.value = _state.value.copy(busy = false, error = failure.message)
            }
        }
    }

    /** Back to the drafts of the point. */
    public fun close() {
        _state.value = _state.value.copy(order = null, catalog = null, sheet = null, chooser = null, message = null, error = null)
        reload()
    }

    /** Drops the open draft: the row goes, the cart stays core's. */
    public fun cancel() {
        val id = _state.value.order?.id ?: return
        _state.value = _state.value.copy(busy = true, error = null)
        runtime.scope.launch {
            try {
                server.call("pos.cancel", params { put("id", JsonPrimitive(id)) })
                _state.value = _state.value.copy(order = null, busy = false, message = null)
                reload()
            } catch (failure: MobileApiException) {
                _state.value = _state.value.copy(busy = false, error = failure.message)
                reload(quiet = true)
            }
        }
    }

    // ---- the catalog ----

    /** Opens the catalog at a group, or at the top of the menu. */
    public fun openCatalog(group: String? = null) {
        val current = _state.value.catalog
        _state.value = _state.value.copy(
            catalog = (current ?: PosCatalogState()).copy(group = group, query = "", loading = true),
            sheet = null,
        )
        loadCatalog()
    }

    public fun catalogSearch(query: String) {
        val catalog = _state.value.catalog ?: return
        _state.value = _state.value.copy(catalog = catalog.copy(query = query))
        searchJob?.cancel()
        searchJob = runtime.scope.launch {
            kotlinx.coroutines.delay(SEARCH_DELAY_MS)
            loadCatalog()
        }
    }

    /** Up a level, and out of the catalog from the top. */
    public fun catalogBack() {
        val catalog = _state.value.catalog ?: return
        when {
            catalog.query.isNotEmpty() -> catalogSearch("")
            catalog.path.size > 1 -> openCatalog(catalog.path[catalog.path.size - 2].id)
            catalog.group != null -> openCatalog(null)
            else -> _state.value = _state.value.copy(catalog = null)
        }
    }

    public fun closeCatalog() {
        _state.value = _state.value.copy(catalog = null)
    }

    private fun loadCatalog() {
        val order = _state.value.order ?: return
        val catalog = _state.value.catalog ?: return
        runtime.scope.launch {
            try {
                val answer = call(
                    "pos.catalog",
                    params {
                        put("id", JsonPrimitive(order.id))
                        catalog.group?.let { put("group", JsonPrimitive(it)) }
                        catalog.query.trim().takeIf { it.isNotEmpty() }?.let { put("q", JsonPrimitive(it)) }
                    },
                    PosCatalog.serializer(),
                )
                _state.value = _state.value.copy(catalog = _state.value.catalog?.copy(catalog = answer, loading = false))
            } catch (failure: MobileApiException) {
                _state.value = _state.value.copy(catalog = _state.value.catalog?.copy(loading = false), error = failure.message)
            }
        }
    }

    /** «+» on a tile: the server adds it and answers with the receipt; the catalog stays open. */
    public fun add(dishId: String) {
        val order = _state.value.order ?: return
        step("pos.add", params { put("id", JsonPrimitive(order.id)); put("dishId", JsonPrimitive(dishId)) }, keepCatalog = true) {
            loadCatalog()
        }
    }

    /**
     * The chooser of a dish: from the «+» of a product that has options, or from a line of the
     * receipt. Choosing changes nothing until it is confirmed — `pos.options` is a query.
     */
    public fun openOptions(dishId: String? = null, lineId: Int? = null) {
        val order = _state.value.order ?: return
        _state.value = _state.value.copy(sheet = PosSheet.Options, chooser = null, message = null)
        runtime.scope.launch {
            try {
                val answer = call("pos.options", params {
                    put("id", JsonPrimitive(order.id))
                    dishId?.let { put("dishId", JsonPrimitive(it)) }
                    lineId?.let { put("lineId", JsonPrimitive(it)) }
                }, PosDishOptions.serializer())
                // The server opens the chooser at what it would have put on the line itself.
                val chosen = answer.groups
                    .flatMap { it.options }
                    .filter { it.amount > 0 }
                    .associate { it.id to it.amount }
                _state.value = _state.value.copy(chooser = PosChooser(answer, chosen))
            } catch (failure: MobileApiException) {
                _state.value = _state.value.copy(sheet = null, error = failure.message)
            }
        }
    }

    /**
     * One of a group and no more: the choice replaces whatever was ticked in it, and tapping the
     * ticked one takes it off — even in a group that requires a choice. The rule is the server's:
     * it refuses the dish and says which group is empty, which is a word the operator can act on.
     */
    public fun pickOption(groupId: String, optionId: String) {
        val chooser = _state.value.chooser ?: return
        val group = chooser.options.groups.firstOrNull { it.id == groupId } ?: return
        val chosen = chooser.chosen.toMutableMap()
        val wasChosen = chooser.chosen[optionId] != null
        group.options.forEach { chosen.remove(it.id) }
        if (!wasChosen) chosen[optionId] = 1
        _state.value = _state.value.copy(chooser = chooser.copy(chosen = chosen), message = null)
    }

    /** − / + on an option of a group that takes several. */
    public fun setOption(optionId: String, amount: Int) {
        val chooser = _state.value.chooser ?: return
        val option = chooser.options.groups.flatMap { it.options }.firstOrNull { it.id == optionId } ?: return
        val chosen = chooser.chosen.toMutableMap()
        val wanted = amount.coerceAtLeast(0).let { asked -> option.maxAmount?.let { asked.coerceAtMost(it) } ?: asked }
        if (wanted <= 0) chosen.remove(optionId) else chosen[optionId] = wanted
        _state.value = _state.value.copy(chooser = chooser.copy(chosen = chosen), message = null)
    }

    /** Into the receipt, or onto the line it was opened from. The server checks the groups. */
    public fun confirmOptions() {
        val order = _state.value.order ?: return
        val chooser = _state.value.chooser ?: return
        val chosen = chooser.chosen.filterValues { it > 0 }
        val modifiers = kotlinx.serialization.json.buildJsonArray {
            chosen.forEach { (optionId, amount) ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(optionId))
                        put("groupId", JsonPrimitive(chooser.groupOf(optionId).orEmpty()))
                        put("amount", JsonPrimitive(amount))
                    },
                )
            }
        }
        val line = chooser.options.lineId
        if (line == null) {
            step("pos.add", params {
                put("id", JsonPrimitive(order.id))
                put("dishId", JsonPrimitive(chooser.options.dishId))
                put("modifiers", modifiers)
            }, closeSheet = true, keepCatalog = true) { if (_state.value.catalog != null) loadCatalog() }
        } else {
            step("pos.setLineOptions", params {
                put("id", JsonPrimitive(order.id))
                put("lineId", JsonPrimitive(line))
                put("modifiers", modifiers)
            }, closeSheet = true)
        }
    }

    public fun setLine(lineId: Int, amount: Int) {
        val order = _state.value.order ?: return
        step(
            "pos.setLine",
            params { put("id", JsonPrimitive(order.id)); put("lineId", JsonPrimitive(lineId)); put("amount", JsonPrimitive(amount)) },
            keepCatalog = true,
        ) { if (_state.value.catalog != null) loadCatalog() }
    }

    public fun setLineComment(lineId: Int, comment: String) {
        val order = _state.value.order ?: return
        step("pos.setLineComment", params {
            put("id", JsonPrimitive(order.id))
            put("lineId", JsonPrimitive(lineId))
            put("comment", JsonPrimitive(comment))
        })
    }

    // ---- the questions of the checkout ----

    /** Opens a sheet and reads what it chooses from. */
    public fun openSheet(sheet: PosSheet, lineId: Int? = null) {
        _state.value = _state.value.copy(sheet = sheet, lineId = lineId, chooser = null, message = null)
        val order = _state.value.order ?: return
        runtime.scope.launch {
            try {
                when (sheet) {
                    PosSheet.Service -> options(service = call("pos.serviceOptions", JsonObject(emptyMap()), PosServiceOptions.serializer()).options)
                    PosSheet.Time -> options(time = call("pos.timeOptions", params { put("id", JsonPrimitive(order.id)) }, PosTimeOptions.serializer()))
                    PosSheet.Payment -> options(payments = call("pos.payments", params { put("id", JsonPrimitive(order.id)) }, PosPayments.serializer()).items)
                    PosSheet.Clients -> searchClients("")
                    PosSheet.Address -> {
                        options(cities = call("pos.cities", JsonObject(emptyMap()), PosNamedList.serializer()).items)
                        order.clientId?.let { client ->
                            options(savedAddresses = call("pos.clientAddresses", params { put("clientId", JsonPrimitive(client)) }, PosSavedAddresses.serializer()).items)
                        }
                    }
                    else -> Unit
                }
            } catch (failure: MobileApiException) {
                _state.value = _state.value.copy(error = failure.message)
            }
        }
    }

    public fun closeSheet() {
        _state.value = _state.value.copy(sheet = null, lineId = null, chooser = null)
    }

    public fun searchClients(query: String) {
        searchJob?.cancel()
        searchJob = runtime.scope.launch {
            if (query.isNotEmpty()) kotlinx.coroutines.delay(SEARCH_DELAY_MS)
            try {
                val answer = call("pos.clients", params { if (query.isNotBlank()) put("q", JsonPrimitive(query)) }, PosClients.serializer())
                options(clients = answer.items)
            } catch (failure: MobileApiException) {
                _state.value = _state.value.copy(error = failure.message)
            }
        }
    }

    public fun searchStreets(city: String, query: String) {
        searchJob?.cancel()
        if (query.length < 2) return options(streets = emptyList())
        searchJob = runtime.scope.launch {
            kotlinx.coroutines.delay(SEARCH_DELAY_MS)
            try {
                val answer = call("pos.addressSearch", params {
                    put("city", JsonPrimitive(city))
                    put("q", JsonPrimitive(query))
                }, PosNamedList.serializer())
                options(streets = answer.items)
            } catch (failure: MobileApiException) {
                _state.value = _state.value.copy(error = failure.message)
            }
        }
    }

    public fun setService(serviceType: String, table: String? = null) {
        val order = _state.value.order ?: return
        step("pos.setService", params {
            put("id", JsonPrimitive(order.id))
            put("serviceType", JsonPrimitive(serviceType))
            table?.takeIf { it.isNotBlank() }?.let { put("table", JsonPrimitive(it)) }
        }, closeSheet = true)
    }

    public fun setTable(table: String) {
        val order = _state.value.order ?: return
        step("pos.setTable", params { put("id", JsonPrimitive(order.id)); put("table", JsonPrimitive(table)) }, closeSheet = true)
    }

    public fun setCustomer(name: String, phone: String, createClient: Boolean) {
        val order = _state.value.order ?: return
        step("pos.setCustomer", params {
            put("id", JsonPrimitive(order.id))
            put("name", JsonPrimitive(name))
            put("phone", JsonPrimitive(phone))
            put("createClient", JsonPrimitive(createClient))
        }, closeSheet = true)
    }

    public fun setClient(clientId: String) {
        val order = _state.value.order ?: return
        step("pos.setClient", params { put("id", JsonPrimitive(order.id)); put("clientId", JsonPrimitive(clientId)) }, closeSheet = true)
    }

    public fun setAddress(city: String, street: String, home: String, apartment: String?, comment: String?) {
        val order = _state.value.order ?: return
        step("pos.setAddress", params {
            put("id", JsonPrimitive(order.id))
            city.takeIf { it.isNotBlank() }?.let { put("city", JsonPrimitive(it)) }
            put("street", JsonPrimitive(street))
            put("home", JsonPrimitive(home))
            apartment?.takeIf { it.isNotBlank() }?.let { put("apartment", JsonPrimitive(it)) }
            comment?.takeIf { it.isNotBlank() }?.let { put("comment", JsonPrimitive(it)) }
        }, closeSheet = true)
    }

    /**
     * A saved address of the customer, by its id: the whole address — the house, the flat, the
     * entrance — is on the record, and only the server can read it off (`pos.setAddress`).
     */
    public fun setSavedAddress(savedId: String) {
        val order = _state.value.order ?: return
        step("pos.setAddress", params {
            put("id", JsonPrimitive(order.id))
            put("savedId", JsonPrimitive(savedId))
        }, closeSheet = true)
    }

    public fun setTime(mode: String, date: Long? = null, maxWaitMinutes: Int? = null) {
        val order = _state.value.order ?: return
        step("pos.setTime", params {
            put("id", JsonPrimitive(order.id))
            put("mode", JsonPrimitive(mode))
            date?.let { put("date", JsonPrimitive(it)) }
            maxWaitMinutes?.let { put("maxWaitMinutes", JsonPrimitive(it)) }
        }, closeSheet = true)
    }

    public fun setPayment(paymentMethodId: String) {
        val order = _state.value.order ?: return
        step("pos.setPayment", params { put("id", JsonPrimitive(order.id)); put("paymentMethodId", JsonPrimitive(paymentMethodId)) }, closeSheet = true)
    }

    public fun setDetails(comment: String?, personsCount: Int?) {
        val order = _state.value.order ?: return
        step("pos.setDetails", params {
            put("id", JsonPrimitive(order.id))
            comment?.let { put("comment", JsonPrimitive(it)) }
            personsCount?.let { put("personsCount", JsonPrimitive(it)) }
        }, closeSheet = true)
    }

    public fun setPromocode(code: String?) {
        val order = _state.value.order ?: return
        step("pos.promocode", params {
            put("id", JsonPrimitive(order.id))
            code?.takeIf { it.isNotBlank() }?.let { put("code", JsonPrimitive(it)) }
        }, closeSheet = true)
    }

    // ---- the end ----

    public fun check() {
        val order = _state.value.order ?: return
        step("pos.check", params { put("id", JsonPrimitive(order.id)) })
    }

    public fun place() {
        val order = _state.value.order ?: return
        step("pos.place", params { put("id", JsonPrimitive(order.id)) }) { answer ->
            // Placed: the receipt becomes the «accepted» screen, and the drafts are read again.
            if (answer.ok) {
                _state.value = _state.value.copy(order = null, placed = answer.order, catalog = null, sheet = null)
                reload()
            }
        }
    }

    /** From the «accepted» screen back to the drafts. */
    public fun done() {
        _state.value = _state.value.copy(placed = null, message = null)
        reload()
    }

    public fun dismissNotice() {
        _state.value = _state.value.copy(notice = null)
    }

    public fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // ---- the plumbing ----

    private fun options(
        service: List<PosServiceOption>? = null,
        time: PosTimeOptions? = null,
        payments: List<PosPaymentOption>? = null,
        cities: List<PosNamed>? = null,
        streets: List<PosNamed>? = null,
        clients: List<PosClient>? = null,
        savedAddresses: List<PosSavedAddress>? = null,
    ) {
        val current = _state.value.options
        _state.value = _state.value.copy(
            options = current.copy(
                service = service ?: current.service,
                time = time ?: current.time,
                payments = payments ?: current.payments,
                cities = cities ?: current.cities,
                streets = streets ?: current.streets,
                clients = clients ?: current.clients,
                savedAddresses = savedAddresses ?: current.savedAddresses,
            ),
        )
    }

    /**
     * One step: the server does it and answers with the whole order. The sheet that asked closes
     * when the server took the answer, and stays open when it refused — the operator fixes what
     * they are looking at.
     */
    private fun step(
        method: String,
        params: JsonObject,
        closeSheet: Boolean = false,
        keepCatalog: Boolean = false,
        onDone: (PosStep) -> Unit = {},
    ) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null, message = null)
        runtime.scope.launch {
            try {
                val answer = call(method, params, PosStep.serializer())
                _state.value = _state.value.copy(
                    order = answer.order ?: _state.value.order,
                    message = answer.message,
                    busy = false,
                    loaded = true,
                    sheet = if (closeSheet && answer.ok) null else _state.value.sheet,
                    lineId = if (closeSheet && answer.ok) null else _state.value.lineId,
                    chooser = if (closeSheet && answer.ok) null else _state.value.chooser,
                    catalog = if (keepCatalog) _state.value.catalog else _state.value.catalog,
                )
                onDone(answer)
                reloadDrafts()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: MobileApiException) {
                _state.value = _state.value.copy(busy = false, error = failure.message)
            }
        }
    }

    private fun params(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(build)

    private suspend fun <T> call(method: String, params: JsonObject, serializer: DeserializationStrategy<T>): T =
        ProtocolJson.decodeFromJsonElement(serializer, server.call(method, params))

    @androidx.compose.runtime.Composable
    override fun Content() {
        PosScreen(this)
    }

    private companion object {
        /** A search waits this long after the last keystroke, as the renderer's does. */
        const val SEARCH_DELAY_MS = 300L
    }
}

/** The groups from the top down to the open one, for the breadcrumbs. */
internal val PosCatalogState.path: List<PosGroup> get() = catalog.path
