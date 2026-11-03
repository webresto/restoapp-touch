package cx.m42.restoapp.orders

/** The app's own words. The state names are core's (`lib/adminpanel/i18n/locales/ru.json`). */
internal interface OrdersStrings {
    val active: String
    val done: String
    val all: String
    val noActive: String
    val noOrders: String
    val back: String
    val customer: String
    val receiving: String
    val pickup: String
    val delivery: String
    val comment: String
    val contents: String
    val deliveryCost: String
    val total: String
    val paid: String
    val notPaid: String
    val resetDemo: String
    val demoHint: String

    fun state(state: OrderState): String
    fun action(state: OrderState): String
    fun title(number: Int): String
    fun dishes(count: Int): String
    fun ago(minutes: Long): String
    fun created(ago: String): String
}

internal object OrdersStringsRu : OrdersStrings {
    override val active = "Активные"
    override val done = "Завершённые"
    override val all = "Все"
    override val noActive = "Активных заказов нет"
    override val noOrders = "Заказов нет"
    override val back = "Все заказы"
    override val customer = "Клиент"
    override val receiving = "Получение"
    override val pickup = "Самовывоз"
    override val delivery = "Доставка"
    override val comment = "Комментарий"
    override val contents = "Состав"
    override val deliveryCost = "Доставка"
    override val total = "Итого"
    override val paid = "Оплачен"
    override val notPaid = "Не оплачен"
    override val resetDemo = "Сбросить демо-данные"
    override val demoHint = "Демо-данные этого сервера: хранятся только на этом компьютере"

    override fun state(state: OrderState) = when (state) {
        OrderState.ORDER -> "Заказан"
        OrderState.COOKING -> "Готовится"
        OrderState.ON_THE_WAY -> "В пути"
        OrderState.DONE -> "Доставлен"
        OrderState.REJECT -> "Отклонён"
    }

    override fun action(state: OrderState) = when (state) {
        OrderState.ORDER -> "Заказан"
        OrderState.COOKING -> "Начать готовить"
        OrderState.ON_THE_WAY -> "Передать курьеру"
        OrderState.DONE -> "Завершить"
        OrderState.REJECT -> "Отклонить"
    }

    override fun title(number: Int) = "Заказ №$number"

    override fun dishes(count: Int): String {
        val word = when {
            count % 100 in 11..14 -> "позиций"
            count % 10 == 1 -> "позиция"
            count % 10 in 2..4 -> "позиции"
            else -> "позиций"
        }
        return "$count $word"
    }

    override fun ago(minutes: Long) = when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        minutes < 24 * 60 -> "${minutes / 60} ч назад"
        else -> "${minutes / (24 * 60)} дн назад"
    }

    override fun created(ago: String) = "Создан $ago"
}

internal object OrdersStringsEn : OrdersStrings {
    override val active = "Active"
    override val done = "Completed"
    override val all = "All"
    override val noActive = "No active orders"
    override val noOrders = "No orders"
    override val back = "All orders"
    override val customer = "Customer"
    override val receiving = "Receiving"
    override val pickup = "Pickup"
    override val delivery = "Delivery"
    override val comment = "Comment"
    override val contents = "Items"
    override val deliveryCost = "Delivery"
    override val total = "Total"
    override val paid = "Paid"
    override val notPaid = "Not paid"
    override val resetDemo = "Reset demo data"
    override val demoHint = "This server's demo data: kept on this computer only"

    override fun state(state: OrderState) = when (state) {
        OrderState.ORDER -> "Ordered"
        OrderState.COOKING -> "Cooking"
        OrderState.ON_THE_WAY -> "On the way"
        OrderState.DONE -> "Delivered"
        OrderState.REJECT -> "Rejected"
    }

    override fun action(state: OrderState) = when (state) {
        OrderState.ORDER -> "Ordered"
        OrderState.COOKING -> "Start cooking"
        OrderState.ON_THE_WAY -> "Hand to courier"
        OrderState.DONE -> "Complete"
        OrderState.REJECT -> "Reject"
    }

    override fun title(number: Int) = "Order #$number"
    override fun dishes(count: Int) = if (count == 1) "1 item" else "$count items"

    override fun ago(minutes: Long) = when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }

    override fun created(ago: String) = "Created $ago"
}

internal val OrdersStringTables: Map<String, OrdersStrings> = mapOf("ru" to OrdersStringsRu, "en" to OrdersStringsEn)
