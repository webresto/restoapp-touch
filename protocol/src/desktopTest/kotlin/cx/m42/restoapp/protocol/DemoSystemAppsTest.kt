package cx.m42.restoapp.protocol

import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.demo.DemoRole
import cx.m42.restoapp.demo.DemoStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * «Каналы продаж», «Настройки» and «Система» on the demo server: what a connected server declares is here too, it
 * speaks the contract, and the three lean on each other the way they do on a real server.
 */
class DemoSystemAppsTest {

    private class MemoryStore(var text: String? = null) : DemoStore {
        override suspend fun read(): String? = text
        override suspend fun write(text: String) {
            this.text = text
        }
    }

    private var now = 1_700_000_000_000

    private fun demo(store: MemoryStore = MemoryStore()) =
        DemoBackend(store, DemoBackend.TWO_POINTS, clock = { now }, serverName = "Демо-ресторан")

    private suspend fun DemoBackend.ask(method: String, vararg params: Pair<String, JsonElement>): JsonElement =
        call("demo", method, JsonObject(mapOf(*params)), null, "ru").value

    private suspend fun DemoBackend.card(method: String, id: String): JsonObject =
        recordOf(ask(method, "id" to JsonPrimitive(id)))!!

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun refusalOf(block: suspend () -> Unit): MobileApiException =
        assertFailsWith<MobileApiException> { runBlocking { block() } }

    @Test
    fun theAdministratorHasTheThreeAppsAndNobodyElseDoes(): Unit = runBlocking {
        val demo = demo()
        val admin = demo.describe("demo", "ru").value
        for (id in listOf("channels", "settings", "system")) assertNotNull(admin.app(id), id)
        assertEquals(listOf("Система"), listOf("channels", "settings", "system").map { admin.app(it)!!.category }.distinct())

        for (role in listOf(DemoRole.Operator, DemoRole.Stock, DemoRole.Marketer)) {
            demo.setRole(role)
            val declared = demo.describe("demo", "ru").value.apps.map { it.id }
            for (id in listOf("channels", "settings", "system")) assertFalse(id in declared, "$role has $id")
            assertEquals(MobileApiException.FORBIDDEN, refusalOf { demo.ask("system.home") }.code)
        }
    }

    @Test
    fun theAnswersOfTheThreeAppsAreTheEnvelopesOfTheContract(): Unit = runBlocking {
        val demo = demo()
        val admin = demo.describe("demo", "ru").value
        val lists = mapOf("channels.list" to "channels.get", "settings.list" to "settings.get", "system.modules" to "system.module", "system.catalog" to "system.catalogItem", "channels.types" to "channels.type")
        for ((list, open) in lists) {
            val answer = demo.ask(list)
            Contract.assertValid("listResult", answer)
            val rows = ListPage.of(answer).items
            assertTrue(rows.isNotEmpty(), list)
            // A row with a link of its own leads there; every other one opens as a card of the contract.
            for (row in rows.filter { it.open() == null }) Contract.assertValid("itemResult", demo.ask(open, "id" to JsonPrimitive(row.id()!!)))
            // Every method a record offers is declared, or the client would draw an action the server refuses.
            for (row in rows) for (action in row.actions()) assertNotNull(admin.apps.firstNotNullOfOrNull { it.method(action.method) }, action.method)
        }
        Contract.assertValid("itemResult", demo.ask("system.home"))
    }

    @Test
    fun theHomeOfTheSystemLeadsToWhatThereIs(): Unit = runBlocking {
        val demo = demo()
        val admin = demo.describe("demo", "ru").value
        val links = recordOf(demo.ask("system.home"))!!.links()
        assertEquals(listOf("system.modules", "system.catalog", "settings.list", "channels.list"), links.map { it.open.method })
        assertEquals(listOf(null, null, "settings", "channels"), links.map { it.open.app })
        for (link in links) assertNotNull(admin.app(link.open.app ?: "system")!!.method(link.open.method!!), link.title)
    }

