package cx.m42.restoapp.orders

import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * An order's state, named as RestoApp core names it (`libs/OrderStateFlow.ts`). Only the states an
 * operator sees: CART, CHECKOUT and PAYMENT belong to the customer and never reach this screen.
 */
@Serializable
internal enum class OrderState {
    ORDER, COOKING, ON_THE_WAY, DONE, REJECT;

    val active: Boolean get() = this == ORDER || this == COOKING || this == ON_THE_WAY
}

/** core's `OrderStateFlow`, cut to the operator's states (`ORDER_OPERATOR_ALLOWED_TARGET_STATES`). */
private val Flow: Map<OrderState, List<OrderState>> = mapOf(
    OrderState.ORDER to listOf(OrderState.COOKING, OrderState.ON_THE_WAY, OrderState.DONE, OrderState.REJECT),
    OrderState.COOKING to listOf(OrderState.ON_THE_WAY, OrderState.DONE, OrderState.REJECT),
    OrderState.ON_THE_WAY to listOf(OrderState.DONE, OrderState.REJECT),
    OrderState.DONE to emptyList(),
    OrderState.REJECT to emptyList(),
)

/** One line of an order: core's `OrderDish`, without modifiers yet. */
@Serializable
internal data class OrderLine(val name: String, val amount: Int, val price: Int) {
    val total: Int get() = amount * price
}

/**
 * An order as the kanban sees it — the shape of core's `mapOrder` plus its dishes. Money in whole
 * roubles: a demo has no kopecks.
 */
@Serializable
internal data class Order(
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
) {
    val basketTotal: Int get() = lines.sumOf { it.total }
    val total: Int get() = basketTotal + deliveryCost
    val dishesCount: Int get() = lines.sumOf { it.amount }

    /**
     * Where it may go from here. A pickup is never "on the way": core allows it, but an operator
     * pressing it for a customer standing at the counter is a mistake, not a choice.
     */
    fun next(): List<OrderState> =
        Flow.getValue(state).filterNot { selfService && it == OrderState.ON_THE_WAY }
}

/** A plausible evening at one restaurant: twelve orders, most of them still in the kitchen. */
internal object DemoOrders {

    private val dishes = listOf(
        "Пепперони 30 см" to 590,
        "Маргарита 30 см" to 490,
        "Четыре сыра 30 см" to 650,
        "Филадельфия" to 560,
        "Калифорния" to 470,
        "Том ям" to 420,
        "Борщ" to 320,
        "Цезарь с курицей" to 380,
        "Морс клюквенный 0,5 л" to 150,
        "Лимонад домашний" to 190,
        "Чизкейк" to 280,
        "Тирамису" to 310,
    )

    private val customers = listOf(
        "Анна Смирнова", "Игорь Петров", "Мария Козлова", "Дмитрий Волков", "Елена Новикова",
        "Сергей Морозов", "Ольга Лебедева", "Алексей Соколов", "Наталья Фёдорова", "Павел Орлов",
    )

    private val addresses = listOf(
        "ул. Ленина, 12, кв. 45",
        "пр. Мира, 7, подъезд 2, кв. 18",
        "ул. Садовая, 3",
        "ул. Гагарина, 28, кв. 9",
        "Набережная, 15, этаж 4",
    )

    private val comments = listOf("Без лука, пожалуйста", "Позвоните за 10 минут", "Домофон не работает", null, null, null)

    /** State and age in minutes, newest first. */
    private val plan = listOf(
        OrderState.ORDER to 2, OrderState.ORDER to 6, OrderState.ORDER to 11,
        OrderState.COOKING to 14, OrderState.COOKING to 21, OrderState.COOKING to 27,
        OrderState.ON_THE_WAY to 34, OrderState.ON_THE_WAY to 48,
        OrderState.DONE to 75, OrderState.REJECT to 96, OrderState.DONE to 130, OrderState.DONE to 190,
    )

    fun generate(random: Random, now: Long): List<Order> {
        val first = 1000 + random.nextInt(9000)
        return plan.mapIndexed { index, (state, minutes) ->
            val selfService = random.nextInt(3) == 0
            val lines = dishes.shuffled(random).take(1 + random.nextInt(4)).map { (name, price) ->
                OrderLine(name, 1 + random.nextInt(2), price)
            }
            Order(
                id = "o${first + plan.size - index}",
                number = first + plan.size - index,
                state = state,
                lines = lines,
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
    }

    private fun phone(random: Random): String {
        fun digits(n: Int) = (1..n).joinToString("") { random.nextInt(10).toString() }
        return "+7 9${digits(2)} ${digits(3)}-${digits(2)}-${digits(2)}"
    }
}

/** 1330 → "1 330 ₽", with a no-break space so the sum never splits across lines. */
internal fun rub(amount: Int): String {
    val digits = amount.toString()
    val grouped = digits.reversed().chunked(3).joinToString(" ").reversed()
    return "$grouped ₽"
}
