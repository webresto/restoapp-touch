package cx.m42.restoapp.demo

import cx.m42.restoapp.protocol.Control
import cx.m42.restoapp.protocol.Controls
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.DeclaredMethod
import cx.m42.restoapp.protocol.Field
import cx.m42.restoapp.protocol.Formats
import cx.m42.restoapp.protocol.Icon
import cx.m42.restoapp.protocol.ItemUi
import cx.m42.restoapp.protocol.Model
import cx.m42.restoapp.protocol.Special
import cx.m42.restoapp.protocol.Tones
import cx.m42.restoapp.protocol.Ui
import cx.m42.restoapp.protocol.Views
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * The two demo apps as the server would declare them. «Блюда» is the `apps/dishes.ts` of the
 * app-mobile-api hook, method for method and field for field; «Заказы» has the shape the plan gives
 * slice 2 (section 3), which the server does not have yet.
 */

internal const val DISHES = "dishes"
internal const val ORDERS = "orders"

/** The hook's `locales/` for «Блюда», and the plan's words for «Заказы». */
internal class DemoWords(lang: String) {
    private val ru = lang.startsWith("ru")

    fun t(ru: String, en: String): String = if (this.ru) ru else en

    val restaurant = t("Ресторан", "Restaurant")
    val unlimited = t("Безлимит", "Unlimited")
    val outOfStock = t("Нет в наличии", "Out of stock")

    fun state(state: OrderState): String = when (state) {
        OrderState.ORDER -> t("Заказан", "Ordered")
        OrderState.COOKING -> t("Готовится", "Cooking")
        OrderState.ON_THE_WAY -> t("В пути", "On the way")
        OrderState.DONE -> t("Выполнен", "Done")
        OrderState.REJECT -> t("Отклонён", "Rejected")
    }

    /** What the button that moves an order there says. */
    fun action(state: OrderState): String = when (state) {
        OrderState.ORDER -> t("Вернуть в заказы", "Back to ordered")
        OrderState.COOKING -> t("Готовить", "Start cooking")
        OrderState.ON_THE_WAY -> t("Отправить", "Send out")
        OrderState.DONE -> t("Выполнен", "Done")
        OrderState.REJECT -> t("Отклонить", "Reject")
    }
}

/** Material Symbols "restaurant", as the hook has it. */
private const val DISHES_ICON =
    "M11 9H9V2H7v7H5V2H3v7c0 2.12 1.66 3.84 3.75 3.97V22h2.5v-9.03C11.34 12.84 13 11.12 13 9V2h-2v7zm5-3v8h2.5v8H21V2c-2.76 0-5 2.24-5 4z"

/** Material Symbols "receipt_long". */
private const val ORDERS_ICON =
    "M19.5 3.5 18 2l-1.5 1.5L15 2l-1.5 1.5L12 2l-1.5 1.5L9 2 7.5 3.5 6 2v14H3v3c0 1.66 1.34 3 3 3h12c1.66 0 3-1.34 3-3V2l-1.5 1.5z" +
        "M19 19c0 .55-.45 1-1 1s-1-.45-1-1v-3H8V5h11v14zM9 7h6v2H9zm7 0h2v2h-2zm-7 3h6v2H9zm7 0h2v2h-2z"

private fun stringProperty(maxLength: Int, minLength: Int? = null) = buildJsonObject {
    put("type", "string")
    minLength?.let { put("minLength", it) }
    put("maxLength", maxLength)
}

private fun objectSchema(required: List<String>, properties: Map<String, JsonObject>) = buildJsonObject {
    put("type", "object")
    if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    put("properties", JsonObject(properties))
}

internal fun enumProperty(values: List<String>, default: String?, titles: Map<String, String>) = buildJsonObject {
    put("type", "string")
    put("enum", JsonArray(values.map(::JsonPrimitive)))
    default?.let { put("default", it) }
    putJsonObject("x-titles") { titles.forEach { (key, value) -> put(key, value) } }
}