    /** The whole point of a channel screen: it says why a channel does not work, and the way out really works. */
    @Test
    fun aChannelNeedsItsSetupAndTheSettingsFinishIt(): Unit = runBlocking {
        val demo = demo()
        val channel = demo.card("channels.get", "ch-telegram")
        assertEquals("needs_setup", channel.text("status"))
        assertEquals("settings.list", channel.links().single().open.method)
        assertEquals("settings", channel.links().single().open.app)

        val refused = refusalOf { demo.ask("channels.toggle", "id" to JsonPrimitive("ch-telegram"), "enabled" to JsonPrimitive(true)) }
        assertEquals(MobileApiException.CONFLICT, refused.code)
        assertTrue(refused.message!!.startsWith("Сначала настройте канал"), refused.message)

        // The line leads to the rows of the provider, and the token among them is what is missing.
        val rows = ListPage.of(demo.ask("settings.list", "q" to JsonPrimitive("sales-channel-telegram"))).items
        assertEquals(listOf("SALES_CHANNEL_TELEGRAM_TOKEN"), rows.map { it.text("key") })
        demo.ask("settings.update", "id" to JsonPrimitive("SALES_CHANNEL_TELEGRAM_TOKEN"), "value" to JsonPrimitive("123:abc"))

        assertEquals("ready", demo.card("channels.get", "ch-telegram").text("status"))
        val on = recordOf(demo.ask("channels.toggle", "id" to JsonPrimitive("ch-telegram"), "enabled" to JsonPrimitive(true)))!!
        assertEquals(true, (on["enabledText"] as JsonPrimitive).content.toBoolean())
    }

    /** From a channel with no provider, through the marketplace and the job, to a channel that can be set up. */
    @Test
    fun aMissingProviderIsInstalledFromTheChannel(): Unit = runBlocking {
        val demo = demo()
        val channel = demo.card("channels.get", "ch-vk")
        assertEquals("error", channel.text("status"))
        assertEquals(false, (channel["enabledText"] as JsonPrimitive).content.toBoolean())
        assertTrue(channel.actions().none { it.method == "channels.toggle" }, "no live provider, no switch")
        val install = channel.links().single()
        assertEquals("system", install.open.app)

        val found = ListPage.of(demo.call("demo", install.open.method!!, install.open.params, null, "ru").value).items
        assertEquals(listOf("sales-channel-vk"), found.map { it.id() })

        val started = recordOf(demo.ask("system.install", "appId" to JsonPrimitive("sales-channel-vk")))!!
        val jobId = JsonPrimitive(started.text("jobId")!!)
        assertEquals("running", recordOf(demo.ask("system.job", "id" to jobId))!!.text("state"))
        // The same button again comes back to the job; another one waits for it.
        assertEquals(jobId.content, recordOf(demo.ask("system.install", "appId" to JsonPrimitive("sales-channel-vk")))!!.text("jobId"))
        assertEquals(MobileApiException.CONFLICT, refusalOf { demo.ask("system.update", "appId" to JsonPrimitive("admin-frontend")) }.code)

        now += 60_000
        val done = recordOf(demo.ask("system.job", "id" to jobId))!!
        assertEquals("done", done.text("state"))
        assertNotNull(done.text("restartText"))

        assertEquals("needs_setup", demo.card("channels.get", "ch-vk").text("status"))
        assertTrue(ListPage.of(demo.ask("system.catalog")).items.none { it.id() == "sales-channel-vk" })
        assertEquals("settings.list", demo.card("channels.get", "ch-vk").links().single().open.method)
    }

    @Test
    fun aJobTheCompatibilityRefusesEndsInWordsAndChangesNothing(): Unit = runBlocking {
        val demo = demo()
        val before = ListPage.of(demo.ask("system.modules")).total
        val jobId = JsonPrimitive(recordOf(demo.ask("system.install", "appId" to JsonPrimitive("payment-terminal")))!!.text("jobId")!!)
        now += 60_000
        val failed = recordOf(demo.ask("system.job", "id" to jobId))!!
        assertEquals("failed", failed.text("state"))
        assertTrue(failed.text("errorText")!!.contains("core"))
        assertEquals(before, ListPage.of(demo.ask("system.modules")).total)
    }

