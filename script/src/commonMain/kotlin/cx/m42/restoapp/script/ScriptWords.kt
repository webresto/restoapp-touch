package cx.m42.restoapp.script

/** The words of the frame a script's screen is drawn in; what the screen says is the script's own. */
internal class ScriptWords(lang: String) {
    private val ru = lang.startsWith("ru")

    private fun t(ru: String, en: String) = if (this.ru) ru else en

    val failed = t("Экран не открылся", "The screen did not open")
    val emptyHint = t("Здесь появится то, что добавят", "What is added will show here")
    val choose = t("Выбрать", "Choose")
}