internal fun dishesApp(words: DemoWords, places: List<String>): DeclaredApp {
    val balanceSpecials = listOf(
        Special(JsonPrimitive(-1), words.unlimited),
        Special(JsonPrimitive(0), words.outOfStock, Tones.DANGER),
    )
    val point = words.t("На этой точке", "At this point")
    val catalog = words.t("Каталог", "Catalog")
    return DeclaredApp(
        id = DISHES,
        version = "1.0.0",
        title = words.t("Блюда", "Dishes"),
        icon = Icon(DISHES_ICON),
        category = words.restaurant,
        home = "dishes.list",
        scope = DeclaredApp.SCOPE_PLACE,
        places = places,
        models = mapOf(
            "dish" to Model(
                listOf(
                    Field("name", words.t("Название", "Name"), Formats.TEXT, readonly = true),
                    Field("balance", words.t("Доступно", "Available"), Formats.NUMBER, readonly = true, section = point, specials = balanceSpecials),
                    Field("localBalance", words.t("Ручной остаток", "Manual stock"), Formats.NUMBER, readonly = true, section = point, specials = balanceSpecials),
                    Field("rmsBalance", words.t("Остаток из RMS", "Stock from RMS"), Formats.NUMBER, readonly = true, section = point, specials = balanceSpecials),
                    Field("placeEnable", words.t("Продаётся на точке", "Sold at this point"), Formats.BOOLEAN, readonly = true, section = point),
                    Field(
                        "mode", words.t("Режим остатков", "Stock mode"), Formats.ENUM, readonly = true, section = point,
                        titles = mapOf(
                            "local-only" to words.t("Только ручной", "Manual only"),
                            "rms-only" to words.t("Только RMS", "RMS only"),
                            "minimum" to words.t("Меньший из ручного и RMS", "The smaller of manual and RMS"),
                        ),
                    ),
                    Field("groupTitle", words.t("Категория", "Category"), Formats.TEXT, readonly = true, section = catalog),
                    Field("code", words.t("Код", "Code"), Formats.TEXT, readonly = true, section = catalog),
                    Field("price", words.t("Цена", "Price"), Formats.MONEY, readonly = true, section = catalog),
                    Field("enable", words.t("В продаже в каталоге", "On sale in the catalog"), Formats.BOOLEAN, readonly = true, section = catalog),
                ),
            ),
        ),
        methods = listOf(
            DeclaredMethod(
                name = "dishes.list",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Остатки", "Stock"),
                model = "dish",
                input = objectSchema(
                    required = emptyList(),
                    properties = mapOf(
                        "filter" to enumProperty(
                            listOf("limited", "stop", "all"), "limited",
                            mapOf("limited" to words.t("С остатком", "Limited"), "stop" to words.t("На стопе", "Stopped"), "all" to words.t("Все", "All")),
                        ),
                        "q" to stringProperty(100),
                        "cursor" to stringProperty(200),
                    ),
                ),
                ui = Ui(
                    view = Views.LIST,
                    item = ItemUi(title = "name", subtitle = "groupTitle", value = "balance"),
                    groupBy = "groupTitle",
                    search = "q",
                    chips = "filter",
                    open = "dishes.get",
                    empty = words.t("Здесь пусто", "Nothing here"),
                ),
            ),
            DeclaredMethod(
                name = "dishes.get",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Блюдо", "Dish"),
                model = "dish",
                input = objectSchema(listOf("id"), mapOf("id" to stringProperty(100, minLength = 1))),
                ui = Ui(view = Views.DETAIL, item = ItemUi(title = "name", subtitle = "groupTitle", value = "balance")),
            ),
            DeclaredMethod(
                name = "dishes.setBalance",
                kind = DeclaredMethod.KIND_MUTATION,
                title = words.t("Изменить остаток", "Change stock"),
                input = objectSchema(
                    listOf("id", "balance"),
                    mapOf(
                        "id" to stringProperty(100, minLength = 1),
                        "balance" to buildJsonObject {
                            put("type", "integer")
                            put("minimum", -1)
                            put("maximum", 100000)
                        },
                    ),
                ),
                ui = Ui(
                    view = Views.ACTION,
                    on = "dish",
                    placement = listOf("row", "detail"),
                    control = Control(type = Controls.STEPPER, param = "balance", bind = "localBalance", specials = balanceSpecials),
                ),
            ),
            DeclaredMethod(
                name = "dishes.setEnabled",
                kind = DeclaredMethod.KIND_MUTATION,
                title = words.t("Продаётся на точке", "Sold at this point"),
                input = objectSchema(
                    listOf("id", "enable"),
                    mapOf("id" to stringProperty(100, minLength = 1), "enable" to buildJsonObject { put("type", "boolean") }),
                ),
                ui = Ui(
                    view = Views.ACTION,
                    on = "dish",
                    placement = listOf("row", "detail"),
                    control = Control(type = Controls.TOGGLE, param = "enable", bind = "placeEnable"),
                ),
            ),
        ),
    )
}

internal val TARGETS: List<OrderState> = listOf(OrderState.COOKING, OrderState.ON_THE_WAY, OrderState.DONE, OrderState.REJECT)

internal fun setStateInput(words: DemoWords, targets: List<OrderState>) = objectSchema(
    listOf("id", "to"),
    mapOf(
        "id" to stringProperty(100, minLength = 1),
        "to" to enumProperty(targets.map { it.name }, default = null, titles = targets.associate { it.name to words.action(it) }),
    ),
)

