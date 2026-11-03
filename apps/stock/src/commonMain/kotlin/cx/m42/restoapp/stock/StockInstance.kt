package cx.m42.restoapp.stock

import androidx.compose.runtime.Composable
import cx.m42.superizer.app.AppChrome
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.runtime.InstanceRuntime
import cx.m42.superizer.runtime.getJson
import cx.m42.superizer.runtime.setJson
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The menu with its balances, the filter, the search, and which dish is open.
 *
 * Balances are the server's (here: the app's storage, D7), written after every change. The filter,
 * the search and the open dish are the snapshot.
 */
internal class StockInstance(private val runtime: InstanceRuntime) : AppInstance() {

    private val _items = MutableStateFlow<List<StockItem>>(emptyList())
    val items: StateFlow<List<StockItem>> get() = _items.asStateFlow()

    private val _filter = MutableStateFlow(Filter.Limited)
    val filter: StateFlow<Filter> get() = _filter.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> get() = _query.asStateFlow()

    private val _open = MutableStateFlow<String?>(null)
    val open: StateFlow<String?> get() = _open.asStateFlow()

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    private val writing = Mutex()

    override suspend fun onLaunch() {
        val stored = runCatching { runtime.storage.getJson<List<StockItem>>(KEY_ITEMS) }.getOrNull()
        if (stored != null) {
            _items.value = stored
        } else {
            _items.value = DemoStock.generate(Random(runtime.clock.now()))
            runtime.storage.setJson(KEY_ITEMS, _items.value)
        }
        runtime.analytics.screen(StockApp.ID)
    }

    override fun saveState(): JsonObject = buildJsonObject {
        put("filter", JsonPrimitive(_filter.value.name))
        if (_query.value.isNotEmpty()) put("query", JsonPrimitive(_query.value))
        _open.value?.let { put("open", JsonPrimitive(it)) }
    }

    override fun restore(state: JsonObject) {
        state["filter"]?.jsonPrimitive?.content
            ?.let { name -> Filter.entries.firstOrNull { it.name == name } }
            ?.let { _filter.value = it }
        _query.value = state["query"]?.jsonPrimitive?.content.orEmpty()
        _open.value = state["open"]?.jsonPrimitive?.content?.takeIf { id -> _items.value.any { it.id == id } }
        retitle()
    }

    /** What the list shows: the filter, then the search, in menu order. */
    fun shown(items: List<StockItem>, filter: Filter, query: String): List<StockItem> {
        val needle = query.trim()
        return items.filter { filter.shows(it) && (needle.isEmpty() || it.name.contains(needle, ignoreCase = true)) }
    }

    fun select(filter: Filter) {
        _filter.value = filter
    }

    fun search(text: String) {
        _query.value = text
    }

    fun open(id: String) {
        _open.value = id
        retitle()
    }

    fun back() {
        _open.value = null
        retitle()
    }

    /** One more. An unlimited dish has no count to add to. */
    fun plus(id: String) = update(id) { if (it.unlimited) it else it.copy(balance = it.balance + 1) }

    /** One fewer; the last one puts the dish on stop, and a dish on stop stays there. */
    fun minus(id: String) = update(id) { if (it.balance <= 0) it else it.copy(balance = it.balance - 1) }

    fun setBalance(id: String, balance: Int) = update(id) { it.copy(balance = balance.coerceAtLeast(0)) }

    fun unlimited(id: String) = update(id) { it.copy(balance = -1) }

    fun stop(id: String) = update(id) { it.copy(balance = 0) }

    fun setEnabled(id: String, enabled: Boolean) = update(id) { it.copy(enabled = enabled) }

    /** A fresh menu: new balances, the list, nothing open. */
    fun resetDemo() {
        _items.value = DemoStock.generate(Random(runtime.clock.now() + 1))
        _open.value = null
        _query.value = ""
        _filter.value = Filter.Limited
        retitle()
        persist()
    }

    private fun update(id: String, change: (StockItem) -> StockItem) {
        _items.value = _items.value.map { if (it.id == id) change(it) else it }
        persist()
    }

    private fun persist() {
        runtime.scope.launch {
            writing.withLock { runtime.storage.setJson(KEY_ITEMS, _items.value) }
        }
    }

    private fun retitle() {
        val item = _items.value.firstOrNull { it.id == _open.value }
        _chrome.value = if (item == null) AppChrome() else AppChrome(title = item.name, subtitle = item.group, canGoBack = true)
    }

    @Composable
    override fun Content() {
        StockScreen(this)
    }

    private companion object {
        const val KEY_ITEMS = "stock.v1"
    }
}
