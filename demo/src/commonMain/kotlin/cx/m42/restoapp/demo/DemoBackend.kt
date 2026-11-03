package cx.m42.restoapp.demo

import cx.m42.restoapp.protocol.Answer
import cx.m42.restoapp.protocol.Declaration
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.DeclaredUser
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.restoapp.protocol.LoginResult
import cx.m42.restoapp.protocol.MobileApi
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.PROTOCOL_VERSION
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.restoapp.protocol.ServerAbout
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.restoapp.protocol.SignedInUser
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Where the demo server keeps its data: the server's own storage on this device. */
public interface DemoStore {
    public suspend fun read(): String?

    public suspend fun write(text: String)
}

/**
 * The demo server (plan section 4): protocol 1 answered in process, so the same renderer draws it
 * as a real server, and without a network.
 *
 * It keeps the rules of the real one where the renderer can see them: a method missing from the
 * declaration is `forbidden`; a call of an app by points needs a point of that app, another one is
 * `place_forbidden`; the data of one point never shows at another; an order moves only along core's
 * flow, anything else is `conflict`. [role] is the switch in Settings: the declaration — and its
 * revision — change with it.
 */
public class DemoBackend(
    private val store: DemoStore,
    /** [TWO_POINTS] or [ONE_POINT]. */
    public val places: List<Place>,
    private val clock: () -> Long,
    /** The demo server's name, as `server.about` and the declaration tell it. */
    private val serverName: String,
    /** Who the person is on a fresh demo; the role switch changes it later. */
    private val initialRole: DemoRole = DemoRole.Admin,
) : MobileApi {

    private val lock = Mutex()
    private var state: DemoState? = null

    private val _role = MutableStateFlow(initialRole)
    public val role: StateFlow<DemoRole> get() = _role.asStateFlow()

    override suspend fun about(): ServerAbout = ServerAbout(serverName, VERSION)

    /** The demo asks for no CAPTCHA. */
    override suspend fun challenge(): ByteArray? = null

    override suspend fun login(login: String, password: String, captchaSolution: String?, device: DeviceInfo): LoginResult {
        val role = loaded().role
        return LoginResult(TOKEN, SignedInUser(JsonPrimitive(role.ordinal + 1), loginOf(role), fullName = null), PROTOCOL_VERSION)
    }

    override suspend fun logout(token: String) {}

    override suspend fun describe(token: String, lang: String): Answer<Declaration> {
        val declaration = declaration(loaded().role, lang)
        return Answer(declaration, declaration.revision, PROTOCOL_VERSION)
    }

    override suspend fun call(token: String, method: String, params: JsonObject, place: String?, lang: String): Answer<JsonElement> =
        lock.withLock {
            val current = loadLocked()
            val declaration = declaration(current.role, lang)
            val app = declaration.apps.firstOrNull { app -> app.methods.any { it.name == method } }
                ?: fail(MobileApiException.FORBIDDEN, "Method is not available")
            val point = if (app.byPlace) {
                if (place.isNullOrEmpty()) fail(MobileApiException.INVALID, "This method needs a point: {params, place}")
                if (place !in app.places) fail(MobileApiException.PLACE_FORBIDDEN, "Access to this point is not granted")
                place
            } else {
                if (place != null) fail(MobileApiException.INVALID, "This method does not take a point")
                null
            }
            val words = DemoWords(lang)
            val result = when (app.id) {
                DISHES -> dishes(method, params, point!!, current)
                ORDERS -> orders(method, params, point!!, current, words)
                CLIENTS -> clients(method, params, current, words)
                POS -> pos(method, params, point!!, current, words)
                else -> fail(MobileApiException.FORBIDDEN, "Method is not available")
            }
            Answer(result, declaration.revision, PROTOCOL_VERSION)
        }

    /** The role switch in Settings. The next answer carries another revision. */
    public suspend fun setRole(role: DemoRole) {
        lock.withLock { save(loadLocked().copy(role = role)) }
    }

    /** New balances and a new evening at every point; the role stays. */
    public suspend fun reset() {
        lock.withLock { save(generate(loadLocked().role, clock() + 1)) }
    }

    // ---- the declaration ----

    private fun placesOf(role: DemoRole, app: String): List<String> = when {
        // The stock manager takes no orders, and the operator leads them rather than taking them:
        // the POS belongs to whoever holds `order-entry`, which here is the administrator.
        app == POS && role != DemoRole.Admin -> emptyList()
        role == DemoRole.Operator && app == ORDERS -> places.take(1).map { it.id }
        role == DemoRole.Operator -> emptyList()
        role == DemoRole.Stock && app == ORDERS -> emptyList()
        else -> places.map { it.id }
    }

    private fun declaration(role: DemoRole, lang: String): Declaration {
        val words = DemoWords(lang)
        val apps = listOfNotNull(
            placesOf(role, DISHES).takeIf { it.isNotEmpty() }?.let { dishesApp(words, it) },
            placesOf(role, ORDERS).takeIf { it.isNotEmpty() }?.let { ordersApp(words, it) },
            // The customers are the model rights of the panel: the stock manager has none of them,
            // the operator reads them, the administrator may add one as well.
            if (role == DemoRole.Stock) null else clientsApp(words, mayCreate = role == DemoRole.Admin),
            placesOf(role, POS).takeIf { it.isNotEmpty() }?.let { posApp(words, it) },
        )
        val used = apps.flatMap { it.places }.toSet()
        return Declaration(
            protocolVersion = PROTOCOL_VERSION,
            revision = "demo-${role.name.lowercase()}-${places.joinToString(",") { it.id }}-2",
            server = ServerFacts(serverName, VERSION, currency = "RUB", timezone = "Europe/Moscow", locale = "ru"),
            user = DeclaredUser(JsonPrimitive(role.ordinal + 1), loginOf(role), fullName = fullNameOf(role, words), isAdministrator = role == DemoRole.Admin),
            places = places.filter { it.id in used }.sortedBy { it.title },
            apps = apps,
        )
    }

    private fun loginOf(role: DemoRole) = role.name.lowercase()

    private fun fullNameOf(role: DemoRole, words: DemoWords) = when (role) {
        DemoRole.Admin -> words.t("Администратор", "Administrator")
        DemoRole.Operator -> words.t("Оператор", "Operator")
        DemoRole.Stock -> words.t("Склад", "Stock manager")
    }

    // ---- «Блюда» ----

    private suspend fun dishes(method: String, params: JsonObject, place: String, current: DemoState): JsonElement {
        val rows = current.stock[place].orEmpty()
        fun item(dish: DemoDish): JsonObject {
            val row = rows[dish.id]
            return buildJsonObject {
                put("id", dish.id)
                put("name", dish.name)
                put("code", dish.code)
                put("price", dish.price)
                put("enable", true)
                put("balance", row.effective())
                put("localBalance", row?.localBalance?.let(::JsonPrimitive) ?: JsonNull)
                put("rmsBalance", row?.rmsBalance?.let(::JsonPrimitive) ?: JsonNull)
                put("placeEnable", row?.enable != false)
                put("groupTitle", dish.group)
                put("mode", "minimum")
                putJsonArray("_actions") {
                    add(buildJsonObject { put("method", "dishes.setBalance") })
                    add(buildJsonObject { put("method", "dishes.setEnabled") })
                }
            }
        }
        fun dish(id: String) = DemoCatalog.dishes.firstOrNull { it.id == id } ?: fail(MobileApiException.NOT_FOUND, "No such product")

        return when (method) {
            "dishes.list" -> {
                val filter = params.enumOf("filter", listOf("limited", "stop", "all"), "limited")
                val q = params.stringOrNull("q", 100)?.trim()?.lowercase()?.ifEmpty { null }
                val offset = params.stringOrNull("cursor", 200)?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: fail(MobileApiException.INVALID, "Bad cursor") } ?: 0
                val all = DemoCatalog.dishes.map { it to item(it) }
                val limited = all.filter { (_, item) -> item.int("balance") != UNLIMITED }
                val stop = limited.filter { (_, item) -> item.int("balance") == 0 }
                val chosen = when (filter) {
                    "all" -> all.sortedWith(compareBy({ DemoCatalog.groupOrder.indexOf(it.first.group) }, { it.first.name }))
                    "stop" -> stop.sortedWith(compareBy({ it.first.group }, { it.first.name }))
                    else -> limited.sortedWith(compareBy({ it.first.group }, { it.first.name }))
                }.filter { (dish, _) -> q == null || q in dish.name.lowercase() || q in dish.code.lowercase() }
                page(chosen.map { it.second }, offset, mapOf("limited" to limited.size, "stop" to stop.size, "all" to all.size))
            }
            "dishes.get" -> buildJsonObject { put("item", item(dish(params.string("id")))) }
            "dishes.setBalance" -> {
                val target = dish(params.string("id"))
                val balance = params.int("balance", -1..100_000)
                val row = rows[target.id] ?: DishPlaceRow()
                save(current.withStock(place, target.id, row.copy(localBalance = balance)))
                buildJsonObject { put("item", item(target).withRow(row.copy(localBalance = balance))) }
            }
            "dishes.setEnabled" -> {
                val target = dish(params.string("id"))
                val enable = params.boolean("enable")
                val row = rows[target.id] ?: DishPlaceRow()
                save(current.withStock(place, target.id, row.copy(enable = enable)))
                buildJsonObject { put("item", item(target).withRow(row.copy(enable = enable))) }
            }
            else -> fail(MobileApiException.FORBIDDEN, "Method is not available")
        }
    }

    /** The record after a write: what [item] would say about the new row. */
    private fun JsonObject.withRow(row: DishPlaceRow): JsonObject = JsonObject(
        this + mapOf(
            "balance" to JsonPrimitive(row.effective()),
            "localBalance" to (row.localBalance?.let(::JsonPrimitive) ?: JsonNull),
            "placeEnable" to JsonPrimitive(row.enable),
        ),
    )

    private fun DemoState.withStock(place: String, dish: String, row: DishPlaceRow) =
        copy(stock = stock + (place to (stock[place].orEmpty() + (dish to row))))

    // ---- «Заказы» ----

    private suspend fun orders(method: String, params: JsonObject, place: String, current: DemoState, words: DemoWords): JsonElement {
        val orders = current.orders[place].orEmpty()
        fun item(order: DemoOrder): JsonObject = buildJsonObject {
            put("id", order.id)
            put("number", order.number)
            put("title", "№ ${order.number}")
            put("summary", order.customerName + " · " + words.t(if (order.selfService) "Самовывоз" else "Доставка", if (order.selfService) "Pickup" else "Delivery"))
            put("state", order.state.name)
            put("createdAt", order.createdAt)
            put("updatedAt", order.updatedAt)
            put("customerName", order.customerName)
            put("customerPhone", order.customerPhone)
            put("method", if (order.selfService) "pickup" else "delivery")
            put("address", order.address?.let(::JsonPrimitive) ?: JsonNull)
            put("comment", order.comment?.let(::JsonPrimitive) ?: JsonNull)
            put("contents", order.lines.joinToString("\n") { "${it.amount} × ${it.name} — ${rub(it.amount * it.price)}" })
            put("deliveryCost", order.deliveryCost)
            put("total", order.total)
            put("paid", order.paid)
            putJsonArray("_actions") {
                val next = order.next()
                if (next.isNotEmpty()) add(buildJsonObject {
                    put("method", "orders.setState")
                    put("input", setStateInput(words, next))
                })
            }
        }
        fun order(id: String) = orders.firstOrNull { it.id == id } ?: fail(MobileApiException.NOT_FOUND, "No such order")

        return when (method) {
            "orders.list" -> {
                val tab = params.enumOf("tab", listOf("active", "done"), "active")
                val q = params.stringOrNull("q", 100)?.trim()?.lowercase()?.ifEmpty { null }
                val offset = params.stringOrNull("cursor", 200)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val since = params.longOrNull("since")
                val active = orders.filter { it.state.active }
                val done = orders.filterNot { it.state.active }
                val chosen = (if (tab == "done") done else active)
                    .filter { q == null || q in it.number.toString() || q in it.customerName.lowercase() }
                    // Only what changed since that moment; the counts stay those of the whole tab.
                    .filter { since == null || it.updatedAt >= since }
                    .sortedByDescending { it.createdAt }
                page(chosen.map(::item), offset, mapOf("active" to active.size, "done" to done.size))
            }
            "orders.get" -> buildJsonObject { put("item", item(order(params.string("id")))) }
            "orders.setState" -> {
                val target = order(params.string("id"))
                val to = OrderState.entries.firstOrNull { it.name == params.string("to") }
                    ?.takeIf { it in TARGETS } ?: fail(MobileApiException.INVALID, "Unknown state")
                if (to !in target.next()) fail(MobileApiException.CONFLICT, "The order cannot go there from ${target.state}")
                val moved = target.copy(state = to, updatedAt = clock(), paid = target.paid || to == OrderState.DONE)
                save(current.copy(orders = current.orders + (place to orders.map { if (it.id == moved.id) moved else it })))
                buildJsonObject { put("item", item(moved)) }
            }
            else -> fail(MobileApiException.FORBIDDEN, "Method is not available")
        }
    }

    // ---- «Клиенты» ----

    private suspend fun clients(method: String, params: JsonObject, current: DemoState, words: DemoWords): JsonElement {
        fun item(client: DemoClient, card: Boolean): JsonObject = buildJsonObject {
            put("id", client.id)
            put("title", client.title)
            put("firstName", client.firstName)
            put("phone", client.phone)
            put("email", client.email?.let(::JsonPrimitive) ?: JsonNull)
            put("verified", client.verified)
            put("orderCount", client.orderCount)
            put("createdAt", client.createdAt)
            if (card) {
                put("addresses", client.addresses.joinToString("\n"))
                put(
                    "orders",
                    (1..client.orderCount).take(3)
                        .joinToString("\n") { index -> "№${1000 + index} · ${rub(500 * index + 300)} · ${words.state(OrderState.DONE)}" },
                )
            }
            putJsonArray("_actions") {}
        }

        return when (method) {
            "clients.list" -> {
                val q = params.stringOrNull("q", 100)?.trim()?.lowercase()?.ifEmpty { null }
                val offset = params.stringOrNull("cursor", 200)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val digits = q?.filter { it.isDigit() }
                val chosen = current.clients
                    .filter { client ->
                        q == null ||
                            client.title.lowercase().contains(q) ||
                            (digits != null && digits.length >= 3 && client.phone.filter { it.isDigit() }.contains(digits))
                    }
                    .sortedByDescending { it.createdAt }
                page(chosen.map { item(it, card = false) }, offset, emptyMap())
            }
            "clients.get" -> {
                val client = current.clients.firstOrNull { it.id == params.string("id") }
                    ?: fail(MobileApiException.NOT_FOUND, "No such customer")
                buildJsonObject { put("item", item(client, card = true)) }
            }
            "clients.create" -> {
                val name = params.string("name").trim()
                val phone = params.string("phone")
                val digits = phone.filter { it.isDigit() }
                if (digits.length < 5) fail(MobileApiException.INVALID, "This is not a phone number")
                // `materializeUser` of core is idempotent by number, and so is this.
                val existing = current.clients.firstOrNull { it.phone.filter { digit -> digit.isDigit() } == digits }
                if (existing != null) return buildJsonObject { put("item", item(existing, card = true)) }
                val created = DemoClient(id = "u${current.clients.size + 1}", firstName = name, phone = phone, createdAt = clock())
                save(current.copy(clients = current.clients + created))
                buildJsonObject { put("item", item(created, card = true)) }
            }
            else -> fail(MobileApiException.FORBIDDEN, "Method is not available")
        }
    }

    // ---- POS ----

    private suspend fun pos(method: String, params: JsonObject, place: String, current: DemoState, words: DemoWords): JsonElement {
        fun order(draft: DemoDraft): JsonObject = buildJsonObject {
            put("id", draft.id)
            put("number", draft.number)
            put("state", "CART")
            put("serviceType", draft.serviceType)
            put("placeId", draft.place)
            put("cookingPlaceId", draft.place)
            put("cookingPlaceTitle", places.firstOrNull { it.id == draft.place }?.title?.let(::JsonPrimitive) ?: JsonNull)
            put("table", JsonNull)
            put("customerName", draft.customerName?.let(::JsonPrimitive) ?: JsonNull)
            put("customerPhone", draft.customerPhone?.let(::JsonPrimitive) ?: JsonNull)
            put("clientId", draft.clientId?.let(::JsonPrimitive) ?: JsonNull)
            put("lines", JsonArray(emptyList()))
            put("dishesCount", 0)
            put("basketTotal", 0)
            put("discountTotal", 0)
            put("deliveryCost", 0)
            put("bonusesTotal", 0)
            put("total", 0)
            put("createdAt", draft.createdAt)
            put("updatedAt", draft.createdAt)
        }
        val mine = current.drafts.filter { it.place == place }

        return when (method) {
            "pos.order" -> {
                val id = params.stringOrNull("id", 100)
                if (id == null) {
                    buildJsonObject {
                        put("order", JsonNull)
                        putJsonArray("drafts") { mine.sortedByDescending { it.createdAt }.forEach { add(order(it)) } }
                    }
                } else {
                    val draft = mine.firstOrNull { it.id == id } ?: fail(MobileApiException.NOT_FOUND, "No such draft")
                    buildJsonObject { put("order", order(draft)) }
                }
            }
            "pos.start" -> {
                val clientId = params.stringOrNull("clientId", 100)
                val client = clientId?.let { id -> current.clients.firstOrNull { it.id == id } }
                val point = places.firstOrNull { it.id == place }
                val draft = DemoDraft(
                    id = "draft${current.nextDraft}",
                    // As `shortId` of core reads: eight characters staff can say out loud.
                    number = "D${(1000 + current.nextDraft)}",
                    place = place,
                    // The demo's first point has a room to eat in, the others hand food over.
                    // The strings of core's `serviceType`; the demo is a server, so it writes them
                    // as a server does rather than importing the client's constants.
                    serviceType = if (point?.id == places.firstOrNull()?.id) "dine-in" else "pickup",
                    clientId = client?.id,
                    customerName = client?.firstName,
                    customerPhone = client?.phone,
                    createdAt = clock(),
                )
                save(current.copy(drafts = current.drafts + draft, nextDraft = current.nextDraft + 1))
                buildJsonObject { put("order", order(draft)) }
            }
            "pos.cancel" -> {
                val id = params.string("id")
                val draft = mine.firstOrNull { it.id == id } ?: fail(MobileApiException.NOT_FOUND, "No such draft")
                save(current.copy(drafts = current.drafts - draft))
                buildJsonObject { put("ok", true) }
            }
            else -> fail(MobileApiException.FORBIDDEN, "Method is not available")
        }
    }

    // ---- storage ----

    private suspend fun loaded(): DemoState = lock.withLock { loadLocked() }

    private suspend fun loadLocked(): DemoState {
        state?.let { return it }
        val stored = store.read()
            ?.let { text -> runCatching { ProtocolJson.decodeFromString(DemoState.serializer(), text) }.getOrNull() }
            ?.takeIf { it.version == 1 && it.clients.isNotEmpty() && places.all { place -> place.id in it.stock && place.id in it.orders } }
        val result = stored ?: generate(initialRole, clock()).also { store.write(ProtocolJson.encodeToString(DemoState.serializer(), it)) }
        state = result
        _role.value = result.role
        return result
    }

    private suspend fun save(next: DemoState) {
        state = next
        _role.value = next.role
        store.write(ProtocolJson.encodeToString(DemoState.serializer(), next))
    }

    private fun generate(role: DemoRole, seed: Long): DemoState {
        val now = clock()
        return DemoState(
            role = role,
            stock = places.mapIndexed { index, place -> place.id to DemoCatalog.stock(Random(seed + index)) }.toMap(),
            orders = places.mapIndexed { index, place ->
                place.id to DemoEvening.orders(Random(seed + 100 + index), now, firstNumber = 1000 + 2000 * index + Random(seed + index).nextInt(900))
            }.toMap(),
            clients = DemoClients.all(now),
        )
    }

    public companion object {
        public const val VERSION: String = "demo"

        /** The token the host keeps for a demo server; the demo checks none. */
        public const val TOKEN: String = "demo"

        /** «Демо-ресторан»: two points, so switching between them shows at once. */
        public val TWO_POINTS: List<Place> = listOf(
            Place("p1", "Ленина, 5", "ул. Ленина, 5"),
            Place("p2", "Мира, 3", "пр. Мира, 3"),
        )

        /** «Демо-точка»: one point, and so no point switch at all. */
        public val ONE_POINT: List<Place> = listOf(Place("p1", "Садовая, 3", "ул. Садовая, 3"))

        private const val PAGE = 50
    }

    private fun page(items: List<JsonObject>, offset: Int, counts: Map<String, Int>): JsonObject {
        val shown = items.drop(offset).take(PAGE)
        val next = offset + shown.size
        return buildJsonObject {
            put("items", JsonArray(shown))
            put("nextCursor", if (next < items.size) JsonPrimitive(next.toString()) else JsonNull)
            put("counts", JsonObject(counts.mapValues { JsonPrimitive(it.value) }))
            put("total", items.size)
        }
    }
}