    @Test
    fun aProgramIsRemovedOnlyWhenNothingNeedsIt(): Unit = runBlocking {
        val demo = demo()
        assertTrue(demo.card("system.module", "core").actions().isEmpty())
        val needed = demo.card("system.module", "simple-navigation")
        assertTrue(needed.actions().none { it.method == "system.remove" })
        assertTrue(needed.text("removeText")!!.contains("admin-frontend"))

        val free = demo.card("system.module", "sales-channel-qr")
        assertEquals(listOf("system.remove"), free.actions().map { it.method })
        demo.ask("system.remove", "appId" to JsonPrimitive("sales-channel-qr"))
        now += 60_000

        assertEquals(MobileApiException.NOT_FOUND, refusalOf { demo.card("system.module", "sales-channel-qr") }.code)
        val gone = recordOf(demo.ask("system.remove", "appId" to JsonPrimitive("sales-channel-qr"), "job" to JsonPrimitive("job-1")))!!
        assertEquals("removed", gone.text("state"))
        // Its channel says why it stopped, and only now may it be deleted; its settings left with it.
        val channel = demo.card("channels.get", "ch-qr")
        assertEquals("error", channel.text("status"))
        assertTrue(channel.actions().any { it.method == "channels.delete" })
        assertTrue(ListPage.of(demo.ask("settings.list", "q" to JsonPrimitive("sales-channel-qr"))).items.isEmpty())
    }

    @Test
    fun theRestartIsPlannedOnceThenBroughtForwardThenOver(): Unit = runBlocking {
        val demo = demo()
        fun whens(card: JsonObject) = card.actions().single().input
        assertNull(whens(recordOf(demo.ask("system.home"))!!), "the declared input: both buttons")

        val planned = recordOf(demo.ask("system.restart", "when" to JsonPrimitive("midnight")))!!
        assertNotNull(planned.text("restartText"))
        assertEquals(listOf("now"), whens(planned)!!.property("when")!!.enumValues())
        assertEquals(MobileApiException.INVALID, refusalOf { demo.ask("system.restart", "when" to JsonPrimitive("midnight")) }.code)

        val bootBefore = demo.card("settings.get", "TZ").text("boot")
        val going = recordOf(demo.ask("system.restart", "when" to JsonPrimitive("now")))!!
        assertEquals("Сервер перезапускается", going.text("restartText"))
        assertTrue(going.actions().isEmpty())

        now += 60_000
        val back = recordOf(demo.ask("system.home"))!!
        assertNull(back.text("restartText"))
        assertNotEquals(bootBefore, demo.card("settings.get", "TZ").text("boot"), "a reminder of the run before is spent")
    }

    @Test
    fun theSettingsRefuseWhatTheServerWould(): Unit = runBlocking {
        val demo = demo()
        fun update(key: String, value: JsonElement) = refusalOf { demo.ask("settings.update", "id" to JsonPrimitive(key), "value" to value) }

        assertEquals(MobileApiException.FORBIDDEN, update("RMS_LAST_SUCCESSFUL_MENU_DISHES_SYNC_AT", JsonPrimitive("x")).code)
        assertEquals(MobileApiException.FORBIDDEN, update("IMAGES_URL", JsonPrimitive("https://x.example/")).code)
        assertEquals(MobileApiException.INVALID, update("SYNC_PRODUCTS_INTERVAL_SECONDS", JsonPrimitive(5)).code)
        assertEquals(MobileApiException.INVALID, update("PROJECT_NAME", JsonPrimitive("Не латиница")).code)
        assertEquals(MobileApiException.INVALID, update("ALLOWED_PHONE_COUNTRIES", buildJsonArray { add(JsonPrimitive("XX")) }).code)
        assertEquals(MobileApiException.INVALID, update("CITY", JsonNull).code)

        // No action where the write is refused anyway, one everywhere else.
        assertTrue(demo.card("settings.get", "IMAGES_URL").actions().isEmpty())
        assertEquals(listOf("settings.update"), demo.card("settings.get", "CITY").actions().map { it.method })

        // A value cleared goes back to its default, and the chips say so.
        val changed = ListPage.of(demo.ask("settings.list", "kind" to JsonPrimitive("changed"))).counts.getValue("changed")
        demo.ask("settings.update", "id" to JsonPrimitive("SHOW_UNAVAILABLE_DISHES"), "value" to JsonNull)
        assertEquals("Нет", demo.card("settings.get", "SHOW_UNAVAILABLE_DISHES").text("valueText"))
        assertEquals(changed - 1, ListPage.of(demo.ask("settings.list")).counts.getValue("changed"))
    }

