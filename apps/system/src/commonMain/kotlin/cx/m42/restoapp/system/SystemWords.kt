package cx.m42.restoapp.system

/** The names of the buttons of the progress screen; what it says about the job is the server's. */
internal class SystemWords(lang: String) {
    private val ru = lang.startsWith("ru")

    private fun t(ru: String, en: String) = if (this.ru) ru else en

    val close = t("Закрыть", "Close")
    val collapse = t("Свернуть", "Hide")
    val cancel = t("Отмена", "Cancel")
    val finish = t("Готово", "Done")
    val loading = t("Загружаем…", "Loading…")
    val restartNow = t("Перезапустить сейчас", "Restart now")
    val restarting = t("Сервер перезапускается", "The server is restarting")
    val starting = t("Начинаем", "Starting")
    val offline = t("Нет связи с сервером, задание идёт без нас. Ждём.", "No connection to the server, the job goes on without us. Waiting.")
    val noJob = t("Сервер не начал задание", "The server did not start the job")
}
