package cx.m42.restoapp.render

import cx.m42.restoapp.protocol.Field
import cx.m42.restoapp.protocol.Formats
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.restoapp.protocol.Special
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * A value of a record as text, by the field's declaration: specials first ("Безлимит" for -1), then
 * the format. Money and dates are formatted by the client in the server's currency and time zone
 * (plan section 1); a format this client does not know is plain text, never a crash.
 */

/** A value as the screen shows it: the words, and a tone when the server gave one. */
public data class Shown(val text: String, val tone: String? = null)

/** What a value that is missing or null reads as. */
public const val NO_VALUE: String = "—"

internal fun JsonElement?.isMissing(): Boolean = this == null || this is JsonNull

internal fun specialOf(value: JsonElement?, specials: List<Special>): Special? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return specials.firstOrNull { special ->
        val number = primitive.doubleOrNull
        val other = special.value.doubleOrNull
        if (number != null && other != null && !primitive.isString && !special.value.isString) number == other
        else special.value.contentOrNull == primitive.contentOrNull
    }
}

/**
 * [value] of [field] as text. [field] may be null — the row names a field the model does not
 * declare (a computed one): then it is text.
 */
public fun showValue(value: JsonElement?, field: Field?, facts: ServerFacts?, words: RenderWords, now: Long): Shown {
    if (field != null) specialOf(value, field.specials)?.let { return Shown(it.title, it.tone) }
    if (value.isMissing()) return Shown(NO_VALUE)
    val format = field?.format ?: Formats.TEXT
    val primitive = value as? JsonPrimitive
    val text = when (format) {
        Formats.MONEY -> primitive?.doubleOrNull?.let { money(it, facts?.currency) }
        Formats.NUMBER -> primitive?.doubleOrNull?.let(::number)
        Formats.PERCENT -> primitive?.doubleOrNull?.let { percent(it) }
        Formats.BOOLEAN -> primitive?.booleanOrNull?.let { if (it) words.yes else words.no }
        Formats.ENUM -> primitive?.contentOrNull?.let { field?.titles?.get(it) ?: it }
        Formats.DATETIME, Formats.RELATIVE -> instantOf(value)?.let { millis ->
            if (format == Formats.RELATIVE) relativeTime(millis, now, facts?.timezone, words.lang)
            else dateTime(millis, facts?.timezone, words.lang)
        }
        Formats.REF -> (value as? JsonObject)?.let { ref -> (ref["title"] as? JsonPrimitive)?.contentOrNull ?: (ref["id"] as? JsonPrimitive)?.contentOrNull }
            ?: primitive?.contentOrNull
        Formats.IMAGE -> null
        else -> null
    } ?: plain(value)
    return Shown(text)
}

/** Any JSON as one line of text: what an unknown format, or a value of the wrong shape, reads as. */
internal fun plain(value: JsonElement?): String = when (value) {
    null, JsonNull -> NO_VALUE
    is JsonPrimitive -> value.contentOrNull ?: NO_VALUE
    is JsonArray -> value.joinToString(", ") { plain(it) }
    is JsonObject -> (value["title"] as? JsonPrimitive)?.contentOrNull ?: value.toString()
}

/** Epoch milliseconds from a number or an ISO-8601 string. */
internal fun instantOf(value: JsonElement?): Long? {
    val primitive = value as? JsonPrimitive ?: return null
    if (!primitive.isString) return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
    return primitive.contentOrNull?.let(::parseInstant)
}

/** 1 330 → "1 330", 2.5 → "2,5" — digits grouped with no-break spaces, so they never split. */
public fun number(value: Double): String {
    val negative = value < 0
    val abs = kotlin.math.abs(value)
    val whole = abs.toLong()
    val fraction = kotlin.math.round((abs - whole) * 100).toLong()
    val grouped = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
    val tail = if (fraction == 0L) "" else "," + fraction.toString().padStart(2, '0').trimEnd('0')
    return (if (negative) "−" else "") + grouped + tail
}

/** 10 → "10 %" — the size of a discount, which is not money and must not be shown as any. */
public fun percent(value: Double): String = number(value) + "\u00A0%"

/** 590 in RUB → "590 ₽". A currency without a sign of its own goes by its code: "590 KZT". */
public fun money(value: Double, currency: String?): String {
    val sign = when (currency?.uppercase()) {
        null, "", "RUB", "RUR" -> "₽"
        "USD" -> "$"
        "EUR" -> "€"
        "KZT" -> "₸"
        "BYN" -> "Br"
        "UAH" -> "₴"
        "GEL" -> "₾"
        "AMD" -> "֏"
        else -> currency.uppercase()
    }
    return number(value) + " " + sign
}

/** Tone of a value of [field], from its specials; null when the value has none. */
internal fun toneOf(value: JsonElement?, field: Field?): String? = field?.let { specialOf(value, it.specials)?.tone }

/**
 * Time in the server's zone, in the person's language. Platform code: the JVM's java.time.
 *
 * Public because the native POS formats the slots of a kitchen with it: two formatters would
 * print the same moment two ways on the same screen.
 */
public expect fun dateTime(epochMillis: Long, timezone: String?, lang: String): String

/** "5 мин назад", "вчера 18:40" — and the date beyond that. */
internal expect fun relativeTime(epochMillis: Long, now: Long, timezone: String?, lang: String): String

internal expect fun parseInstant(text: String): Long?
