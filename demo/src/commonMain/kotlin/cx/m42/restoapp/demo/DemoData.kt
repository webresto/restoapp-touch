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

/** Everything the demo server keeps, as one JSON in the server's own storage. */
@Serializable
internal data class DemoState(
    val version: Int = 1,
    val role: DemoRole = DemoRole.Admin,
    /** placeId → dishId → row; a dish without a row is unlimited at that point. */
    val stock: Map<String, Map<String, DishPlaceRow>> = emptyMap(),
    /** placeId → its orders. */
    val orders: Map<String, List<DemoOrder>> = emptyMap(),
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
