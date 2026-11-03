package cx.m42.restoapp.stock

/** The app's own words. "Безлимит", "Нет в наличии" and the rest are core's (`ru.json`). */
internal interface StockStrings {
    val limited: String
    val stop: String
    val all: String
    val search: String
    val allInStock: String
    val nothingFound: String
    val unlimited: String
    val outOfStock: String
    val off: String
    val back: String
    val balance: String
    val balanceHint: String
    val setBalance: String
    val sale: String
    val onSale: String
    val resetDemo: String
    val demoHint: String

    fun left(count: Int, unit: String): String
}

internal object StockStringsRu : StockStrings {
    override val limited = "Ограниченные"
    override val stop = "На стопе"
    override val all = "Все"
    override val search = "Поиск блюда"
    override val allInStock = "Все товары в наличии!"
    override val nothingFound = "Ничего не найдено"
    override val unlimited = "Безлимит"
    override val outOfStock = "Нет в наличии"
    override val off = "Выкл"
    override val back = "Склад"
    override val balance = "Остаток"
    override val balanceHint = "Сколько осталось. 0 — на стопе"
    override val setBalance = "Установить баланс"
    override val sale = "Продажа"
    override val onSale = "В продаже"
    override val resetDemo = "Сбросить демо-данные"
    override val demoHint = "Демо-данные этого сервера: хранятся только на этом компьютере"

    override fun left(count: Int, unit: String) = "$count $unit"
}

internal object StockStringsEn : StockStrings {
    override val limited = "Limited"
    override val stop = "On stop"
    override val all = "All"
    override val search = "Find a dish"
    override val allInStock = "Everything is in stock!"
    override val nothingFound = "Nothing found"
    override val unlimited = "Unlimited"
    override val outOfStock = "Out of stock"
    override val off = "Off"
    override val back = "Stock"
    override val balance = "Stock"
    override val balanceHint = "How many are left. 0 puts it on stop"
    override val setBalance = "Set balance"
    override val sale = "Sale"
    override val onSale = "On sale"
    override val resetDemo = "Reset demo data"
    override val demoHint = "This server's demo data: kept on this computer only"

    override fun left(count: Int, unit: String) = "$count $unit"
}

internal val StockStringTables: Map<String, StockStrings> = mapOf("ru" to StockStringsRu, "en" to StockStringsEn)
