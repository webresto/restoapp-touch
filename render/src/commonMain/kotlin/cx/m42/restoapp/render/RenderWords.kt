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
    public val set: String = t("Установить", "Set")
    public val unknownView: String = t("Этот экран приложение пока не умеет показывать", "This app cannot show this screen yet")
    public val linkGone: String = t("Этот переход сейчас недоступен", "This link cannot be followed now")

    public fun shownOf(shown: Int, total: Int): String = t("Показано $shown из $total", "$shown of $total shown")

    public fun tooManyAttempts(seconds: Int?): String =
        if (seconds == null) t("Слишком много попыток, подождите", "Too many attempts, wait a little")
        else t("Слишком много попыток, подождите $seconds с", "Too many attempts, wait $seconds s")
}
