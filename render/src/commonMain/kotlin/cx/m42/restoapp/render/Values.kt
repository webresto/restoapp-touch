package cx.m42.restoapp.render

import cx.m42.restoapp.protocol.CreditRate
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
    if (field != null) specialOf(value, field.specials)?.let { return Shown(it.title, it.tone ?: field.toneOf(enumKey(value)).takeIf { field.format == Formats.ENUM }) }
    if (value.isMissing()) return Shown(NO_VALUE)
    val format = field?.format ?: Formats.TEXT
    val primitive = value as? JsonPrimitive
    val text = when (format) {
        // A cloud that says `money` in `CR` (P10 cloud before P11) — credits all the same.
        Formats.MONEY -> primitive?.doubleOrNull?.let { if (facts?.currency == ServerFacts.CREDITS) credits(it, words.lang) else money(it, facts?.currency) }
        Formats.CREDITS -> primitive?.doubleOrNull?.let { credits(it, words.lang) }
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
    // P2: the tone of an enum's value from the field's `x-tones` — the same everywhere the value is shown.
    return Shown(text, if (format == Formats.ENUM) field?.toneOf(enumKey(value)) else null)
}

/** The key of an enum value in `x-titles` / `x-tones`: the value as a string. */
internal fun enumKey(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

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

/** P11: 1234.5 credits → "1 234,5 кр" ("cr" in English) — the unit of every amount of the cloud. */
public fun credits(value: Double, lang: String): String = number(value) + "\u00A0" + if (lang.startsWith("ru")) "кр" else "cr"

/** P11: credits as money of the display currency at the system rate, rounded to its decimals: "12,35 $", "$12,35". */
public fun creditsAsMoney(credits: Double, rate: CreditRate): String {
    val digits = rate.decimals.coerceIn(0, 6)
    var factor = 1.0
    repeat(digits) { factor *= 10 }
    val amount = kotlin.math.round(credits * rate.perCredit * factor) / factor
    val text = if (digits == 0) number(kotlin.math.round(amount)) else number(amount)
    return if (rate.signBefore) (if (amount < 0) "−" + rate.sign + text.removePrefix("−") else rate.sign + text) else text + "\u00A0" + rate.sign
}

/** P11: "≈ 12,35 $" next to an amount of credits — display only. */
public fun approxMoney(credits: Double, rate: CreditRate): String = "≈\u00A0" + creditsAsMoney(credits, rate)

/** Tone of a value of [field], from its specials; null when the value has none. */
internal fun toneOf(value: JsonElement?, field: Field?): String? = field?.let { specialOf(value, it.specials)?.tone ?: it.toneOf(enumKey(value)) }

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
