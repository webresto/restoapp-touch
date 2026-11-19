@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package cx.m42.restoapp.render

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/*
 * The browser's own calendar: `Intl.DateTimeFormat` in the server's zone. The same words as on the JVM and the
 * iPhone — «18 нояб. 2026, 21:53», «вчера 21:53».
 */

private fun formatJs(epochMillis: Double, zone: String?, locale: String, withDate: Boolean): String = js(
    """{
    const options = withDate
        ? { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false }
        : { hour: '2-digit', minute: '2-digit', hour12: false };
    if (zone) options.timeZone = zone;
    try { return new Intl.DateTimeFormat(locale, options).format(new Date(epochMillis)); }
    catch (e) { delete options.timeZone; return new Intl.DateTimeFormat(locale, options).format(new Date(epochMillis)); }
    }""",
)

/** The day of [epochMillis] in [zone] as `yyyy-mm-dd`, to count calendar days between two moments. */
private fun dayJs(epochMillis: Double, zone: String?): String = js(
    """{
    const options = { year: 'numeric', month: '2-digit', day: '2-digit' };
    if (zone) options.timeZone = zone;
    try { return new Intl.DateTimeFormat('en-CA', options).format(new Date(epochMillis)); }
    catch (e) { delete options.timeZone; return new Intl.DateTimeFormat('en-CA', options).format(new Date(epochMillis)); }
    }""",
)

private fun dayNumber(epochMillis: Long, zone: String?): Long {
    val (y, m, d) = dayJs(epochMillis.toDouble(), zone).split('-').map { it.toInt() }
    // Days from the civil date (Howard Hinnant's days_from_civil): only differences of it matter.
    val yy = if (m <= 2) y - 1 else y
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097L + doe - 719468L
}

public actual fun dateTime(epochMillis: Long, timezone: String?, lang: String): String =
    formatJs(epochMillis.toDouble(), timezone, lang.substringBefore('-').ifEmpty { "ru" }, withDate = true)

internal actual fun relativeTime(epochMillis: Long, now: Long, timezone: String?, lang: String): String {
    val ru = lang.startsWith("ru")
    val minutes = (now - epochMillis) / 60_000
    val days = dayNumber(now, timezone) - dayNumber(epochMillis, timezone)
    val clock = formatJs(epochMillis.toDouble(), timezone, "en-GB", withDate = false)
    return when {
        minutes < 0 -> dateTime(epochMillis, timezone, lang)
        minutes < 1 -> if (ru) "только что" else "just now"
        minutes < 60 -> if (ru) "$minutes мин назад" else "$minutes min ago"
        days == 0L -> (if (ru) "сегодня " else "today ") + clock
        days == 1L -> (if (ru) "вчера " else "yesterday ") + clock
        else -> dateTime(epochMillis, timezone, lang)
    }
}

@OptIn(ExperimentalTime::class)
internal actual fun parseInstant(text: String): Long? =
    runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()
