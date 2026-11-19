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
    public val add: String = t("Добавить", "Add")
    /** The cross of a sheet, for a screen reader. */
    public val close: String = t("Закрыть", "Close")
    public val emptyReceipt: String = t("В заказе пока ничего нет", "Nothing in this order yet")
    public val drop: String = t("Бросить черновик", "Drop the draft")
    public val dropConfirm: String = t("Бросить этот заказ?", "Drop this order?")
    public val back: String = t("К заказам", "To the orders")
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
    public val noPlacesHint: String = t("Касса работает на точке продаж: когда администратор назначит её, чеки появятся здесь.", "The till works at a point of sale: once your administrator assigns one, the receipts show up here.")

    public fun serviceType(type: String): String = when (type) {
        PosOrder.DELIVERY -> t("Доставка", "Delivery")
        PosOrder.PICKUP -> t("Самовывоз", "Pickup")
        PosOrder.DINE_IN -> t("В зале", "Dine-in")
        // A server that grew a fourth way of handing food over: its own word, not a crash.
        else -> type
    }

    // ---- the catalog ----
    public val catalog: String = t("Каталог", "Catalog")
    public val toReceipt: String = t("В чек", "To the receipt")
    public val searchProduct: String = t("Блюдо или код", "A product or a code")
    public val nothingFound: String = t("Ничего не нашлось", "Nothing found")
    public val stopped: String = t("Стоп", "Stopped")
    public val left: String = t("Осталось", "Left")
    public val cooking: String = t("Готовка", "Cooking")
    public val withOptions: String = t("можно выбрать состав", "options to choose")
    public val more: String = t("Показать ещё", "Show more")

    // ---- what goes with a dish ----
    public val options: String = t("Состав", "Options")
    public val addToReceipt: String = t("Добавить в чек", "Add to the receipt")
    public val mustChoose: String = t("обязательно", "required")
    public val chooseOne: String = t("один на выбор", "one of these")
    public val noOptions: String = t("У этого блюда нечего выбирать", "Nothing to choose for this product")

    /** «до 3» — the ceiling of a group, as the server sent it. */
    public fun upTo(count: Int): String = if (ru) "до $count" else "up to $count"

    /** «1 бесплатно» — how many of an option are not charged for. */
    public fun free(count: Int): String = if (ru) "$count бесплатно" else "$count free"

    public val byDefault: String = t("по умолчанию", "by default")

    // ---- the steps of the checkout ----
    public val receiving: String = t("Получение", "Receiving")
    public val guest: String = t("Гость", "Guest")
    public val address: String = t("Адрес", "Address")
    public val time: String = t("Время", "Time")
    public val payment: String = t("Оплата", "Payment")
    public val notOnTerminal: String = t("На терминале пока недоступно", "Not taken on a terminal yet")
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

    // ---- the new look: sections, the till, dialogs ----
    public val pos: String = t("Касса", "Till")
    public val receiptsTab: String = t("Чеки", "Receipts")
    public val receiptTab: String = t("Чек", "Receipt")
    public val backToReceipt: String = t("К чеку", "To the receipt")
    public val openReceipts: String = t("Открытые чеки", "Open receipts")
    public val allReceipts: String = t("Все чеки", "All receipts")
    public val newShort: String = t("Новый", "New")
    public val draft: String = t("Черновик", "Draft")
    public val moreActions: String = t("Ещё", "More")
    public val dropOrder: String = t("Бросить заказ", "Drop the order")
    public fun dropTitle(number: String): String = t("Бросить заказ № $number?", "Drop order № $number?")
    public fun dropText(count: Int): String =
        if (count == 0) t("Чек пустой, на кухню ничего не уходило.", "The receipt is empty; nothing went to the kitchen.")
        else t("Чек и ${positions(count)} в нём будут удалены. На кухню ничего не уходило.", "The receipt and its ${positions(count)} will be deleted. Nothing went to the kitchen.")
    public val dropYes: String = t("Бросить", "Drop")
    public val cancel: String = t("Отмена", "Cancel")
    public val done: String = t("Готово", "Done")
    public val addDishes: String = t("Добавить блюда", "Add products")
    public fun toOrder(number: String): String = t("в чек № $number", "to receipt № $number")
    public val emptyReceiptHint: String = t("Добавьте блюда из каталога", "Add products from the catalog")
    public val emptyReceiptWide: String = t("Нажмите на блюдо в каталоге слева", "Tap a product in the catalog on the left")
    public val orderSection: String = t("Заказ", "Order")
    public val perItem: String = t("за шт.", "each")
    public fun inReceipt(count: Int): String = if (count == 0) t("Чек пока пуст", "The receipt is empty") else t("В чеке ${positions(count)}", "${positions(count)} in the receipt")
    public val emptyYet: String = t("Пока пусто", "Empty yet")
    public val noGuest: String = t("Гость не указан", "No guest given")
    public val place: String = t("Оформить", "Place")
    public val removeLine: String = t("Убрать", "Remove")
    public val chooseGroup: String = t("Выберите группу", "Choose a group")
    public val chooseGroupHint: String = t("Или найдите блюдо по названию или коду.", "Or find a product by its name or code.")
    public fun nothingFoundHint(query: String): String = t("По запросу «$query» блюд нет. Проверьте написание или выберите группу.", "No products match «$query». Check the spelling or pick a group.")
    public val outOfStock: String = t("Нет в наличии", "Out of stock")
    public val notChosen: String = t("ничего не выбрано", "nothing chosen")
    public val noExtra: String = t("без доплаты", "no extra charge")
    public val optional: String = t("по желанию", "optional")
    public val receivingHint: String = t("Как гость получит заказ", "How the guest gets the order")
    public val guestHint: String = t("Имя и телефон увидит курьер; по телефону заказ найдётся в «Клиентах»", "The courier sees the name and the phone; the order is found by the phone in «Customers»")
    public val orPickClient: String = t("Или выберите клиента", "Or pick a customer")
    public val timeHint: String = t("Когда заказ должен быть готов", "When the order must be ready")
    public val promocodeHint: String = t("Скидку посчитает сервер, когда примет код", "The server counts the discount once it takes the code")
    public val code: String = t("Код", "Code")
    public val lineComment: String = t("Комментарий к блюду", "A note to the product")
    public val lineCommentHint: String = t("Например, «без лука» или «нарезать на 8»", "E.g. «no onion» or «cut in 8»")
    public val orderComment: String = t("Комментарий к заказу", "A note to the order")
    public val orderCommentHint: String = t("Его увидят кухня и курьер", "The kitchen and the courier see it")
    public val personsHint: String = t("Сколько положить приборов", "How many sets of cutlery")
    public val tableHint: String = t("Номер или название — его увидят на кухне и официант", "A number or a name — the kitchen and the waiter see it")
    public val failed: String = t("Не получилось", "Something went wrong")
    public val attention: String = t("Обратите внимание", "Please note")
    public val checked: String = t("Проверка пройдена", "Check passed")
    public val refused: String = t("Сервер не принял", "The server refused")
    public val serverSays: String = t("Сервер сообщает", "The server says")
    public val hide: String = t("Скрыть", "Hide")
    public fun passedTo(number: String, place: String?): String =
        if (place != null) t("№ $number передан на кухню точки $place", "№ $number is sent to the kitchen of $place") else t("№ $number передан на кухню", "№ $number is sent to the kitchen")
    public val toOpenReceipts: String = t("К открытым чекам", "To the open receipts")
    public val opened: String = t("открыт", "opened")
    public val point: String = t("Точка", "Point")
    public fun orders(count: Int): String = if (ru) "$count зак." else "$count ord."

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
