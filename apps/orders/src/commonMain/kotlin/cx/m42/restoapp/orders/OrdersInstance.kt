package cx.m42.restoapp.orders

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

/** The three tabs over the list: what still needs someone, what is finished, and everything. */
internal enum class Tab {
    Active, Done, All;

    fun shows(order: Order): Boolean = when (this) {
        Active -> order.state.active
        Done -> !order.state.active
        All -> true
    }
}

/**
 * The orders, the tab, and which order is open.
 *
 * The orders are the server's (here: the app's storage, D7) and are written after every change.
 * Only the tab and the open order are the snapshot — what the screen would lose if the process died.
 */
internal class OrdersInstance(private val runtime: InstanceRuntime) : AppInstance() {

    private val strings: OrdersStrings get() = runtime.locale.pick(OrdersStringTables, OrdersStringsRu)

    private val _orders = MutableStateFlow<List<Order>>(emptyList())
    val orders: StateFlow<List<Order>> get() = _orders.asStateFlow()

    private val _tab = MutableStateFlow(Tab.Active)
    val tab: StateFlow<Tab> get() = _tab.asStateFlow()

    private val _open = MutableStateFlow<String?>(null)
    val open: StateFlow<String?> get() = _open.asStateFlow()

    private val _chrome = MutableStateFlow(AppChrome())
    override val chrome: StateFlow<AppChrome> get() = _chrome.asStateFlow()

    /** One write at a time, each of the latest list — so the last tap is always what is stored. */
    private val writing = Mutex()

    fun now(): Long = runtime.clock.now()

    override suspend fun onLaunch() {
        val stored = runCatching { runtime.storage.getJson<List<Order>>(KEY_ORDERS) }.getOrNull()
        if (stored != null) {
            _orders.value = stored
        } else {
            _orders.value = DemoOrders.generate(Random(now()), now())
            runtime.storage.setJson(KEY_ORDERS, _orders.value)
        }
        retitle()
        runtime.analytics.screen(OrdersApp.ID)
    }

    override fun saveState(): JsonObject = buildJsonObject {
        put("tab", JsonPrimitive(_tab.value.name))
        _open.value?.let { put("open", JsonPrimitive(it)) }
    }

    override fun restore(state: JsonObject) {
        state["tab"]?.jsonPrimitive?.content
            ?.let { name -> Tab.entries.firstOrNull { it.name == name } }
            ?.let { _tab.value = it }
        // An order that is no longer there (demo data reset since) is the list, not an error.
        _open.value = state["open"]?.jsonPrimitive?.content?.takeIf { id -> _orders.value.any { it.id == id } }
        retitle()
    }

    fun select(tab: Tab) {
        _tab.value = tab
    }

    fun open(id: String) {
        _open.value = id
        retitle()
    }

    fun back() {
        _open.value = null
        retitle()
    }

    /** Moves an order on, if the flow allows it from where it is; anything else is ignored. */
    fun move(id: String, to: OrderState) {
        _orders.value = _orders.value.map { order ->
            if (order.id == id && to in order.next()) order.copy(state = to) else order
        }
        persist()
    }

    /** A fresh evening: new demo orders, the list, nothing open. */
    fun resetDemo() {
        _orders.value = DemoOrders.generate(Random(now() + 1), now())
        _open.value = null
        _tab.value = Tab.Active
        retitle()
        persist()
    }

    private fun persist() {
        runtime.scope.launch {
            writing.withLock { runtime.storage.setJson(KEY_ORDERS, _orders.value) }
        }
    }

    private fun retitle() {
        val order = _orders.value.firstOrNull { it.id == _open.value }
        _chrome.value = if (order == null) AppChrome() else AppChrome(title = strings.title(order.number), canGoBack = true)
    }

    @Composable
    override fun Content() {
        OrdersScreen(this)
    }

    private companion object {
        const val KEY_ORDERS = "orders.v1"
    }
}
