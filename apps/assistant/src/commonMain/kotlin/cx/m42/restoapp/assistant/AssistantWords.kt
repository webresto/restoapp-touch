package cx.m42.restoapp.assistant

/** The words of «Ассистент» that are not the panel's (those come with its status: title, hint, placeholder). */
internal class AssistantWords(lang: String) {
    private val ru = lang.startsWith("ru")
    private fun t(ru: String, en: String) = if (this.ru) ru else en

    val conversations = t("Диалоги", "Conversations")
    val newConversation = t("Новый диалог", "New conversation")
    val untitled = t("Без названия", "Untitled")
    val active = t("Текущий", "Current")
    val delete = t("Удалить", "Delete")
    val send = t("Отправить", "Send")
    val stop = t("Стоп", "Stop")
    val placeholder = t("Спросите ассистента…", "Ask the assistant…")
    val welcome = t("Чем помочь?", "How can I help?")
    val thinking = t("Думает…", "Thinking…")
    val reasoning = t("Размышления", "Reasoning")
    val tool = t("Инструмент", "Tool")
    val running = t("выполняется", "running")
    val failed = t("ошибка", "failed")
    val input = t("Параметры", "Input")
    val output = t("Результат", "Result")
    val retry = t("Повторить", "Retry")
    val close = t("Закрыть", "Close")
    val loading = t("Загрузка…", "Loading…")
    val notConnected = t("Ассистент не подключён", "The assistant is not connected")
    val noConversations = t("Диалогов пока нет", "No conversations yet")
    val history = t("История разговоров", "Conversation history")
    val conversationsOverline = t("ДИАЛОГИ", "CONVERSATIONS")
    val disclaimer = t("Ассистент может ошибаться. Действия он только готовит — включаете вы.", "The assistant can be wrong. It only prepares actions — you turn them on.")
    val failedTitle = t("Не получилось", "Something went wrong")
    val notConnectedHint = t("Проверьте связь с сервером и попробуйте ещё раз", "Check the connection to the server and try again")
    val noConversationsHint = t("Начните новый — он появится здесь", "Start a new one — it will show here")
    val done = t("готово", "done")
    fun tokens(n: Long) = t("токенов: $n", "tokens: $n")
}
