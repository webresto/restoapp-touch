package cx.m42.restoapp.render

/**
 * The words a form by a schema needs — the rules of a value, said the way the server says them.
 *
 * Every phrase here has a twin in core (`lib/settings/schema.ts`, `describeAjvError`), because both
 * sides check the same value: this one so the person is told before they press «Сохранить», that one
 * because it is the one with the last word. When the server does refuse, its sentence is shown
 * instead of these — it is already in the language of the request, and it is final.
 */
public class SchemaWords(public val lang: String) {
    private val ru = lang.startsWith("ru")

    private fun t(ru: String, en: String) = if (this.ru) ru else en

    public val yes: String = t("Да", "Yes")
    public val no: String = t("Нет", "No")
    public val cancelChoosing: String = t("Свернуть", "Collapse")
    public val empty: String = t("Не может быть пустым", "Cannot be empty")
    public val mustBeNumber: String = t("Должно быть числом", "Must be a number")
    public val mustBeWhole: String = t("Целое число", "A whole number")
    public val notJson: String = t("Не разбирается как JSON", "This is not JSON")
    public val uniqueItems: String = t("Значения не должны повторяться", "Values must not repeat")
    public val add: String = t("Добавить", "Add")
    public val addKey: String = t("Добавить ключ", "Add a key")
    public val remove: String = t("Удалить", "Remove")
    public val key: String = t("Ключ", "Key")
    public val rawTail: String = t("Остальные свойства, как есть", "The other properties, as they are")
    public val choose: String = t("Выберите", "Choose")
    public val notSet: String = t("Не задано", "Not set")
    public val element: String = t("Элемент", "Item")

    public fun atLeastChars(count: Int): String =
        t("Не короче $count символов", "At least $count characters long")

    public fun atMostChars(count: Int): String =
        t("Не длиннее $count символов", "No longer than $count characters")

    public fun atLeast(min: String): String = t("$min или больше", "$min or greater")

    public fun atMost(max: String): String = t("$max или меньше", "$max or less")

    public fun oneOf(values: List<String>): String =
        t("Одно из: ${values.joinToString(", ")}", "One of: ${values.joinToString(", ")}")

    public fun mustContainOnly(hint: String): String =
        t("Должно содержать только: $hint", "Must contain only: $hint")

    /** Without a hint in the manifest there is only the expression itself — as the server also says. */
    public fun mustMatch(pattern: String): String =
        t("Должно подходить под выражение: $pattern", "Must match the pattern: $pattern")

    public fun atLeastItems(count: Int): String =
        t("Не меньше $count элементов", "At least $count item(s)")

    public fun missing(property: String): String =
        t("Заполните «$property»", "Fill in “$property”")

    /** The bounds of a number as a sentence, when a setting has nothing better to say about itself. */
    public fun between(min: String?, max: String?): String? = when {
        min != null && max != null -> t("От $min до $max", "From $min to $max")
        min != null -> atLeast(min)
        max != null -> atMost(max)
        else -> null
    }
}
