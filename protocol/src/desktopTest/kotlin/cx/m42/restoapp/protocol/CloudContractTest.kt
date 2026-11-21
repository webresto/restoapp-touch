package cx.m42.restoapp.protocol

import cx.m42.restoapp.demo.DemoCloud
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The hints of the Resto Cloud screens (`_plan/08-cloud-screens.md`): P8 `item.series`, P9 `open.server` and `quiet`,
 * P10 `balance` of `describe`. The schema takes them in their shapes and refuses the rest; the client reads a wrong
 * one as none and keeps the declaration; the demo cloud sends every one of them, in money and never in credits.
 */
class CloudContractTest {

    private fun json(text: String) = ProtocolJson.parseToJsonElement(text)

    @Test
    fun theSchemaTakesTheCloudHints() {
        Contract.assertValid("ui", json("""{"view":"list","item":{"title":"title","subtitle":"host","badge":"status","meta":"plan","value":"perDay","series":"load"}}"""))
        Contract.assertValid("link", json("""{"title":"Открыть управление","open":{"server":"6f1c2a"}}"""))
        Contract.assertValid("link", json("""{"title":"Веб-админка","quiet":true,"open":{"url":"https://pizza.restocloud.app/admin"}}"""))
        Contract.assertValid("balance", json("""{"amount":1840.0,"daysLeft":42,"tone":null}"""))
        Contract.assertValid("balance", json("""{"amount":172.8,"daysLeft":4,"tone":"warning"}"""))
    }

    @Test
    fun theSchemaRefusesThemOutOfShape() {
        Contract.assertInvalid("ui", """{"view":"list","item":{"series":7}}""")
        Contract.assertInvalid("link", """{"title":"X","quiet":"yes","open":{"url":"/admin"}}""")
        // A link leads to one place: a method, an address or a server — never two.
        Contract.assertInvalid("open", """{"server":"a","url":"/admin"}""")
        Contract.assertInvalid("open", """{"server":""}""")
        Contract.assertInvalid("balance", """{"daysLeft":4}""")
        Contract.assertInvalid("balance", """{"amount":1,"tone":"muted"}""")
    }

    @Test
    fun aCloudHintOfTheWrongShapeIsNoHint() {
        val item = ProtocolJson.decodeFromString(ItemUi.serializer(), """{"title":"t","series":["load"]}""")
        assertEquals("t", item.title)
        assertNull(item.series)
        val record = json(
            """{"_links":[{"title":"A","open":{"server":42},"quiet":"yes"},{"title":"B","quiet":true,"open":{"url":"/admin"}},{"title":"C","open":{"server":"s1"}}]}""",
        ).jsonObject
        val links = record.links()
        assertEquals(listOf("A", "B", "C"), links.map { it.title })
        assertNull(links[0].open.server)
        assertNull(links[0].quiet)
        assertEquals(true, links[1].quiet)
        assertEquals("s1", links[2].open.server)

        val facts = ProtocolJson.decodeFromString(ServerFacts.serializer(), """{"name":"Resto Cloud","balance":{"amount":"lots"}}""")
        assertNull(facts.balance)
        val user = ProtocolJson.decodeFromString(DeclaredUser.serializer(), """{"id":1,"login":"anna","balance":{"amount":10,"daysLeft":"x","tone":"purple"}}""")
        assertEquals(10.0, user.balance?.amount)
        assertNull(user.balance?.daysLeft)
        // A tone the client does not warn with is no warning.
        assertNull(user.balance?.warning)
    }

    @Test
    fun p11CreditsAndTheSystemRate() {
        val rate = """{"currency":"USD","title":"US dollar","sign":"$","perCredit":0.01,"decimals":2,"signBefore":true,"rateUpdatedAt":"2026-11-19T10:00:00.000Z","rateText":"$0,01","note":"1 кредит = $0,01"}"""
        Contract.assertValid("credits", json(rate))
        Contract.assertInvalid("credits", """{"currency":"USD","sign":"$","perCredit":0}""")
        Contract.assertInvalid("credits", """{"currency":"usd","sign":"$","perCredit":0.01}""")
        // Every amount of the cloud is credits: `CR`, the balance in credits, the rate beside it.
        val facts = ProtocolJson.decodeFromString(ServerFacts.serializer(), """{"name":"Resto Cloud","currency":"CR","balance":{"amount":999999.23},"credits":$rate}""")
        assertEquals(ServerFacts.CREDITS, facts.currency)
        assertEquals(CreditRate("USD", "$", 0.01, 2, true, "US dollar", "2026-11-19T10:00:00.000Z", "$0,01", "1 кредит = $0,01"), facts.credits)
        // A wrong shape is no rate, never a lost declaration.
        assertNull(ProtocolJson.decodeFromString(ServerFacts.serializer(), """{"name":"Resto Cloud","credits":{"sign":1}}""").credits)
    }

