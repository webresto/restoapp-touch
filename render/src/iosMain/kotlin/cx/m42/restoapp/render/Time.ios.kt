package cx.m42.restoapp.render

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.systemTimeZone
import platform.Foundation.timeZoneWithName

private fun zone(timezone: String?): NSTimeZone =
    timezone?.let { NSTimeZone.timeZoneWithName(it) } ?: NSTimeZone.systemTimeZone

private fun dateOf(epochMillis: Long): NSDate = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)

private fun format(pattern: String, epochMillis: Long, zone: NSTimeZone, locale: String): String =
    NSDateFormatter().also {
        it.locale = NSLocale(localeIdentifier = locale)
        it.timeZone = zone
        it.dateFormat = pattern
    }.stringFromDate(dateOf(epochMillis))

public actual fun dateTime(epochMillis: Long, timezone: String?, lang: String): String =
    format("d MMM yyyy, HH:mm", epochMillis, zone(timezone), lang.substringBefore('-').ifEmpty { "ru" })

internal actual fun relativeTime(epochMillis: Long, now: Long, timezone: String?, lang: String): String {
    val ru = lang.startsWith("ru")
    val minutes = (now - epochMillis) / 60_000
    val zone = zone(timezone)
    val calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian).also { it.timeZone = zone }
    val days = calendar.components(
        NSCalendarUnitDay,
        fromDate = calendar.startOfDayForDate(dateOf(epochMillis)),
        toDate = calendar.startOfDayForDate(dateOf(now)),
        options = 0u,
    ).day
    // POSIX: a person's 12-hour setting rewrites HH in any other locale.
    val clock = format("HH:mm", epochMillis, zone, "en_US_POSIX")
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
