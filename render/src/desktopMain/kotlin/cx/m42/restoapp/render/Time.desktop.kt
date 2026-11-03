package cx.m42.restoapp.render

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private fun zone(timezone: String?): ZoneId =
    timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

private fun locale(lang: String): Locale = Locale.forLanguageTag(lang.substringBefore('-').ifEmpty { "ru" })

internal actual fun dateTime(epochMillis: Long, timezone: String?, lang: String): String =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale(lang)).format(Instant.ofEpochMilli(epochMillis).atZone(zone(timezone)))

internal actual fun relativeTime(epochMillis: Long, now: Long, timezone: String?, lang: String): String {
    val ru = lang.startsWith("ru")
    val minutes = (now - epochMillis) / 60_000
    val zone = zone(timezone)
    val time = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = DateTimeFormatter.ofPattern("HH:mm").format(time)
    return when {
        minutes < 0 -> dateTime(epochMillis, timezone, lang)
        minutes < 1 -> if (ru) "только что" else "just now"
        minutes < 60 -> if (ru) "$minutes мин назад" else "$minutes min ago"
        time.toLocalDate() == today -> (if (ru) "сегодня " else "today ") + clock
        ChronoUnit.DAYS.between(time.toLocalDate(), today) == 1L -> (if (ru) "вчера " else "yesterday ") + clock
        else -> dateTime(epochMillis, timezone, lang)
    }
}

internal actual fun parseInstant(text: String): Long? =
    runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
