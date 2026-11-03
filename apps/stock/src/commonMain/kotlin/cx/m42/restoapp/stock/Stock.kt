package cx.m42.restoapp.stock

import kotlin.random.Random
import kotlinx.serialization.Serializable

/**
 * One dish and what is left of it — RestoApp core's `Dish` cut to what the stock manager shows.
 *
 * [balance] keeps core's convention (`models/Dish.ts`): -1 is unlimited, 0 is out of stock ("on
 * stop"), anything above is how many are left. [enabled] is core's `enable`, the admin's on/off.
 */
@Serializable
internal data class StockItem(
    val id: String,
    val name: String,
    val group: String,
    val price: Int,
    val unit: String,
    val balance: Int,
    val enabled: Boolean = true,
) {
    val unlimited: Boolean get() = balance < 0
    val limited: Boolean get() = balance >= 0
    val onStop: Boolean get() = balance == 0 || !enabled
}

/** The three filters over the list; [Limited] is core's "Товары с ограниченным остатком". */
internal enum class Filter {
    Limited, Stop, All;

    fun shows(item: StockItem): Boolean = when (this) {
        Limited -> item.limited
        Stop -> item.onStop
        All -> true
    }
}

/** A menu of eighteen dishes, a third of them counted and a couple run out. */
internal object DemoStock {

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

    fun generate(random: Random): List<StockItem> {
        // Two out, five counted, the rest unlimited — shuffled, so every server runs out of
        // something different.
        val balances = (listOf(0, 0) + List(5) { 1 + random.nextInt(12) } + List(menu.size - 7) { -1 })
            .shuffled(random)
        val switchedOff = random.nextInt(menu.size)
        return menu.mapIndexed { index, (group, name, price) ->
            StockItem(
                id = "d${index + 1}",
                name = name,
                group = group,
                price = price,
                unit = if (group == "Супы") "порц." else "шт",
                balance = balances[index],
                enabled = index != switchedOff,
            )
        }
    }
}

/** 590 → "590 ₽", with a no-break space so the sum never splits across lines. */
internal fun rub(amount: Int): String {
    val grouped = amount.toString().reversed().chunked(3).joinToString(" ").reversed()
    return "$grouped ₽"
}
