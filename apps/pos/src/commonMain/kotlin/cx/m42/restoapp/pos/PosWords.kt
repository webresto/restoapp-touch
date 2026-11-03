package cx.m42.restoapp.pos

/** The POS has words of its own: the server sends an order, not an interface. */
public class PosWords(public val lang: String) {
    private val ru = lang.startsWith("ru")

    private fun t(ru: String, en: String) = if (this.ru) ru else en

    public val drafts: String = t("Незакрытые заказы", "Open orders")
    public val noDrafts: String = t("Здесь пока ничего нет", "Nothing here yet")
    public val noDraftsHint: String = t("Нажмите «Новый заказ», чтобы начать.", "Tap «New order» to start.")
    public val newOrder: String = t("Новый заказ", "New order")
    public val add: String = t("+ Добавить", "+ Add")
    public val addLater: String = t("Каталог появится в следующей версии", "The catalog comes in the next version")
    public val emptyReceipt: String = t("В заказе пока ничего нет", "Nothing in this order yet")
    public val drop: String = t("Бросить черновик", "Drop the draft")
    public val dropConfirm: String = t("Бросить этот заказ?", "Drop this order?")
    public val back: String = t("← К заказам", "← To the orders")
    public val basket: String = t("Блюда", "Products")
    public val discount: String = t("Скидка", "Discount")
    public val delivery: String = t("Доставка", "Delivery")
    public val bonuses: String = t("Бонусами", "Bonuses")
    public val total: String = t("Итого", "Total")
    public val customer: String = t("Гость", "Guest")
    public val noCustomer: String = t("не указан", "not given")
    public val table: String = t("Столик", "Table")
    public val cooks: String = t("Готовит", "Cooked at")
    public val retry: String = t("Повторить", "Retry")
    public val loading: String = t("Загружаем…", "Loading…")
    public val noPlaces: String = t("Точки не назначены — обратитесь к администратору", "No points are assigned — ask your administrator")
    public val places: String = t("Точки", "Points")

    public fun serviceType(type: String): String = when (type) {
        PosOrder.DELIVERY -> t("Доставка", "Delivery")
        PosOrder.PICKUP -> t("Самовывоз", "Pickup")
        PosOrder.DINE_IN -> t("В зале", "Dine-in")
        // A server that grew a fourth way of handing food over: its own word, not a crash.
        else -> type
    }

    public fun positions(count: Int): String = if (ru) {
        val rest = count % 100
        val one = count % 10
        when {
            rest in 11..14 -> "$count позиций"
            one == 1 -> "$count позиция"
            one in 2..4 -> "$count позиции"
            else -> "$count позиций"
        }
    } else {
        if (count == 1) "$count item" else "$count items"
    }
}
