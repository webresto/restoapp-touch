package cx.m42.restoapp.demo

import kotlin.random.Random
import kotlinx.serialization.Serializable

/*
 * The demo server's data: the menu of the old native «Склад» and the evening of the old «Заказы»,
 * now per point — core's `DishPlace` (manual and RMS balance, stop at the point) and orders that
 * belong to one point each.
 */

/** Who the person is on the demo server: what the role switch in Settings picks. */
@Serializable
public enum class DemoRole {
    /** Everything, at every point — as an administrator of the real server. */
    Admin,

    /** «Заказы» only, at the first point: the group `Operator` with `order-kanban`. */
    Operator,

    /** «Блюда» only, at every point: the group `Stock Manager` with `stock-manager`. */
    Stock,

    /**
     * «Маркетинг» only: the group `Marketer` with `promocodes-manager` and `promotions-manager`, and without
     * the right to read an order — so the summary counts the codes and the promotions and says nothing about
     * the money, exactly as the declaration of such a person has it (`marketing.plan.md`).
     */
    Marketer,
}

/** A dish of the catalog — core's `Dish`, cut to what the stock manager sees. */
internal data class DemoDish(val id: String, val name: String, val code: String, val group: String, val price: Int)

/** core's `DishPlace`: no row is unlimited and on sale. */
@Serializable
internal data class DishPlaceRow(
    val localBalance: Int? = null,
    val rmsBalance: Int? = null,
    val enable: Boolean = true,
)

internal const val UNLIMITED: Int = -1

/** core's `getEffectiveBalance`, mode `minimum`: a stop at the point wins, then the smaller limit. */
internal fun DishPlaceRow?.effective(): Int {
    if (this == null) return UNLIMITED
    if (!enable) return 0
    val limits = listOfNotNull(localBalance, rmsBalance).filter { it >= 0 }
    return limits.minOrNull() ?: UNLIMITED
}

@Serializable
internal enum class OrderState {
    ORDER, COOKING, ON_THE_WAY, DONE, REJECT;

    val active: Boolean get() = this == ORDER || this == COOKING || this == ON_THE_WAY
}

/** core's `OrderStateFlow`, cut to the operator's targets. A pickup is never "on the way". */
internal fun DemoOrder.next(): List<OrderState> = when (state) {
    OrderState.ORDER -> listOf(OrderState.COOKING, OrderState.ON_THE_WAY, OrderState.DONE, OrderState.REJECT)
    OrderState.COOKING -> listOf(OrderState.ON_THE_WAY, OrderState.DONE, OrderState.REJECT)
    OrderState.ON_THE_WAY -> listOf(OrderState.DONE, OrderState.REJECT)
    OrderState.DONE, OrderState.REJECT -> emptyList()
}.filterNot { selfService && it == OrderState.ON_THE_WAY }

@Serializable
internal data class OrderLine(val name: String, val amount: Int, val price: Int)

@Serializable
internal data class DemoOrder(
    val id: String,
    val number: Int,
    val state: OrderState,
    val lines: List<OrderLine>,
    val deliveryCost: Int,
    val customerName: String,
    val customerPhone: String,
    val selfService: Boolean,
    val address: String? = null,
    val comment: String? = null,
    val paid: Boolean,
    /** Epoch millis. */
    val createdAt: Long,
    val updatedAt: Long = createdAt,
) {
    val total: Int get() = lines.sumOf { it.amount * it.price } + deliveryCost
}

/** A customer of the demo — core's `user`, cut to what «Клиенты» shows. */
@Serializable
internal data class DemoClient(
    val id: String,
    val firstName: String,
    val phone: String,
    val email: String? = null,
    val verified: Boolean = false,
    val orderCount: Int = 0,
    val createdAt: Long = 0,
    val addresses: List<String> = emptyList(),
) {
    val title: String get() = firstName.ifBlank { phone }
}

/** One chosen modifier of a line: what the kitchen reads off it. */
@Serializable
internal data class DemoLineOption(val id: String, val name: String, val amount: Int)

/** One line of a POS draft: the product, how many, and what the demo counts for it. */
@Serializable
internal data class DemoDraftLine(
    val lineId: Int,
    val dishId: String,
    val name: String,
    val amount: Int,
    val price: Int,
    val oldPrice: Int? = null,
    val comment: String? = null,
    val hasOptions: Boolean = false,
    val options: List<DemoLineOption> = emptyList(),
) {
    val total: Int get() = amount * price
}

