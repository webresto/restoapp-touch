package cx.m42.restoapp.marketing

/** The words of the editor of a promotion — the only screen of the section the client draws itself. */
internal class MarketingWords(lang: String) {
    private val ru = lang.startsWith("ru")

    private fun t(ru: String, en: String) = if (this.ru) ru else en

    val editor = t("Акция", "Promotion")
    val newPromotion = t("Новая акция", "New promotion")
    val editorHint = t("Что получает гость, когда и где", "What a guest gets, when and where")
    val close = t("Закрыть", "Close")
    val unsaved = t("Не сохранено", "Not saved")
    val unsavedChanges = t("Есть несохранённые изменения", "There are unsaved changes")
    val noChanges = t("Изменений нет", "No changes")
    val cannotSave = t("Не сохраняем", "Not saving")
    val serverRefused = t("Сервер не принял акцию", "The server refused the promotion")
    val cancel = t("Отмена", "Cancel")
    val save = t("Сохранить", "Save")
    val saving = t("Сохраняем…", "Saving…")

    val basics = t("Основное", "The basics")
    val name = t("Название", "Name")
    val description = t("Описание", "Description")
    val namePlaceholder = t("Например, «Бизнес-ланч»", "For example, «Business lunch»")
    val descriptionHint = t("Видят сотрудники в кассе и в отчётах", "Staff see it at the till and in reports")
    val nameNeeded = t("Название и описание обязательны", "A name and a description are required")

    val discount = t("Скидка", "Discount")
    val percentage = t("Процент", "Percentage")
    val flat = t("Сумма", "Flat amount")
    val amount = t("Размер", "Size")
    val percentSize = t("Размер скидки", "Discount size")
    val flatSize = t("Сумма скидки", "Discount amount")
    val noGift = t("Без подарка", "No gift")
    val removeGift = t("Убрать подарок", "Remove the gift")
    val giftHint = t("Только то, что гость не может добавить сам", "Only what a guest cannot add himself")
    val notGiftable = t("в продаже — подарком быть не может", "on sale — cannot be a gift")
    val tooBig = t("Процент не больше 100", "A percentage cannot exceed 100")

    val appliesTo = t("На что действует", "What it applies to")
    val wholeBasket = t("Вся корзина", "The whole basket")
    val chosen = t("Выбранные блюда и группы", "The chosen products and groups")
    val dishes = t("Блюда", "Products")
    val groups = t("Группы", "Groups")
    val menuGroups = t("Группы меню", "Menu groups")
    val choose = t("Выбрать…", "Choose…")
    val wholeBasketHint = t("Ничего не выбрано — скидка от всей корзины", "Nothing chosen — the discount is on the whole basket")
    fun chosenCount(count: Int): String = t("Выбрано: $count", "Chosen: $count")
    val done = t("Готово", "Done")
    fun more(count: Int): String = "+$count"

    val conditions = t("Подарок и условия", "The gift and the conditions")
    val gift = t("Подарок", "Gift")
    val giftDish = t("Подарочное блюдо", "The gift product")
    val giftAmount = t("Сколько штук", "How many")
    val giftFrom = t("Подарок от корзины", "Gift from a basket of")
    val minBasket = t("Корзина от", "Basket from")
    val serviceTypes = t("Способы получения", "Ways to receive")
    val anyService = t("Любой способ", "Any way")
    val conditionsOnly = t("Условия", "Conditions")
    val basketHint = t("Пусто — для любой суммы", "Empty — any total")
    val weekDays = t("Дни недели", "Days of the week")
    val everyDay = t("Каждый день, круглосуточно", "Every day, around the clock")
    val fromHour = t("С какого часа", "From what time")
    val tillHour = t("До какого часа", "Till what time")

    val behaviour = t("Поведение", "Behaviour")
    val works = t("Работает", "Works")
    val worksHint = t("Выключена — не применяется нигде, в том числе по промокодам", "Off — it applies nowhere, promo codes included")
    val onlyByCode = t("Только по промокоду", "Only by promo code")
    val onlyByCodeHint = t("Сама не применяется и на витрине не видна", "It does not apply by itself and is not shown on the storefront")
    val joint = t("Суммируется с другими", "Stacks with others")
    val onStorefront = t("Видна на витрине", "Shown on the storefront")
    val priority = t("Приоритет", "Priority")
    val priorityHint = t("Меньше — применяется раньше", "Lower applies earlier")

    val schedule = t("Расписание", "Hours")
    val aroundTheClock = t("Круглосуточно", "Around the clock")
    val from = t("С", "From")
    val till = t("По", "Till")

    val total = t("Итог", "What it gives")
    val guestSees = t("ТАК УВИДИТ ГОСТЬ", "WHAT A GUEST SEES")
    val atTill = t("В кассе", "At the till")
    val appliesByItself = t("Применится сама, если заказ подходит", "Applies by itself when the order fits")
    val appliesByCode = t("Применится после ввода кода", "Applies once the code is entered")
    val byCode = t("по промокоду", "by promo code")
    val off = t("выключена", "off")
    val nothing = t("Пока ничего не даёт", "It gives nothing yet")

    val catalog = t("Каталог", "Catalog")
    val back = t("Назад", "Back")
    val searchProduct = t("Поиск блюда", "Search for a product")
    val nothingFound = t("Ничего не найдено", "Nothing found")
    val loading = t("Загружаем…", "Loading…")
    val chooseGroup = t("Выбрать всю группу", "Choose the whole group")

    val days: List<Pair<String, String>> = listOf(
        "monday" to t("пн", "Mon"),
        "tuesday" to t("вт", "Tue"),
        "wednesday" to t("ср", "Wed"),
        "thursday" to t("чт", "Thu"),
        "friday" to t("пт", "Fri"),
        "saturday" to t("сб", "Sat"),
        "sunday" to t("вс", "Sun"),
    )

    fun serviceType(kind: String): String = when (kind) {
        "delivery" -> t("Доставка", "Delivery")
        "pickup" -> t("Самовывоз", "Pickup")
        else -> t("В зале", "Dine-in")
    }

    fun counted(what: String, count: Int): String = if (count == 0) what else "$what · $count"

    fun giftWords(amount: Int, from: String): String =
        if (from.isEmpty()) t("Подарок: $amount шт.", "Gift: $amount item(s)") else t("Подарок: $amount шт. от $from", "Gift: $amount item(s) from $from")

    fun percentOff(amount: String): String = "−$amount%"
}