internal fun ordersApp(words: DemoWords, places: List<String>): DeclaredApp {
    val client = words.t("Клиент", "Customer")
    val receiving = words.t("Получение", "Receiving")
    val contents = words.t("Состав", "Contents")
    val stateSpecials = listOf(
        Special(JsonPrimitive(OrderState.ORDER.name), words.state(OrderState.ORDER), Tones.INFO),
        Special(JsonPrimitive(OrderState.COOKING.name), words.state(OrderState.COOKING), Tones.WARNING),
        Special(JsonPrimitive(OrderState.ON_THE_WAY.name), words.state(OrderState.ON_THE_WAY), Tones.INFO),
        Special(JsonPrimitive(OrderState.DONE.name), words.state(OrderState.DONE), Tones.SUCCESS),
        Special(JsonPrimitive(OrderState.REJECT.name), words.state(OrderState.REJECT), Tones.DANGER),
    )
    return DeclaredApp(
        id = ORDERS,
        version = "1.0.0",
        title = words.t("Заказы", "Orders"),
        icon = Icon(ORDERS_ICON),
        category = words.restaurant,
        home = "orders.list",
        scope = DeclaredApp.SCOPE_PLACE,
        places = places,
        models = mapOf(
            "order" to Model(
                listOf(
                    Field("state", words.t("Статус", "State"), Formats.ENUM, readonly = true, specials = stateSpecials),
                    Field("createdAt", words.t("Создан", "Created"), Formats.RELATIVE, readonly = true),
                    Field("customerName", words.t("Имя", "Name"), Formats.TEXT, readonly = true, section = client),
                    Field("customerPhone", words.t("Телефон", "Phone"), Formats.PHONE, readonly = true, section = client),
                    Field(
                        "method", words.t("Способ", "Method"), Formats.ENUM, readonly = true, section = receiving,
                        titles = mapOf("pickup" to words.t("Самовывоз", "Pickup"), "delivery" to words.t("Доставка", "Delivery")),
                    ),
                    Field("address", words.t("Адрес", "Address"), Formats.TEXT, readonly = true, section = receiving),
                    Field("comment", words.t("Комментарий", "Comment"), Formats.TEXT, readonly = true, section = receiving),
                    Field("contents", words.t("Блюда", "Dishes"), Formats.TEXT, readonly = true, section = contents),
                    Field("deliveryCost", words.t("Доставка", "Delivery"), Formats.MONEY, readonly = true, section = contents),
                    Field("total", words.t("Итого", "Total"), Formats.MONEY, readonly = true, section = contents),
                    Field("paid", words.t("Оплачен", "Paid"), Formats.BOOLEAN, readonly = true, section = contents),
                ),
            ),
        ),
        methods = listOf(
            DeclaredMethod(
                name = "orders.list",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Заказы", "Orders"),
                model = "order",
                input = objectSchema(
                    emptyList(),
                    mapOf(
                        "tab" to enumProperty(
                            listOf("active", "done"), "active",
                            mapOf("active" to words.t("Активные", "Active"), "done" to words.t("Завершённые", "Finished")),
                        ),
                        "q" to stringProperty(100),
                        "cursor" to stringProperty(200),
                        // Polling while the screen is open: the moment of the last change the client knows.
                        "since" to buildJsonObject {
                            put("type", "integer")
                            put("minimum", 0)
                        },
                    ),
                ),
                ui = Ui(
                    view = Views.LIST,
                    item = ItemUi(title = "title", subtitle = "summary", meta = "createdAt", value = "total", badge = "state"),
                    search = "q",
                    chips = "tab",
                    open = "orders.get",
                    empty = words.t("Заказов нет", "No orders"),
                ),
            ),
            DeclaredMethod(
                name = "orders.get",
                kind = DeclaredMethod.KIND_QUERY,
                title = words.t("Заказ", "Order"),
                model = "order",
                input = objectSchema(listOf("id"), mapOf("id" to stringProperty(100, minLength = 1))),
                ui = Ui(view = Views.DETAIL, item = ItemUi(title = "title", subtitle = "summary", badge = "state")),
            ),
            DeclaredMethod(
                name = "orders.setState",
                kind = DeclaredMethod.KIND_MUTATION,
                title = words.t("Сменить статус", "Change state"),
                input = setStateInput(words, TARGETS),
                ui = Ui(
                    view = Views.ACTION,
                    on = "order",
                    placement = listOf("detail"),
                    control = Control(
                        type = Controls.BUTTONS,
                        param = "to",
                        tones = mapOf(OrderState.DONE.name to Tones.SUCCESS, OrderState.REJECT.name to Tones.DANGER),
                    ),
                    confirm = words.t("Отклонить заказ? Клиент получит уведомление.", "Reject the order? The customer will be told."),
                ),
            ),
        ),
    )
}