/**
 * A draft of the POS: a cart of the demo, at one point, by one person.
 *
 * It holds what the hook's draft holds, because the client must not be able to tell the two apart:
 * the demo counts the same totals, refuses the same steps and places the order into the demo's own
 * «Заказы» at the end.
 */
@Serializable
internal data class DemoDraft(
    val id: String,
    val number: String,
    val place: String,
    val serviceType: String,
    val clientId: String? = null,
    val customerName: String? = null,
    val customerPhone: String? = null,
    val createdAt: Long = 0,
    val state: String = "CART",
    val table: String? = null,
    val lines: List<DemoDraftLine> = emptyList(),
    val address: String? = null,
    val comment: String? = null,
    val personsCount: Int? = null,
    val date: Long? = null,
    val maxWaitMinutes: Int? = null,
    val paymentMethodId: String? = null,
    val paymentMethodTitle: String? = null,
    val promotionCode: String? = null,
    val nextLineId: Int = 1,
) {
    val basketTotal: Int get() = lines.sumOf { it.total }

    /** The demo's promotion: the one code there is takes a tenth off. */
    val discountTotal: Int get() = if (promotionCode == PROMOCODE) basketTotal / 10 else 0

    /** A delivery of the demo costs a flat 200 until the basket is big enough. */
    val deliveryCost: Int get() = if (serviceType == "delivery" && basketTotal in 1..1499) 200 else 0

    val total: Int get() = basketTotal - discountTotal + deliveryCost

    val dishesCount: Int get() = lines.sumOf { it.amount }

    val canCheck: Boolean get() = dishesCount > 0 && !customerPhone.isNullOrBlank() &&
        (serviceType != "delivery" || !address.isNullOrBlank())

    val canPlace: Boolean get() = state == "CHECKOUT"

    internal companion object {
        /** The only code the demo knows; anything else is refused, as a real one refuses. */
        const val PROMOCODE: String = "DEMO10"
    }
}

/**
 * A promotion of the demo — core's `Promotion` with the two switches of C22: `enable` is "works at all" and
 * `onlyByCode` is "does not apply by itself".
 */
@Serializable
internal data class DemoPromotion(
    val id: String,
    val name: String,
    val enable: Boolean = true,
    val onlyByCode: Boolean = false,
    /** false — written by a module: only its switches and its order may be changed. */
    val configured: Boolean = true,
    val percent: Int = 0,
    val flat: Int = 0,
    /** How many items the gift is, and from which basket; 0 — no gift. */
    val giftAmount: Int = 0,
    val giftFrom: Int = 0,
    val minBasketTotal: Int = 0,
    val joint: Boolean = true,
    val onStorefront: Boolean = true,
    val sortOrder: Int = 0,
    val serviceTypes: List<String> = emptyList(),
    val dishes: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
)

/**
 * A promo code of the demo — core's `PromotionCode`. Its status and the words of its term are kept as they are
 * rather than computed from days: the demo shows the screens, and core is what judges a date.
 */
@Serializable
internal data class DemoCode(
    val id: String,
    val code: String,
    val description: String,
    val enable: Boolean = true,
    /** `active`, `scheduled` or `expired`; switched off, a code is `disabled` whatever this says. */
    val status: String = "active",
    val period: String = "без ограничения",
    val schedule: String = "круглосуточно",
    val promotions: List<String> = emptyList(),
) {
    val effectiveStatus: String get() = if (!enable) "disabled" else status

    val worksNow: Boolean get() = effectiveStatus == "active"
}

/** Where a code was typed: core has no model of it either — it is read off the orders. */
@Serializable
internal data class DemoApplication(
    val orderId: String,
    val shortId: Int,
    val code: String,
    val applied: Boolean,
    val discount: Int,
    val createdAt: Long,
    /** Why it gave nothing; null — it applied. */
    val reason: String? = null,
    val orderState: String = "DONE",
)

