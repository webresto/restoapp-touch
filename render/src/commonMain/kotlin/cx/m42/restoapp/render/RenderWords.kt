package cx.m42.restoapp.render

/**
 * The renderer's own words — the few it needs besides what the server sends already translated:
 * search, more, the states of the connection, the point switch.
 */
public class RenderWords(public val lang: String) {
    private val ru = lang.startsWith("ru")

    private fun t(ru: String, en: String) = if (this.ru) ru else en

    public val yes: String = t("Да", "Yes")
    public val no: String = t("Нет", "No")
    public val search: String = t("Поиск", "Search")
    public val more: String = t("Показать ещё", "Show more")
    public val loading: String = t("Загружаем…", "Loading…")
    public val retry: String = t("Повторить", "Retry")
    public val back: String = t("Назад", "Back")
    public val save: String = t("Сохранить", "Save")
    public val nothingFound: String = t("Ничего не найдено", "Nothing found")
    public val offline: String = t("Нет связи с сервером — показано то, что было", "No connection to the server — showing what was there")
    public val signedOut: String = t("Нужно войти заново", "Sign in again")
    public val signedOutHint: String = t("Сервер больше не узнаёт это устройство. Войдите на главном экране.", "The server no longer knows this device. Sign in on the Home screen.")
    public val toHome: String = t("На главную", "To Home")
    public val updateRequired: String = t("Сервер новее приложения — обновите приложение", "The server is newer than the app — update the app")
    public val needsNewerApp: String = t("Нужна новая версия приложения", "This needs a newer version of the app")
    public val needsNewerAppHint: String = t("Это приложение сервера работает только в новой версии RestoApp.", "This app of the server runs only in a newer RestoApp.")
    public val gone: String = t("Это приложение больше недоступно на сервере", "This app is no longer available on the server")
    public val noPlaces: String = t("Точки не назначены — обратитесь к администратору", "No points are assigned — ask your administrator")
    public val places: String = t("Точки", "Points")
    public val confirm: String = t("Подтвердить", "Confirm")
    public val cancel: String = t("Отмена", "Cancel")
    /** After the caption of a field one may leave empty. */
    public val optional: String = t("необязательно", "optional")
    public val unsavedTitle: String = t("Несохранённые изменения будут потеряны", "Unsaved changes will be lost")
    public val unsavedHint: String = t("Сохранить их перед выходом?", "Save them before leaving?")
    public val saveAndLeave: String = t("Сохранить и выйти", "Save and leave")
    public val leaveWithoutSaving: String = t("Выйти без сохранения", "Leave without saving")
    public val stay: String = t("Остаться", "Stay")
    public val set: String = t("Установить", "Set")
    /** What a drag handle is called for a screen reader. */
    public val reorder: String = t("Перетащите, чтобы поменять порядок", "Drag to reorder")
    public val unknownView: String = t("Этот экран приложение пока не умеет показывать", "This app cannot show this screen yet")
    /** The arrow of a line that leaves the app, for a screen reader. */
    public val opensInBrowser: String = t("Откроется в браузере", "Opens in the browser")
    public val linkGone: String = t("Этот переход сейчас недоступен", "This link cannot be followed now")
    public val goneHint: String = t("Его убрали с сервера или у вас больше нет к нему доступа.", "It was removed from the server, or you no longer have access to it.")
    public val noPlacesHint: String = t("Приложение работает по точкам, а у вас пока нет ни одной.", "This app works by points, and you have none yet.")
    public val nothingFoundHint: String = t("Проверьте, что написано в поиске, или сбросьте его.", "Check what the search says, or clear it.")
    public val emptyHint: String = t("Записи появятся здесь, как только они будут на сервере.", "Records show here as soon as the server has them.")
    public val resetSearch: String = t("Сбросить поиск", "Clear the search")
    /** The rail of a record on a wide window: its dates and the like. */
    public val details: String = t("Сведения", "Details")
    /** The mark of a form with edits the server has not got. */
    public val unsaved: String = t("Не сохранено", "Not saved")
    /** The head of a list's first column when the server gave its field no title. */
    public val name: String = t("Название", "Name")
    public val status: String = t("Статус", "Status")
    public val info: String = t("Подробнее", "More")
    public val value: String = t("Значение", "Value")
    public val actions: String = t("Действия", "Actions")
    /** The first tab of a board on a phone: every record of the list. */
    public val all: String = t("Все", "All")
    /** The `⋯` of a row, for a screen reader. */
    public val moreActions: String = t("Ещё действия", "More actions")
    /** A choice nobody has made yet. */
    public val choose: String = t("Выберите", "Choose")
    public val linkNotOpened: String = t("Не удалось открыть ссылку", "The link could not be opened")
    public val serverNotOpened: String = t("Сервер сейчас не работает — откройте его, когда он запустится", "The server is not running — open it once it is up")

    /** The head of a table column of a sparkline whose field the model does not declare. */
    public val trend: String = t("Нагрузка", "Load")

    public fun shownOf(shown: Int, total: Int): String = t("Показано $shown из $total", "$shown of $total shown")

    public fun tooManyAttempts(seconds: Int?): String =
        if (seconds == null) t("Слишком много попыток, подождите", "Too many attempts, wait a little")
        else t("Слишком много попыток, подождите $seconds с", "Too many attempts, wait $seconds s")
}
