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
import cx.m42.restoapp.protocol.Tones
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
            put("state", draft.state)
            put("serviceType", draft.serviceType)
            put("placeId", draft.place)
            // A delivery of the demo is cooked where it was taken; the one case where the two
            // differ is the stubbed screenshot of the plan, not the demo.
            put("cookingPlaceId", draft.place)
            put("cookingPlaceTitle", places.firstOrNull { it.id == draft.place }?.title?.let(::JsonPrimitive) ?: JsonNull)
            put("table", draft.table?.let(::JsonPrimitive) ?: JsonNull)
            put("customerName", draft.customerName?.let(::JsonPrimitive) ?: JsonNull)
            put("customerPhone", draft.customerPhone?.let(::JsonPrimitive) ?: JsonNull)
            put("clientId", draft.clientId?.let(::JsonPrimitive) ?: JsonNull)
            put("address", draft.address?.let(::JsonPrimitive) ?: JsonNull)
            put("comment", draft.comment?.let(::JsonPrimitive) ?: JsonNull)
            put("personsCount", draft.personsCount?.let(::JsonPrimitive) ?: JsonNull)
            put("date", draft.date?.let(::JsonPrimitive) ?: JsonNull)
            put("maxWaitMinutes", draft.maxWaitMinutes?.let(::JsonPrimitive) ?: JsonNull)
            put("paymentMethodId", draft.paymentMethodId?.let(::JsonPrimitive) ?: JsonNull)
            put("paymentMethodTitle", draft.paymentMethodTitle?.let(::JsonPrimitive) ?: JsonNull)
            put("promotionCode", draft.promotionCode?.let(::JsonPrimitive) ?: JsonNull)
            putJsonArray("lines") {
                draft.lines.forEach { line ->
                    add(buildJsonObject {
                        put("lineId", line.lineId)
                        put("dishId", line.dishId)
                        put("name", line.name)
                        put("amount", line.amount)
                        put("price", line.price)
                        put("total", line.total)
                        put("oldPrice", line.oldPrice?.let(::JsonPrimitive) ?: JsonNull)
                        put("comment", line.comment?.let(::JsonPrimitive) ?: JsonNull)
                        put("hasOptions", line.hasOptions)
                        putJsonArray("options") {
                            line.options.forEach { option ->
                                add(buildJsonObject {
                                    put("id", option.id)
                                    put("name", option.name)
                                    put("amount", option.amount)
                                })
                            }
                        }
                    })
                }
            }
            put("dishesCount", draft.dishesCount)
            put("basketTotal", draft.basketTotal)
            put("discountTotal", draft.discountTotal)
            put("deliveryCost", draft.deliveryCost)
            put("bonusesTotal", 0)
            put("total", draft.total)
            put("canCheck", draft.canCheck)
            put("canPlace", draft.canPlace)
            put("createdAt", draft.createdAt)
            put("updatedAt", draft.createdAt)
        }
        val mine = current.drafts.filter { it.place == place }

        /** A step of the order: write the draft, answer with the whole of it and a word about it. */
        suspend fun step(id: String, change: (DemoDraft) -> Pair<DemoDraft, Pair<String, String>?>): JsonObject {
            val draft = mine.firstOrNull { it.id == id } ?: fail(MobileApiException.NOT_FOUND, "No such draft")
            val (updated, message) = change(draft)
            save(current.copy(drafts = current.drafts.map { if (it.id == updated.id) updated else it }))
            return buildJsonObject {
                put("order", order(updated))
                put(
                    "message",
                    message?.let { (text, tone) -> buildJsonObject { put("text", text); put("tone", tone) } } ?: JsonNull,
                )
                put("ok", message == null || message.second != Tones.DANGER)
            }
        }

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

            "pos.catalog" -> {
                val id = params.string("id")
                mine.firstOrNull { it.id == id } ?: fail(MobileApiException.NOT_FOUND, "No such draft")
                val draft = mine.first { it.id == id }
                val group = params.stringOrNull("group", 100)
                val q = params.stringOrNull("q", 100)?.trim()?.lowercase()?.ifEmpty { null }
                val offset = params.stringOrNull("cursor", 200)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val rows = current.stock[place].orEmpty()
                val chosen = DemoCatalog.dishes
                    .filter { dish -> if (q != null) q in dish.name.lowercase() || q in dish.code.lowercase() else dish.group == group }
                    // What is stopped at the point is not sold at the point: core hides it, so does this.
                    .filter { dish -> rows[dish.id].effective() != 0 }
                val items = chosen.map { dish ->
                    buildJsonObject {
                        put("id", dish.id)
                        put("name", dish.name)
                        put("code", dish.code)
                        put("groupId", dish.group)
                        put("price", dish.price)
                        put("salePrice", JsonNull)
                        put("balance", rows[dish.id].effective())
                        put("weight", JsonNull)
                        put("imageUrl", JsonNull)
                        put("hasOptions", DemoCatalog.optionGroups.containsKey(dish.id))
                        put("inOrder", draft.lines.filter { it.dishId == dish.id }.sumOf { it.amount })
                    }
                }
                fun groupOf(name: String) = buildJsonObject {
                    put("id", name)
                    put("name", name)
                    put("imageUrl", JsonNull)
                }
                buildJsonObject {
                    putJsonArray("path") { if (group != null && q == null) add(groupOf(group)) }
                    putJsonArray("groups") { if (group == null && q == null) DemoCatalog.groupOrder.forEach { add(groupOf(it)) } }
                    putJsonArray("items") { items.drop(offset).take(PAGE).forEach { add(it) } }
                    put("nextCursor", if (offset + PAGE < items.size) JsonPrimitive((offset + PAGE).toString()) else JsonNull)
                    put("total", items.size)
                }
            }

            "pos.add" -> {
                val amount = params.intOrNull("amount") ?: 1
                val dishId = params.string("dishId")
                val chosen = params.chosenOptions()
                step(params.string("id")) { draft ->
                    val dish = DemoCatalog.dishes.firstOrNull { it.id == dishId }
                        ?: return@step draft to (words.t("Такого блюда нет", "No such product") to Tones.DANGER)
                    val groups = DemoCatalog.optionGroups[dishId].orEmpty()
                    if (chosen != null) {
                        refuseOptions(groups, chosen, words)?.let { return@step draft to it }
                    }
                    // A line with options is its own line: two guests want different sauces on the
                    // same pizza, and the receipt has to show which is which.
                    val line = if (chosen != null) null else draft.lines.firstOrNull { it.dishId == dishId && it.options.isEmpty() }
                    val lines = if (line != null) {
                        draft.lines.map { if (it.lineId == line.lineId) it.copy(amount = it.amount + amount) else it }
                    } else {
                        draft.lines + DemoDraftLine(
                            lineId = draft.nextLineId,
                            dishId = dish.id,
                            name = dish.name,
                            amount = amount,
                            price = dish.price + optionsCost(groups, chosen.orEmpty()),
                            hasOptions = groups.isNotEmpty(),
                            options = namedOptions(groups, chosen.orEmpty()),
                        )
                    }
                    draft.copy(lines = lines, nextLineId = draft.nextLineId + if (line == null) 1 else 0, state = "CART") to null
                }
            }

            "pos.options" -> {
                val id = params.string("id")
                val draft = mine.firstOrNull { it.id == id } ?: fail(MobileApiException.NOT_FOUND, "No such draft")
                val lineId = params.intOrNull("lineId")
                val line = lineId?.let { wanted -> draft.lines.firstOrNull { it.lineId == wanted } }
                if (lineId != null && line == null) fail(MobileApiException.NOT_FOUND, "No such line")
                val dishId = line?.dishId ?: params.string("dishId")
                val dish = DemoCatalog.dishes.firstOrNull { it.id == dishId }
                    ?: fail(MobileApiException.NOT_FOUND, "No such product")
                val groups = DemoCatalog.optionGroups[dishId].orEmpty()
                val taken = line?.options?.associate { it.id to it.amount }.orEmpty()
                buildJsonObject {
                    put("dishId", dish.id)
                    put("name", dish.name)
                    put("price", dish.price)
                    put("lineId", line?.lineId?.let(::JsonPrimitive) ?: JsonNull)
                    put("amount", line?.amount ?: 1)
                    putJsonArray("groups") {
                        groups.forEach { group ->
                            add(buildJsonObject {
                                put("id", group.id)
                                put("name", group.name)
                                put("required", group.required)
                                put("minAmount", group.minAmount)
                                put("maxAmount", group.maxAmount?.let(::JsonPrimitive) ?: JsonNull)
                                put("single", group.single)
                                putJsonArray("options") {
                                    group.options.forEach { option ->
                                        add(buildJsonObject {
                                            put("id", option.id)
                                            put("name", option.name)
                                            put("price", option.price)
                                            put("balance", -1)
                                            // The line's own choice, or the default the server would fill in.
                                            put("amount", if (line != null) taken[option.id] ?: 0 else option.defaultAmount)
                                            put("minAmount", 0)
                                            put("maxAmount", option.maxAmount?.let(::JsonPrimitive) ?: JsonNull)
                                            put("freeAmount", option.freeAmount)
                                            put("defaultAmount", option.defaultAmount)
                                        })
                                    }
                                }
                            })
                        }
                    }
                }
            }

            "pos.setLineOptions" -> {
                val lineId = params.int("lineId", 1..Int.MAX_VALUE)
                val chosen = params.chosenOptions().orEmpty()
                step(params.string("id")) { draft ->
                    val line = draft.lines.firstOrNull { it.lineId == lineId }
                        ?: return@step draft to (words.t("Этой позиции уже нет в заказе", "That line is no longer in the order") to Tones.DANGER)
                    val dish = DemoCatalog.dishes.first { it.id == line.dishId }
                    val groups = DemoCatalog.optionGroups[line.dishId].orEmpty()
                    refuseOptions(groups, chosen, words)?.let { return@step draft to it }
                    val changed = line.copy(
                        price = dish.price + optionsCost(groups, chosen),
                        options = namedOptions(groups, chosen),
                    )
                    draft.copy(lines = draft.lines.map { if (it.lineId == lineId) changed else it }) to null
                }
            }

            "pos.setLine" -> {
                val lineId = params.int("lineId", 1..Int.MAX_VALUE)
                val amount = params.int("amount", 0..999)
                step(params.string("id")) { draft ->
                    if (draft.lines.none { it.lineId == lineId }) {
                        draft to (words.t("Этой позиции уже нет в заказе", "That line is no longer in the order") to Tones.DANGER)
                    } else {
                        val lines = if (amount == 0) draft.lines.filterNot { it.lineId == lineId }
                        else draft.lines.map { if (it.lineId == lineId) it.copy(amount = amount) else it }
                        draft.copy(lines = lines, state = "CART") to null
                    }
                }
            }

            "pos.setLineComment" -> {
                val lineId = params.int("lineId", 1..Int.MAX_VALUE)
                val comment = params.stringOrNull("comment", 500) ?: ""
                step(params.string("id")) { draft ->
                    draft.copy(lines = draft.lines.map { if (it.lineId == lineId) it.copy(comment = comment.ifBlank { null }) else it }) to null
                }
            }

            "pos.serviceOptions" -> {
                val point = places.firstOrNull { it.id == place }
                // The demo's first point is the one with a room to eat in; the others hand food over.
                val hall = point?.id == places.firstOrNull()?.id
                buildJsonObject {
                    putJsonArray("options") {
                        add(buildJsonObject { put("id", "delivery"); put("available", true); put("reason", JsonNull) })
                        add(buildJsonObject { put("id", "pickup"); put("available", true); put("reason", JsonNull) })
                        add(buildJsonObject {
                            put("id", "dine-in")
                            put("available", hall)
                            put("reason", if (hall) JsonNull else JsonPrimitive(words.t("На этой точке нет зала", "This point has no dining room")))
                        })
                    }
                }
            }

            "pos.setService" -> {
                val serviceType = params.enumOf("serviceType", listOf("delivery", "pickup", "dine-in"), "pickup")
                val table = params.stringOrNull("table", 100)
                val hall = place == places.firstOrNull()?.id
                step(params.string("id")) { draft ->
                    if (serviceType == "dine-in" && !hall) {
                        draft to (words.t("Этот способ получения на точке недоступен", "This point does not take such orders") to Tones.DANGER)
                    } else {
                        draft.copy(
                            serviceType = serviceType,
                            table = if (serviceType == "dine-in") table else null,
                            // The address belongs to a delivery and to nothing else.
                            address = if (serviceType == "delivery") draft.address else null,
                            state = "CART",
                        ) to null
                    }
                }
            }

            "pos.setTable" -> {
                val table = params.string("table")
                step(params.string("id")) { draft ->
                    if (draft.serviceType != "dine-in") {
                        draft to (words.t("Столик указывают только для заказа в зале", "A table belongs to a dine-in order only") to Tones.DANGER)
                    } else {
                        draft.copy(table = table) to null
                    }
                }
            }

            "pos.cities" -> buildJsonObject {
                putJsonArray("items") { add(buildJsonObject { put("id", "c1"); put("name", words.t("Город", "The city")) }) }
            }

            "pos.addressSearch" -> {
                val q = params.string("q")
                buildJsonObject {
                    putJsonArray("items") {
                        DemoAddresses.streets.filter { it.lowercase().contains(q.lowercase()) }.forEach { street ->
                            add(buildJsonObject { put("id", street); put("name", street); put("type", "street") })
                        }
                    }
                }
            }

            "pos.setAddress" -> {
                // Two ways in, as the server has them: a saved address of the customer by its id,
                // or one typed — and then the house number is asked for, because core wants it.
                val savedId = params.stringOrNull("savedId", 100)
                val street = params.stringOrNull("street", 200)
                val home = params.stringOrNull("home", 50)
                val apartment = params.stringOrNull("apartment", 50)
                step(params.string("id")) { draft ->
                    val saved = savedId?.let { id ->
                        current.clients.firstOrNull { it.id == draft.clientId }
                            ?.addresses?.getOrNull(id.substringAfterLast('-').toIntOrNull() ?: -1)
                    }
                    when {
                        draft.serviceType != "delivery" ->
                            draft to (words.t("Адрес нужен только доставке", "An address belongs to a delivery") to Tones.DANGER)
                        savedId != null && saved == null ->
                            draft to (words.t("Этого адреса у клиента нет", "The customer has no such address") to Tones.DANGER)
                        saved != null -> draft.copy(address = saved, state = "CART") to null
                        street.isNullOrBlank() || home.isNullOrBlank() ->
                            draft to (words.t("Укажите улицу и номер дома", "Name the street and the house number") to Tones.DANGER)
                        else -> {
                            val line = listOfNotNull(street, home, apartment?.let { words.t("кв. $it", "apt. $it") }).joinToString(", ")
                            draft.copy(address = line, state = "CART") to null
                        }
                    }
                }
            }

            "pos.clientAddresses" -> {
                val clientId = params.string("clientId")
                val client = current.clients.firstOrNull { it.id == clientId }
                buildJsonObject {
                    putJsonArray("items") {
                        client?.addresses?.forEachIndexed { index, address ->
                            add(buildJsonObject { put("id", "${clientId}-$index"); put("formatted", address) })
                        }
                    }
                }
            }

            "pos.clients" -> clients("clients.list", params, current, words)

            "pos.setCustomer" -> {
                val name = params.string("name").trim()
                val phone = params.string("phone")
                val createClient = params.booleanOrNull("createClient") ?: false
                val digits = phone.filter { it.isDigit() }
                val existing = current.clients.firstOrNull { it.phone.filter { digit -> digit.isDigit() } == digits }
                val created = if (createClient && existing == null) {
                    DemoClient(id = "u${current.clients.size + 1}", firstName = name, phone = phone, createdAt = clock())
                } else {
                    null
                }
                if (created != null) save(current.copy(clients = current.clients + created))
                step(params.string("id")) { draft ->
                    draft.copy(
                        customerName = name,
                        customerPhone = phone,
                        clientId = if (createClient) (existing?.id ?: created?.id) else draft.clientId,
                    ) to null
                }
            }

            "pos.setClient" -> {
                val clientId = params.string("clientId")
                val client = current.clients.firstOrNull { it.id == clientId }
                step(params.string("id")) { draft ->
                    if (client == null) {
                        draft to (words.t("Такого клиента нет", "No such customer") to Tones.DANGER)
                    } else {
                        draft.copy(clientId = client.id, customerName = client.firstName, customerPhone = client.phone) to null
                    }
                }
            }

            "pos.timeOptions" -> {
                val now = clock()
                buildJsonObject {
                    put("asap", true)
                    putJsonArray("slots") {
                        // Four half-hour slots from the next hour, as a kitchen's day looks.
                        (1..4).forEach { index ->
                            val from = now + index * 30 * 60_000L
                            add(JsonArray(listOf(JsonPrimitive(from), JsonPrimitive(from + 30 * 60_000L))))
                        }
                    }
                    put("minCookingMinutes", 20)
                }
            }

            "pos.setTime" -> {
                val mode = params.enumOf("mode", listOf("asap", "time", "maxWait"), "asap")
                val date = params.longOrNull("date")
                val wait = params.intOrNull("maxWaitMinutes")
                step(params.string("id")) { draft ->
                    when {
                        mode == "time" && date == null -> draft to (words.t("Выберите время", "Choose a time") to Tones.DANGER)
                        mode == "maxWait" && wait == null -> draft to (words.t("Укажите, сколько готовы ждать", "Say how long the guest will wait") to Tones.DANGER)
                        else -> draft.copy(
                            date = if (mode == "time") date else null,
                            maxWaitMinutes = if (mode == "maxWait") wait else null,
                            state = "CART",
                        ) to null
                    }
                }
            }

            "pos.payments" -> buildJsonObject {
                putJsonArray("items") {
                    add(buildJsonObject {
                        put("id", "cash"); put("title", words.t("Наличные", "Cash")); put("isPromise", true); put("available", true)
                    })
                    add(buildJsonObject {
                        put("id", "card-on-delivery"); put("title", words.t("Картой курьеру", "Card on delivery")); put("isPromise", true); put("available", true)
                    })
                    add(buildJsonObject {
                        put("id", "online"); put("title", words.t("Онлайн", "Online")); put("isPromise", false); put("available", false)
                    })
                }
            }

            "pos.setPayment" -> {
                val methodId = params.string("paymentMethodId")
                step(params.string("id")) { draft ->
                    when (methodId) {
                        "cash" -> draft.copy(paymentMethodId = methodId, paymentMethodTitle = words.t("Наличные", "Cash"), state = "CART") to null
                        "card-on-delivery" -> draft.copy(paymentMethodId = methodId, paymentMethodTitle = words.t("Картой курьеру", "Card on delivery"), state = "CART") to null
                        "online" -> draft to (words.t("Онлайн-оплату на терминале пока не принимаем", "Online payment is not taken on a terminal yet") to Tones.DANGER)
                        else -> draft to (words.t("Такого способа оплаты нет", "No such payment method") to Tones.DANGER)
                    }
                }
            }

            "pos.setDetails" -> {
                val comment = params.stringOrNull("comment", 1000)
                val persons = params.intOrNull("personsCount")
                step(params.string("id")) { draft ->
                    draft.copy(comment = comment ?: draft.comment, personsCount = persons ?: draft.personsCount) to null
                }
            }

            "pos.promocode" -> {
                val code = params.stringOrNull("code", 100)?.trim()?.ifEmpty { null }
                step(params.string("id")) { draft ->
                    when {
                        code == null -> draft.copy(promotionCode = null) to (words.t("Промокод снят", "Promo code removed") to Tones.INFO)
                        code.equals(DemoDraft.PROMOCODE, ignoreCase = true) ->
                            draft.copy(promotionCode = DemoDraft.PROMOCODE) to (words.t("Промокод применён: −10%", "Promo code applied: −10%") to Tones.SUCCESS)
                        else -> draft.copy(promotionCode = null) to (words.t("Промокод не действует", "This promo code does not apply") to Tones.DANGER)
                    }
                }
            }

            "pos.check" -> step(params.string("id")) { draft ->
                when {
                    draft.dishesCount == 0 -> draft to (words.t("В заказе пусто", "The cart is empty") to Tones.DANGER)
                    draft.customerPhone.isNullOrBlank() -> draft to (words.t("Укажите телефон гостя", "Enter the customer's phone") to Tones.DANGER)
                    draft.serviceType == "delivery" && draft.address.isNullOrBlank() ->
                        draft to (words.t("Не указан адрес", "No address given") to Tones.DANGER)
                    else -> draft.copy(state = "CHECKOUT") to (words.t("Заказ проверен — можно оформлять", "The order is checked — it can be placed") to Tones.SUCCESS)
                }
            }

            "pos.place" -> {
                val id = params.string("id")
                val draft = mine.firstOrNull { it.id == id } ?: fail(MobileApiException.NOT_FOUND, "No such draft")
                if (draft.state != "CHECKOUT") {
                    buildJsonObject {
                        put("order", order(draft))
                        put("message", buildJsonObject {
                            put("text", words.t("Сначала проверьте заказ", "Check the order first"))
                            put("tone", Tones.DANGER)
                        })
                        put("ok", false)
                    }
                } else {
                    // Placed: it leaves the drafts and turns up among the orders of this point, as
                    // it does on a real server.
                    val placed = draft.copy(state = "ORDER")
                    val number = (current.orders[place].orEmpty().maxOfOrNull { it.number } ?: 1000) + 1
                    val asOrder = DemoOrder(
                        id = draft.id,
                        number = number,
                        state = OrderState.ORDER,
                        lines = draft.lines.map { OrderLine(it.name, it.amount, it.price) },
                        deliveryCost = draft.deliveryCost,
                        customerName = draft.customerName ?: "",
                        customerPhone = draft.customerPhone ?: "",
                        selfService = draft.serviceType != "delivery",
                        address = draft.address,
                        comment = listOfNotNull(draft.comment, draft.table?.let { words.t("Столик $it", "Table $it") }).joinToString(" · ").ifBlank { null },
                        paid = false,
                        createdAt = clock(),
                    )
                    save(
                        current.copy(
                            drafts = current.drafts.filterNot { it.id == draft.id },
                            orders = current.orders + (place to (listOf(asOrder) + current.orders[place].orEmpty())),
                        ),
                    )
                    buildJsonObject {
                        put("order", order(placed))
                        put("message", buildJsonObject {
                            put("text", words.t("Заказ ${placed.number} принят", "Order ${placed.number} accepted"))
                            put("tone", Tones.SUCCESS)
                        })
                        put("ok", true)
                    }
                }
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

private fun JsonObject.intOrNull(name: String): Int? {
    val value = this[name] ?: return null
    return (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        ?: fail(MobileApiException.INVALID, "Parameters do not match the method schema: $name")
}

private fun JsonObject.booleanOrNull(name: String): Boolean? {
    val value = this[name] ?: return null
    return (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        ?: fail(MobileApiException.INVALID, "Parameters do not match the method schema: $name")
}

private fun JsonObject.boolean(name: String): Boolean =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        ?: fail(MobileApiException.INVALID, "Parameters do not match the method schema: $name")

/** What the chooser sent back, or null when the call carried no chooser at all. */
private fun JsonObject.chosenOptions(): List<Pair<String, Int>>? {
    val array = this["modifiers"] as? JsonArray ?: return null
    return array.map { element ->
        val entry = element as? JsonObject ?: fail(MobileApiException.INVALID, "Parameters do not match the method schema: modifiers")
        entry.string("id") to entry.int("amount", 0..99)
    }
}

/** The rules of the groups, as the server checks them: a refusal in words, or nothing. */
private fun refuseOptions(
    groups: List<DemoCatalog.DemoOptionGroup>,
    chosen: List<Pair<String, Int>>,
    words: DemoWords,
): Pair<String, String>? {
    val taken = chosen.filter { (_, amount) -> amount > 0 }
    val known = groups.flatMap { group -> group.options.map { it.id } }.toSet()
    if (taken.any { (id, _) -> id !in known }) {
        return words.t("Такой добавки у этого блюда нет", "This product has no such option") to Tones.DANGER
    }
    for (group in groups) {
        val ids = group.options.map { it.id }.toSet()
        val sum = taken.filter { (id, _) -> id in ids }.sumOf { (_, amount) -> amount }
        if (sum < group.minAmount) {
            return words.t("Выберите, что идёт в «${group.name}»", "Choose what goes in «${group.name}»") to Tones.DANGER
        }
        if (group.maxAmount != null && sum > group.maxAmount) {
            return words.t("В «${group.name}» выбрано больше, чем можно", "Too many chosen in «${group.name}»") to Tones.DANGER
        }
    }
    return null
}

/** What the options add to one dish, the free ones not charged — the server's arithmetic. */
private fun optionsCost(groups: List<DemoCatalog.DemoOptionGroup>, chosen: List<Pair<String, Int>>): Int {
    val flat = groups.flatMap { it.options }
    return chosen.sumOf { (id, amount) ->
        val option = flat.firstOrNull { it.id == id } ?: return@sumOf 0
        maxOf(0, amount - option.freeAmount) * option.price
    }
}

/** The chosen options by name, in the order of the dish's own groups. */
private fun namedOptions(groups: List<DemoCatalog.DemoOptionGroup>, chosen: List<Pair<String, Int>>): List<DemoLineOption> {
    val taken = chosen.filter { (_, amount) -> amount > 0 }.toMap()
    return groups.flatMap { group -> group.options }
        .mapNotNull { option -> taken[option.id]?.let { DemoLineOption(option.id, option.name, it) } }
}

/** 1330 → "1 330 ₽", with no-break spaces so a sum never splits. */
internal fun rub(amount: Int): String =
    amount.toString().reversed().chunked(3).joinToString(" ").reversed() + " ₽"