/** Everything the demo server keeps, as one JSON in the server's own storage. */
@Serializable
internal data class DemoState(
    val version: Int = 1,
    val role: DemoRole = DemoRole.Admin,
    /** placeId → dishId → row; a dish without a row is unlimited at that point. */
    val stock: Map<String, Map<String, DishPlaceRow>> = emptyMap(),
    /** placeId → its orders. */
    val orders: Map<String, List<DemoOrder>> = emptyMap(),
    val clients: List<DemoClient> = emptyList(),
    /** The POS drafts, newest last; one list for the demo's single operator. */
    val drafts: List<DemoDraft> = emptyList(),
    /** Numbers the demo gives new drafts. */
    val nextDraft: Int = 1,
    /** «Маркетинг»: the promotions, the codes and the applications of them. */
    val promotions: List<DemoPromotion> = emptyList(),
    val codes: List<DemoCode> = emptyList(),
    val applications: List<DemoApplication> = emptyList(),
)

internal object DemoCatalog {

    private val menu = listOf(
        Triple("Пицца", "Пепперони 30 см", 590),
        Triple("Пицца", "Маргарита 30 см", 490),
        Triple("Пицца", "Четыре сыра 30 см", 650),
        Triple("Пицца", "Гавайская 30 см", 560),
        Triple("Роллы", "Филадельфия", 560),
        Triple("Роллы", "Калифорния", 470),
        Triple("Роллы", "Запечённый с лососем", 520),
        Triple("Супы", "Том ям", 420),
        Triple("Супы", "Борщ", 320),
        Triple("Супы", "Куриный с лапшой", 290),
        Triple("Салаты", "Цезарь с курицей", 380),
        Triple("Салаты", "Греческий", 340),
        Triple("Напитки", "Морс клюквенный 0,5 л", 150),
        Triple("Напитки", "Лимонад домашний", 190),
        Triple("Напитки", "Кола 0,5 л", 120),
        Triple("Десерты", "Чизкейк", 280),
        Triple("Десерты", "Тирамису", 310),
        Triple("Десерты", "Медовик", 260),
    )

    val dishes: List<DemoDish> = menu.mapIndexed { index, (group, name, price) ->
        DemoDish(id = "d${index + 1}", name = name, code = "D" + (index + 1).toString().padStart(2, '0'), group = group, price = price)
    }

    /** One option of a group, as the demo keeps it: the server's shape, with its rules. */
    internal data class DemoOption(
        val id: String,
        val name: String,
        val price: Int,
        val freeAmount: Int = 0,
        val defaultAmount: Int = 0,
        val maxAmount: Int? = null,
    )

    /** A group of a dish: how many of it may be taken, and what of. */
    internal data class DemoOptionGroup(
        val id: String,
        val name: String,
        val minAmount: Int,
        val maxAmount: Int?,
        val options: List<DemoOption>,
    ) {
        val single: Boolean get() = maxAmount == 1
        val required: Boolean get() = minAmount > 0
    }

    /**
     * What the demo's pizzas and pilaf are composed of: a dough that must be chosen, a sauce with a
     * default, and extras counted one by one with the first of them free — the shapes a real menu
     * has, so the chooser is drawn against all of them.
     */
    val optionGroups: Map<String, List<DemoOptionGroup>> = run {
        val dough = DemoOptionGroup(
            id = "dough", name = "Тесто", minAmount = 1, maxAmount = 1,
            options = listOf(
                DemoOption("o-thin", "Тонкое", 0, defaultAmount = 1),
                DemoOption("o-thick", "Пышное", 60),
            ),
        )
        val sauce = DemoOptionGroup(
            id = "sauce", name = "Соус", minAmount = 1, maxAmount = 1,
            options = listOf(
                DemoOption("o-tomato", "Томатный", 0, defaultAmount = 1),
                DemoOption("o-cream", "Сливочный", 40),
                DemoOption("o-hot", "Острый", 40),
            ),
        )
        val extras = DemoOptionGroup(
            id = "extra", name = "Добавки", minAmount = 0, maxAmount = 4,
            options = listOf(
                DemoOption("o-cheese", "Сыр", 80, freeAmount = 1, maxAmount = 3),
                DemoOption("o-olives", "Оливки", 50, maxAmount = 2),
                DemoOption("o-jalapeno", "Халапеньо", 50, maxAmount = 2),
            ),
        )
        val pizzas = dishes.filter { it.group == "Пицца" }.map { it.id }
        // One soup with a sauce and extras, so a group without a dough is drawn too — the second
        // one, because the first of a group is what the tests and the screenshots reach for when
        // they want a product that simply goes into the receipt.
        val soup = dishes.filter { it.group == "Супы" }.drop(1).take(1).map { it.id }
        (pizzas.associateWith { listOf(dough, sauce, extras) } + soup.associateWith { listOf(sauce, extras) })
    }

