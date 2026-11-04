package cx.m42.restoapp.settings

/**
 * The words of the two screens «Настройки» draws itself. Everything about a setting — its name, its
 * description, the reason a value was refused — comes from the server already translated; these are
 * the screen's own.
 */
internal class SettingsWords(val lang: String) {
    private val ru = lang.startsWith("ru")

    private fun t(ru: String, en: String) = if (this.ru) ru else en

    val cancel: String = t("Отмена", "Cancel")
    val save: String = t("Сохранить", "Save")
    val saving: String = t("Сохраняем…", "Saving…")
    val close: String = t("Закрыть", "Close")
    val value: String = t("Значение", "Value")
    val about: String = t("Описание", "Description")
    val toDefault: String = t("К умолчанию", "To the default")
    val notSetYet: String = t("Значение ещё не задано", "No value is set yet")
    val leaveQuestion: String = t("Уйти, не сохранив правки?", "Leave without saving?")
    val leave: String = t("Уйти", "Leave")
    val stay: String = t("Остаться", "Stay")

    val envPinned: String =
        t(
            "Значение приходит из окружения — меняйте его там, где описано окружение контейнера",
            "This value comes from the environment — change it where the container's environment is described",
        )
    val envInvalid: String =
        t(
            "Значение из окружения не проходит схему, и настройка читается как пустая",
            "The value in the environment fails the schema, and the setting reads as empty",
        )
    val readOnly: String =
        t("Значение ведёт приложение — его не меняют руками", "The application maintains this value — it is not edited by hand")
    val secretStored: String =
        t("Значение хранится и не показывается — введите новое, чтобы заменить", "The value is stored and not shown — type a new one to replace it")
    val secretEmpty: String = t("Чтобы заменить секрет, введите новое значение", "To replace the secret, type a new value")
    val restartNeeded: String =
        t("Перезапустите сервер, чтобы изменение применилось", "Restart the server for the change to take effect")
    val restartPending: String =
        t("Это значение уже изменено и ждёт перезапуска сервера", "This value has been changed and is waiting for the server to restart")

    val required: String = t("Обязательная настройка", "A required setting")

    // ---- the transfer ----
    val transfer: String = t("Экспорт и импорт", "Export and import")
    val export: String = t("Экспорт", "Export")
    val import: String = t("Импорт", "Import")
    val copy: String = t("Скопировать", "Copy")
    val copied: String = t("Скопировано в буфер", "Copied to the clipboard")
    val paste: String = t("Вставить из буфера", "Paste from the clipboard")
    val noJson: String = t("В буфере не JSON выгрузки настроек", "The clipboard does not hold a settings dump")
    val changed: String = t("Изменится", "Changes")
    val unchanged: String = t("Без изменений", "No change")
    val notFound: String = t("Нет такой настройки", "No such setting")
    val locked: String = t("Нельзя изменить", "Cannot be changed")
    val selectChanged: String = t("Выбрать все изменённые", "Select every change")
    val clearAll: String = t("Снять все", "Clear the selection")
    val applied: String = t("Применено", "Applied")
    val skipped: String = t("Пропущено", "Skipped")
    val failed: String = t("Ошибка", "Error")
    val current: String = t("Сейчас", "Now")
    val incoming: String = t("Станет", "Becomes")

    fun exportCount(count: Int): String =
        t("$count настроек, секреты не включены", "$count settings, secrets are not included")

    fun defaultIs(text: String): String = t("По умолчанию: $text", "The default: $text")

    fun apply(count: Int): String = t("Применить $count", "Apply $count")

    fun selected(count: Int, of: Int): String = t("Выбрано $count из $of", "$count of $of selected")
}
