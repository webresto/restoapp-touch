package cx.m42.restoapp.pos

import cx.m42.restoapp.render.dateTime

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

    // ---- the catalog ----
    public val catalog: String = t("Каталог", "Catalog")
    public val toReceipt: String = t("← В чек", "← To the receipt")
    public val searchProduct: String = t("Блюдо или код", "A product or a code")
    public val nothingFound: String = t("Ничего не нашлось", "Nothing found")
    public val stopped: String = t("Стоп", "Stopped")
    public val left: String = t("Осталось", "Left")
    public val cooking: String = t("Готовка", "Cooking")
    public val withOptions: String = t("с опциями по умолчанию", "with the default options")
    public val more: String = t("Показать ещё", "Show more")

    // ---- the steps of the checkout ----
    public val receiving: String = t("Получение", "Receiving")
    public val guest: String = t("Гость", "Guest")
    public val address: String = t("Адрес", "Address")
    public val time: String = t("Время", "Time")
    public val payment: String = t("Оплата", "Payment")
    public val promocode: String = t("Промокод", "Promo code")
    public val details: String = t("Детали", "Details")
    public val comment: String = t("Комментарий", "Comment")
    public val persons: String = t("Гостей", "Guests")
    public val notSet: String = t("не выбрано", "not chosen")
    public val asap: String = t("Как можно скорее", "As soon as possible")
    public val byTime: String = t("Ко времени", "By a time")
    public val maxWait: String = t("Не дольше", "No longer than")
    public val minutes: String = t("мин", "min")
    public val name: String = t("Имя", "Name")
    public val phone: String = t("Телефон", "Phone")
    public val createClient: String = t("Завести клиента", "Create a customer")
    public val findClient: String = t("Найти клиента", "Find a customer")
    public val searchClient: String = t("Телефон или имя", "A phone or a name")
    public val city: String = t("Город", "City")
    public val street: String = t("Улица", "Street")
    public val house: String = t("Дом", "House")
    public val apartment: String = t("Квартира", "Apartment")
    public val savedAddresses: String = t("Сохранённые адреса", "Saved addresses")
    public val save: String = t("Сохранить", "Save")
    public val clear: String = t("Снять", "Clear")
    public val check: String = t("Проверить заказ", "Check the order")
    public val placeOrder: String = t("Оформить заказ", "Place the order")
    public val placeConfirm: String = t("Оформить заказ?", "Place the order?")
    public val accepted: String = t("Заказ принят", "Order accepted")
    public val toDrafts: String = t("К заказам", "To the orders")
    public val newOrderAgain: String = t("Новый заказ", "New order")

    /** A moment as the renderer prints one: the same words on both screens of the same server. */
    public fun clock(epochMillis: Long, timezone: String? = null): String = dateTime(epochMillis, timezone, lang)

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