    @Test
    fun aRestoAppSaysNoneOfThem() {
        val facts = ProtocolJson.decodeFromString(ServerFacts.serializer(), """{"name":"Пицца","currency":"RUB"}""")
        assertNull(facts.balance)
        assertNull(facts.credits)
        val text = Contract.wire.encodeToString(ServerFacts.serializer(), facts)
        assertFalse("balance" in text, text)
        assertFalse("series" in Contract.wire.encodeToString(ItemUi.serializer(), ItemUi(title = "t")))
    }

    private fun call(cloud: DemoCloud, method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement =
        runBlocking { cloud.call(DemoCloud.TOKEN, method, params, null, "ru").value }

    @Test
    fun theDemoCloudSendsTheContract(): Unit = runBlocking {
        val cloud = DemoCloud()
        val declaration = cloud.describe(DemoCloud.TOKEN, "ru").value
        Contract.assertValid("declaration", Contract.wire.encodeToJsonElement(Declaration.serializer(), declaration))
        // The menu in the contract's order, «Обзор» first.
        assertEquals(listOf("home", "services", "assistant", "balance", "apiaccess", "account"), declaration.apps.map { it.id })
        assertEquals("RUB", declaration.server.currency)
        assertEquals(AccountBalance(1840.0, 42, null), declaration.balance)
        assertEquals("load", declaration.app("services")!!.method("services.list")!!.ui!!.item!!.series)

        val home = call(cloud, "home.summary")
        Contract.assertValid("itemResult", home)
        val summary = recordOf(home)!!
        assertEquals("cloud", (summary["kind"] as JsonPrimitive).content)
        listOf("attention", "running", "total", "balance", "daysLeft", "perDay", "perMonth", "ordersToday", "services", "spend", "share").forEach {
            assertTrue(it in summary, "$it is not in home.summary")
        }

        val list = call(cloud, "services.list")
        Contract.assertValid("listResult", list)
        val service = call(cloud, "services.get", buildJsonObject { put("id", "pizza-north") })
        Contract.assertValid("itemResult", service)
        val links = recordOf(service)!!.links()
        assertEquals("pizza-north", links.first().open.server)
        assertEquals(true, links.last().quiet)
        // Every block of the card's summary is in the record of a running service.
        val blocks = declaration.app("services")!!.method("services.get")!!.ui!!.summary.orEmpty()
        // The metrics are; the notice (P12) and the steps (P13) are only where there is something to say.
        blocks.filter { it.type != SummaryBlock.NOTICE && it.type != SummaryBlock.STEPS }
            .forEach { assertTrue(it.field in recordOf(service)!!, "${it.field} is not in services.get") }
        assertTrue("notice" !in recordOf(service)!! && "launch" !in recordOf(service)!! && "_refresh" !in recordOf(service)!!)
        val failed = call(cloud, "services.get", buildJsonObject { put("id", "burger-lenina") })
        Contract.assertValid("itemResult", failed)
        assertTrue("notice" in recordOf(failed)!! && "launch" in recordOf(failed)!!)
        val starting = call(cloud, "services.get", buildJsonObject { put("id", "sushi-market") })
        Contract.assertValid("itemResult", starting)
        assertTrue("launch" in recordOf(starting)!! && "_refresh" in recordOf(starting)!!)
        // A stopped one says so, and what to do, in its notice (P12) — no numbers.
        val stopped = recordOf(call(cloud, "services.get", buildJsonObject { put("id", "coffee-zerno") }))!!
        assertTrue("notice" in stopped && "cpu" !in stopped)

        Contract.assertValid("itemResult", call(cloud, "balance.get"))
        Contract.assertValid("listResult", call(cloud, "balance.transactions"))
    }

    @Test
    fun theCloudSpeaksMoneyNotCredits(): Unit = runBlocking {
        val cloud = DemoCloud()
        val texts = buildList {
            add(Contract.wire.encodeToString(Declaration.serializer(), cloud.describe(DemoCloud.TOKEN, "ru").value))
            listOf("home.summary", "services.list", "balance.get", "balance.transactions", "account.get").forEach { add(call(cloud, it).toString()) }
            add(call(cloud, "services.get", buildJsonObject { put("id", "pizza-north") }).toString())
        }
        texts.forEach { text ->
            assertFalse(Regex("""(^|[^а-яё])кр([^а-яё]|$)|кредит|credit""", RegexOption.IGNORE_CASE).containsMatchIn(text), text.take(300))
        }
    }

    @Test
    fun theBalanceTurnsAmberUnderAWeekAndRedUnderThreeDays(): Unit = runBlocking {
        assertEquals(AccountBalance(172.8, 4, Tones.WARNING), DemoCloud(money = 172.8).describe(DemoCloud.TOKEN, "ru").value.balance)
        assertEquals(Tones.DANGER, DemoCloud(money = 80.0).describe(DemoCloud.TOKEN, "ru").value.balance?.tone)
        assertEquals(Tones.DANGER, DemoCloud(money = 0.0).describe(DemoCloud.TOKEN, "ru").value.balance?.tone)
    }
}
