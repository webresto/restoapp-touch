package cx.m42.restoapp.render

import cx.m42.restoapp.protocol.CreditRate
import cx.m42.restoapp.protocol.Field
import cx.m42.restoapp.protocol.Formats
import cx.m42.restoapp.protocol.ServerFacts
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** P11: the cloud's amounts are credits; the sum in the display currency is at the system rate, display only. */
class CreditsTest {
    private val usd = CreditRate("USD", "$", 0.01, 2, true)
    private val vnd = CreditRate("VND", "₫", 260.0, 0, false)

    @Test
    fun creditsAreSaidAsCredits() {
        val facts = ServerFacts("Resto Cloud", currency = ServerFacts.CREDITS, credits = usd)
        val field = Field("balance", "Баланс", Formats.CREDITS)
        assertEquals(number(999999.23) + "\u00A0кр", showValue(JsonPrimitive(999999.23), field, facts, RenderWords("ru"), 0).text)
        assertEquals("12,5\u00A0cr", showValue(JsonPrimitive(12.5), field, facts, RenderWords("en"), 0).text)
    }

    @Test
    fun theSumInTheCurrencyIsAtTheRate() {
        assertEquals("≈\u00A0$" + number(9999.99), approxMoney(999999.23, usd))
        assertEquals("≈\u00A0" + number(260000.0) + "\u00A0₫", approxMoney(1000.0, vnd))
        assertEquals("$0,01", creditsAsMoney(1.0, usd))
    }

    @Test
    fun anOlderCloudThatSaysNoCurrencyIsCreditsToo() {
        val facts = ServerFacts("Resto Cloud", balance = null).ofCloud()
        assertEquals(ServerFacts.CREDITS, facts.currency)
        assertEquals("RUB", ServerFacts("Пицца", currency = "RUB").ofCloud().currency)
        val field = Field("balance", "Баланс", Formats.MONEY)
        assertEquals(number(999999.23) + "\u00A0кр", showValue(JsonPrimitive(999999.23), field, facts, RenderWords("ru"), 0).text)
    }
}