    /** The groups in menu order: the catalog is sorted by group as the server sorts by `parentGroup`. */
    val groupOrder: List<String> = menu.map { it.first }.distinct()

    /**
     * One point's stock: two dishes run out, five counted, one stopped at the point, two drinks with
     * a balance from RMS — the rest have no row and are unlimited. Shuffled per point, so the two
     * points visibly differ.
     */
    fun stock(random: Random): Map<String, DishPlaceRow> {
        val shuffled = dishes.shuffled(random)
        val rows = LinkedHashMap<String, DishPlaceRow>()
        shuffled.take(2).forEach { rows[it.id] = DishPlaceRow(localBalance = 0) }
        shuffled.drop(2).take(5).forEach { rows[it.id] = DishPlaceRow(localBalance = 1 + random.nextInt(12)) }
        shuffled.drop(7).take(1).forEach { rows[it.id] = DishPlaceRow(enable = false) }
        dishes.filter { it.group == "Напитки" && it.id !in rows }.take(2).forEach {
            rows[it.id] = DishPlaceRow(rmsBalance = 5 + random.nextInt(40))
        }
        return rows
    }
}

/** The customers the demo knows: enough to search by digits and by name. */
internal object DemoClients {

    private val people = listOf(
        Triple("Анна Смирнова", "+7 912 000-11-22", 5),
        Triple("Игорь Петров", "+7 913 555-00-33", 2),
        Triple("Мария Козлова", "+7 914 777-20-10", 11),
        Triple("Дмитрий Волков", "+7 915 303-44-55", 0),
        Triple("Елена Новикова", "+7 916 808-90-01", 3),
    )

    fun all(now: Long): List<DemoClient> = people.mapIndexed { index, (name, phone, orders) ->
        DemoClient(
            id = "u${index + 1}",
            firstName = name,
            phone = phone,
            email = if (index == 0) "anna@example.com" else null,
            verified = index % 2 == 0,
            orderCount = orders,
            createdAt = now - (index + 1) * 86_400_000L,
            addresses = if (orders > 0) listOf("ул. Ленина, 12, кв. 45") else emptyList(),
        )
    }
}

/** The streets the demo's address search knows. */
internal object DemoAddresses {
    val streets: List<String> = listOf(
        "ул. Ленина", "ул. Садовая", "пр. Мира", "ул. Гагарина", "Набережная", "ул. Пушкина",
    )
}

/** A plausible evening at one point: twelve orders, most of them still in the kitchen. */
internal object DemoEvening {

    private val dishes = listOf(
        "Пепперони 30 см" to 590, "Маргарита 30 см" to 490, "Четыре сыра 30 см" to 650,
        "Филадельфия" to 560, "Калифорния" to 470, "Том ям" to 420, "Борщ" to 320,
        "Цезарь с курицей" to 380, "Морс клюквенный 0,5 л" to 150, "Лимонад домашний" to 190,
        "Чизкейк" to 280, "Тирамису" to 310,
    )

    private val customers = listOf(
        "Анна Смирнова", "Игорь Петров", "Мария Козлова", "Дмитрий Волков", "Елена Новикова",
        "Сергей Морозов", "Ольга Лебедева", "Алексей Соколов", "Наталья Фёдорова", "Павел Орлов",
    )

    private val addresses = listOf(
        "ул. Ленина, 12, кв. 45", "пр. Мира, 7, подъезд 2, кв. 18", "ул. Садовая, 3",
        "ул. Гагарина, 28, кв. 9", "Набережная, 15, этаж 4",
    )

    private val comments = listOf("Без лука, пожалуйста", "Позвоните за 10 минут", "Домофон не работает", null, null, null)

    /** State and age in minutes, newest first. */
    private val plan = listOf(
        OrderState.ORDER to 2, OrderState.ORDER to 6, OrderState.ORDER to 11,
        OrderState.COOKING to 14, OrderState.COOKING to 21, OrderState.COOKING to 27,
        OrderState.ON_THE_WAY to 34, OrderState.ON_THE_WAY to 48,
        OrderState.DONE to 75, OrderState.REJECT to 96, OrderState.DONE to 130, OrderState.DONE to 190,
    )

