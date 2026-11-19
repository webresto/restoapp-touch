package cx.m42.restoapp.demo

import cx.m42.restoapp.protocol.ClientRender
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.DeclaredMethod
import cx.m42.restoapp.protocol.Field
import cx.m42.restoapp.protocol.Formats
import cx.m42.restoapp.protocol.Model
import cx.m42.restoapp.protocol.Tones
import cx.m42.restoapp.protocol.Ui
import cx.m42.restoapp.protocol.Views
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * «Главная» — the day of a point at a glance (`_plan/07-prototype-1to1.md`): the hook's `apps/home.ts`,
 * method for method. One query, `home.summary`, and the record carries only what the person may see:
 * money with the right to revenue, the kitchen and the orders with the right to orders, the stop list
 * and what sells with the right to dishes. A key that is not there is a block that is not drawn.
 */

internal const val HOME = "home"

/** What of the day a role sees. */
internal class HomeRights(val money: Boolean, val orders: Boolean, val dishes: Boolean)

internal fun homeApp(words: DemoWords, places: List<String>, rights: HomeRights): DeclaredApp {
    val fields = buildList {
        if (rights.money) {
            add(Field("revenue", words.t("Выручка сегодня", "Revenue today"), Formats.MONEY, readonly = true))
            add(Field("averageCheck", words.t("Средний чек", "Average check"), Formats.MONEY, readonly = true))
        }
        if (rights.orders) add(Field("orders", words.t("Заказов", "Orders"), Formats.NUMBER, readonly = true))
        if (rights.dishes) add(Field("stop", words.t("Стоп-лист", "Stop list"), Formats.NUMBER, readonly = true))
    }
    return DeclaredApp(
        id = HOME,
        version = "1.0.0",
        title = words.t("Главная", "Home"),
        category = words.restaurant,
        home = "home.summary",
        scope = DeclaredApp.SCOPE_PLACE,
        places = places,
        models = mapOf("summary" to Model(fields)),
        methods = listOf(
            DeclaredMethod(
                name = "home.summary",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Сводка дня", "The day"),
                input = objectSchema(emptyList(), emptyMap()),
                model = "summary",
                ui = Ui(view = Views.DETAIL),
            ),
        ),
        // The client's own screen: a client that has none shows the tiles it always showed.
        client = ClientRender(native = "home", min = 1),
    )
}

private const val HOUR = 3_600_000L
private const val MOSCOW = 3 * HOUR

internal fun homeSummary(current: DemoState, place: String, words: DemoWords, rights: HomeRights, now: Long): JsonElement {
    val orders = current.orders[place].orEmpty()
    val sold = orders.filter { it.state != OrderState.REJECT }
    val active = orders.filter { it.state.active }
    val stock = current.stock[place].orEmpty()
    fun tone(state: OrderState) = when (state) {
        OrderState.ORDER -> Tones.INFO
        OrderState.COOKING -> Tones.WARNING
        OrderState.ON_THE_WAY, OrderState.DONE -> Tones.SUCCESS
        OrderState.REJECT -> Tones.MUTED
    }
    val item = buildJsonObject {
        if (rights.money) {
            val revenue = sold.sumOf { it.total }
            put("revenue", revenue)
            put("revenueDelta", 12)
            put("averageCheck", if (sold.isEmpty()) 0 else revenue / sold.size)
            put("averageCheckDelta", 84)
            // The hours of the day that had an order, and the ones between them: a chart without gaps.
            val byHour = sold.groupBy { ((it.createdAt + MOSCOW) / HOUR % 24).toInt() }.mapValues { (_, list) -> list.sumOf { it.total } }
            val hours = byHour.keys.sorted().let { if (it.isEmpty()) emptyList() else (it.first()..it.last()).toList() }
            putJsonArray("revenueByHour") {
                hours.forEach { hour ->
                    add(buildJsonObject {
                        put("x", hour.toString().padStart(2, '0') + ":00")
                        put("y", byHour[hour] ?: 0)
                    })
                }
            }
        }
        if (rights.orders) {
            put("orders", orders.size)
            put("ordersActive", active.size)
            putJsonArray("kitchen") {
                listOf(OrderState.ORDER, OrderState.COOKING, OrderState.ON_THE_WAY).forEach { state ->
                    add(buildJsonObject {
                        put("state", state.name)
                        put("title", words.state(state))
                        put("tone", tone(state))
                        put("count", active.count { it.state == state })
                    })
                }
            }
            putJsonArray("active") {
                active.sortedByDescending { it.createdAt }.take(6).forEach { order ->
                    add(buildJsonObject {
                        put("id", order.id)
                        put("title", "№ ${order.number}")
                        put("state", order.state.name)
                        put("stateTitle", words.state(order.state))
                        put("tone", tone(order.state))
                        put("method", if (order.selfService) "pickup" else "delivery")
                        put("summary", order.customerName + " · " + words.t(if (order.selfService) "Самовывоз" else "Доставка", if (order.selfService) "Pickup" else "Delivery"))
                        put("minutes", ((now - order.createdAt) / 60_000L).coerceAtLeast(0))
                        put("total", order.total)
                        // Where a tap leads: the order itself, in the app that has it.
                        putJsonObject("_open") {
                            put("app", ORDERS)
                            put("method", "orders.get")
                            putJsonObject("params") { put("id", order.id) }
                            // Another app by points: the contract wants the point the link opens at.
                            put("place", place)
                        }
                    })
                }
            }
        }
        if (rights.dishes) {
            val balances = DemoCatalog.dishes.map { stock[it.id].effective() }
            put("stop", balances.count { it == 0 })
            put("low", balances.count { it in 1..5 })
            val amounts = sold.flatMap { it.lines }.groupBy { it.name }.mapValues { (_, lines) -> lines.sumOf { it.amount } }
            putJsonArray("popular") {
                amounts.entries.sortedByDescending { it.value }.take(6).forEach { (name, amount) ->
                    val dish = DemoCatalog.dishes.firstOrNull { it.name == name }
                    add(buildJsonObject {
                        put("id", dish?.id ?: name)
                        put("title", name)
                        dish?.let { put("group", it.group) }
                        put("sold", amount)
                    })
                }
            }
        }
    }
    return JsonObject(mapOf("item" to item))
}