    @Test
    fun aSecretIsWrittenAndNeverRead(): Unit = runBlocking {
        val demo = demo()
        val secret = demo.card("settings.get", "JWT_SECRET")
        assertEquals("••••••••", secret.text("valueText"))
        assertEquals(JsonNull, secret["value"])
        val dump = demo.ask("settings.dump").jsonObject
        val keys = dump.getValue("settings").jsonArray.map { it.jsonObject.text("key") }
        assertFalse("JWT_SECRET" in keys)
        assertFalse(dump.toString().contains("demo-secret"))
        assertEquals(keys.size, (dump.getValue("count") as JsonPrimitive).content.toInt())
    }

    @Test
    fun anImportShowsWhatItWouldChangeAndAppliesWhatWasPicked(): Unit = runBlocking {
        val demo = demo()
        val entries = buildJsonArray {
            add(buildJsonObject { put("key", "CITY"); put("value", "Казань") })
            add(buildJsonObject { put("key", "DEFAULT_LOCALE"); put("value", "ru") })
            add(buildJsonObject { put("key", "IMAGES_URL"); put("value", "https://x.example/") })
            add(buildJsonObject { put("key", "NO_SUCH_KEY"); put("value", 1) })
            add(buildJsonObject { put("key", "SYNC_PRODUCTS_INTERVAL_SECONDS"); put("value", 5) })
        }
        val diff = demo.ask("settings.transfer", "settings" to entries, "preview" to JsonPrimitive(true)).jsonObject.getValue("diff").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("changed", "unchanged", "changed", "not_found", "changed"),
            diff.map { it.text("status") },
        )
        assertEquals(true, (diff[2]["readOnly"] as JsonPrimitive).content.toBoolean())
        assertEquals("Москва", demo.card("settings.get", "CITY").text("valueText"), "a preview writes nothing")

        val keys = buildJsonArray { add(JsonPrimitive("CITY")); add(JsonPrimitive("SYNC_PRODUCTS_INTERVAL_SECONDS")); add(JsonPrimitive("IMAGES_URL")) }
        val results = demo.ask("settings.transfer", "settings" to entries, "keys" to keys).jsonObject.getValue("results").jsonArray.map { it.jsonObject }
        assertEquals(
            mapOf("CITY" to "applied", "IMAGES_URL" to "skipped", "SYNC_PRODUCTS_INTERVAL_SECONDS" to "error"),
            results.associate { it.text("key")!! to it.text("status")!! },
        )
        assertEquals("Казань", demo.card("settings.get", "CITY").text("valueText"))
    }

    /** A demo that was opened before these apps existed has them filled in, and keeps everything it held. */
    @Test
    fun theStorageOfAnOlderDemoComesUpWithTheAppsAndKeepsItsData(): Unit = runBlocking {
        val store = MemoryStore()
        demo(store).call("demo", "dishes.setBalance", jsonObjectOf("id" to JsonPrimitive("d1"), "balance" to JsonPrimitive(7)), "p1", "ru")
        val old = ProtocolJson.parseToJsonElement(store.text!!).jsonObject
        val without = JsonObject(old - setOf("settingValues", "channels", "modules", "restartScheduled", "restartOrderedAt", "boot"))
        val stored = MemoryStore(without.toString())

        val again = demo(stored)
        assertEquals("7", ListPage.of(again.call("demo", "dishes.list", jsonObjectOf("filter" to JsonPrimitive("all")), "p1", "ru").value).items.first { it.id() == "d1" }.text("localBalance"))
        assertTrue(ListPage.of(again.ask("channels.list")).items.size > 1)
        assertEquals("Москва", again.card("settings.get", "CITY").text("valueText"))
        assertTrue(ListPage.of(again.ask("system.modules")).items.isNotEmpty())
    }

    /** The menu of a client that cached the old declaration must change: the revision is what tells it to ask again. */
    @Test
    fun theRevisionMovedWithTheDeclaration(): Unit = runBlocking {
        assertTrue(demo().describe("demo", "ru").value.revision.endsWith("-4"))
    }
}