    fun orders(random: Random, now: Long, firstNumber: Int): List<DemoOrder> =
        plan.mapIndexed { index, (state, minutes) ->
            val selfService = random.nextInt(3) == 0
            val number = firstNumber + plan.size - index
            DemoOrder(
                id = "o$number",
                number = number,
                state = state,
                lines = dishes.shuffled(random).take(1 + random.nextInt(4)).map { (name, price) -> OrderLine(name, 1 + random.nextInt(2), price) },
                deliveryCost = if (selfService) 0 else listOf(0, 150, 200).random(random),
                customerName = customers.random(random),
                customerPhone = phone(random),
                selfService = selfService,
                address = if (selfService) null else addresses.random(random),
                comment = comments.random(random),
                paid = state == OrderState.DONE || random.nextBoolean(),
                createdAt = now - minutes * 60_000L,
            )
        }

    private fun phone(random: Random): String {
        fun digits(n: Int) = (1..n).joinToString("") { random.nextInt(10).toString() }
        return "+7 9${digits(2)} ${digits(3)}-${digits(2)}-${digits(2)}"
    }
}

/**
 * «Маркетинг» of the demo: three codes — one that works, one planned and one that ended — and three promotions:
 * one that applies by itself, one only by its code, and one that gives a present. Exactly the set the plan asks
 * the demo server for, so every word and tone of the section has something to stand on.
 */
internal object DemoMarketing {

    val promotions: List<DemoPromotion> = listOf(
        DemoPromotion(id = "pr-summer", name = "Летняя скидка", onlyByCode = true, percent = 10, onStorefront = false),
        DemoPromotion(id = "pr-lunch", name = "Бизнес-ланч", flat = 150, minBasketTotal = 700, serviceTypes = listOf("dine-in"), sortOrder = 1),
        DemoPromotion(id = "pr-gift", name = "Подарок к заказу", giftAmount = 2, giftFrom = 1449, sortOrder = 2),
        DemoPromotion(id = "pr-old", name = "Прошлогодняя акция", enable = false, configured = false, percent = 5, sortOrder = 9),
    )

    val codes: List<DemoCode> = listOf(
        DemoCode(
            id = "code-leto", code = "ЛЕТО10", description = "Скидка 10% по коду из рассылки",
            status = "active", period = "с 01.06.2026 по 31.08.2026", schedule = "пн-пт 10:00-22:00",
            promotions = listOf("pr-summer"),
        ),
        DemoCode(
            id = "code-autumn", code = "ОСЕНЬ", description = "Откроется к сентябрю",
            status = "scheduled", period = "с 01.09.2026", promotions = listOf("pr-gift"),
        ),
        DemoCode(
            id = "code-spring", code = "ВЕСНА", description = "Весенняя рассылка, закончилась",
            status = "expired", period = "по 31.05.2026", promotions = listOf("pr-lunch"),
        ),
        DemoCode(
            id = "code-dead", code = "СТАРЫЙ", description = "Код прошлогодней акции",
            status = "active", promotions = listOf("pr-old"),
        ),
    )

    /** Where the codes were typed: a day apart, so the chips of the period have something to cut. */
    fun applications(now: Long): List<DemoApplication> {
        val day = 24 * 60 * 60 * 1000L
        return listOf(
            DemoApplication(orderId = "ap-1", shortId = 1042, code = "ЛЕТО10", applied = true, discount = 118, createdAt = now - 2 * 60 * 60 * 1000),
            DemoApplication(orderId = "ap-2", shortId = 1041, code = "ЛЕТО10", applied = false, discount = 0, createdAt = now - day, reason = "корзина меньше 700 ₽", orderState = "CART"),
            DemoApplication(orderId = "ap-3", shortId = 1035, code = "СТАРЫЙ", applied = false, discount = 0, createdAt = now - 10 * day, reason = "все акции кода выключены — код ничего не даёт", orderState = "CART"),
            DemoApplication(orderId = "ap-4", shortId = 1012, code = "ВЕСНА", applied = false, discount = 0, createdAt = now - 40 * day, reason = "код не подошёл или истёк", orderState = "CART"),
        )
    }
}