private fun fail(code: String, message: String): Nothing = throw MobileApiException(
    code = code,
    message = message,
    status = when (code) {
        MobileApiException.FORBIDDEN, MobileApiException.PLACE_FORBIDDEN -> 403
        MobileApiException.NOT_FOUND -> 404
        MobileApiException.CONFLICT -> 409
        else -> 400
    },
)

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.string(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() && it.length <= 100 }
        ?: fail(MobileApiException.INVALID, "Parameters do not match the method schema: $name")

private fun JsonObject.stringOrNull(name: String, maxLength: Int): String? {
    val value = this[name] ?: return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length <= maxLength }
        ?: fail(MobileApiException.INVALID, "Parameters do not match the method schema: $name")
}

private fun JsonObject.enumOf(name: String, values: List<String>, default: String): String =
    stringOrNull(name, 100)?.also { if (it !in values) fail(MobileApiException.INVALID, "Parameters do not match the method schema: $name") } ?: default

private fun JsonObject.int(name: String, range: IntRange): Int {
    val value = this[name] as? JsonPrimitive
    val number = value?.takeIf { !it.isString }?.longOrNull
    if (number == null || number !in range.first.toLong()..range.last.toLong()) fail(MobileApiException.INVALID, "Parameters do not match the method schema: $name")
    return number.toInt()
}

private fun JsonObject.longOrNull(name: String): Long? {
    val value = this[name] ?: return null
    return (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        ?: fail(MobileApiException.INVALID, "Parameters do not match the method schema: $name")
}

private fun JsonObject.boolean(name: String): Boolean =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        ?: fail(MobileApiException.INVALID, "Parameters do not match the method schema: $name")

/** 1330 → "1 330 ₽", with no-break spaces so a sum never splits. */
internal fun rub(amount: Int): String =
    amount.toString().reversed().chunked(3).joinToString(" ").reversed() + " ₽"
