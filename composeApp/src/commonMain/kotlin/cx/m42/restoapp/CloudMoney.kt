package cx.m42.restoapp

import cx.m42.restoapp.protocol.AccountBalance
import cx.m42.restoapp.protocol.CreditRate
import cx.m42.restoapp.protocol.ServerFacts
import cx.m42.restoapp.render.approxMoney
import cx.m42.restoapp.render.creditsAsMoney
import cx.m42.restoapp.protocol.Tones
import cx.m42.superizer.kit.Tone
import kotlin.math.abs
import kotlin.math.roundToLong

/*
 * The money of a Resto Cloud account as the app says it. A cloud of P11 counts everything in credits
 * (`server.currency` = `CR`): «1 840 кр», with «≈ 18,40 $» at the system rate of `server.credits` and the rate behind
 * an (i) — display only. An older cloud sent money in the account's currency: «1 840 ₽».
 */

internal fun currencySign(currency: String?, ru: Boolean = true): String = when (currency) {
    ServerFacts.CREDITS -> if (ru) "кр" else "cr"
    null, "RUB" -> "₽"
    "USD" -> "$"
    "EUR" -> "€"
    "KZT" -> "₸"
    "BYN" -> "Br"
    else -> currency
}

/** 1840.0 → «1 840 ₽», 172.8 → «172,8 ₽», 43.25 → «43,25 ₽»; [whole] — rounded to the unit: the chip's «173 ₽». */
internal fun moneyText(amount: Double, currency: String?, whole: Boolean = false, ru: Boolean = true): String {
    val cents = (abs(amount) * 100).roundToLong()
    val units = if (whole) (abs(amount)).roundToLong() else cents / 100
    val fraction = if (whole) 0 else (cents % 100).toInt()
    val digits = units.toString().reversed().chunked(3).joinToString(" ").reversed()
    val tail = when {
        fraction == 0 -> ""
        fraction % 10 == 0 -> "," + (fraction / 10)
        else -> "," + fraction.toString().padStart(2, '0')
    }
    return (if (amount < 0 && (units > 0 || fraction > 0)) "−" else "") + digits + tail + " " + currencySign(currency, ru)
}

/** P11: «≈ 18,40 $» for an amount of credits at the system rate; null without a rate. */
internal fun approxText(credits: Double, rate: CreditRate?): String? = rate?.let { approxMoney(credits, it) }

/** P11: the line of the (i) — «1 кр = $0,01 · курс системы»; the whole note is [CreditRate.note]. */
internal fun rateLine(rate: CreditRate, ru: Boolean): String =
    (if (ru) "1 кр = " else "1 cr = ") + (rate.rateText ?: creditsAsMoney(1.0, rate)) + (if (ru) " · курс системы" else " · the system rate")

internal fun plural(n: Int, one: String, few: String, many: String): String {
    val d = n % 100
    return when {
        d in 11..14 -> many
        n % 10 == 1 -> one
        n % 10 in 2..4 -> few
        else -> many
    }
}

internal fun daysText(days: Int, ru: Boolean): String =
    if (ru) "$days ${plural(days, "день", "дня", "дней")}" else "$days ${if (days == 1) "day" else "days"}"

/** «хватит на 42 дня»; «деньги закончились» at nothing; null when the account spends nothing. */
internal fun moneyLeft(balance: AccountBalance, ru: Boolean): String? = when {
    balance.amount <= 0 -> if (ru) "баланс исчерпан" else "the balance has run out"
    balance.daysLeft != null -> if (ru) "хватит на ${daysText(balance.daysLeft!!, true)}" else "lasts ${daysText(balance.daysLeft!!, false)}"
    else -> null
}

/** The account's line in the switcher: «1 840 ₽ · хватит на 42 дня». */
internal fun balanceLine(balance: AccountBalance, currency: String?, ru: Boolean): String =
    listOfNotNull(moneyText(balance.amount, currency, whole = true, ru = ru), moneyLeft(balance, ru)).joinToString(" · ")

/** The kit's tone of a balance: amber, red, or none. */
internal fun balanceTone(balance: AccountBalance?): Tone = when (balance?.warning) {
    Tones.DANGER -> Tone.Danger
    Tones.WARNING -> Tone.Warn
    else -> Tone.Neutral
}
