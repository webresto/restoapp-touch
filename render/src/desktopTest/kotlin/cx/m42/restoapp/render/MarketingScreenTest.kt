@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.render

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.composeunstyled.Text
import cx.m42.restoapp.demo.DemoRole
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.runAppTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import cx.m42.restoapp.protocol.Answer
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.superizer.app.asConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import cx.m42.superizer.kit.Button
import cx.m42.superizer.kit.ButtonSize

/**
 * «Маркетинг» drawn by the renderer: the summary as the home screen, and the stack of frames the links of a
 * record open — the codes, a code, where it was typed, and back (`marketing.plan.md`, Q39).
 *
 * Everything on these screens is the declaration of the demo server, which is the hook's own
 * (`apps/marketing.ts`): nothing of the section is a screen of the client, apart from the editor of a promotion,
 * which is an action with a native screen and is checked here as such.
 */
class MarketingScreenTest {

    private val AppTestScope<*>.screen get() = instance as ServerAppInstance

    private fun AppTestScope<*>.tap(tag: String) {
        runCatching { node(tag).performScrollTo() }
        click(tag)
    }

    private fun AppTestScope<*>.advance(millis: Long) {
        val scheduler = (runtime.scope as kotlinx.coroutines.test.TestScope).testScheduler
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
        idle()
    }

    private fun marketing(role: DemoRole = DemoRole.Admin, block: AppTestScope<ServerAppConfig>.() -> Unit) {
        val server = DemoServer(DemoServer.demo(role), "marketing")
        runAppTest(ServerApp(server.app.value!!, "ru"), prepare = { it.provide(RestoServer.Key, server) }) {
            idle()
            block()
        }
    }

    @AfterTest
    fun forgetNativeScreens() {
        NativeActions.clear()
    }

    @Test
    fun theSummaryIsTheHomeScreenWithItsNumbersAndItsLinks() = marketing {
        assertShown("marketing:detail")
        node("marketing:title").assert(hasAnyDescendant(hasText("Маркетинг")))
        node("marketing:field-codesTotal:value").assertTextEquals("4")
        node("marketing:field-promotionsByCodeOnly:value").assertTextEquals("1")
        // The links the server built, in its order; the bottom frame has no «Назад».
        ui.onNodeWithText("Промокоды · 4").assertExists()
        ui.onNodeWithText("Акции · 4").assertExists()
        assertAbsent("marketing:back")
        assertTrue(screen.frames.value.size == 1)
    }

    @Test
    fun aLinkOpensTheListAndBackReturnsToTheSummary() = marketing {
        tap("marketing:link-marketing.promocodes")
        idle()
        assertShown("marketing:list")
        node("marketing:row-code-leto").assertExists()
        assertEquals(2, screen.frames.value.size)
        assertTrue(screen.chrome.value.canGoBack)

        // A row of the list opens the card of the code, and its link leads on to the applications.
        tap("marketing:row-code-leto")
        idle()
        assertShown("marketing:detail")
        node("marketing:field-gives:value").assertTextEquals("−10% на всю корзину")
        node("marketing:field-period:value").assertTextEquals("с 01.06.2026 по 31.08.2026")
        tap("marketing:link-marketing.applications")
        idle()
        assertShown("marketing:list")
        assertEquals(4, screen.frames.value.size)
        assertTrue(screen.list.value.items.all { it["code"] == JsonPrimitive("ЛЕТО10") })

        // And back, frame by frame, to the summary.
        tap("marketing:back")
        idle()
        assertShown("marketing:detail")
        tap("marketing:back")
        idle()
        assertShown("marketing:list")
        tap("marketing:back")
        idle()
        assertShown("marketing:detail")
        node("marketing:title").assert(hasAnyDescendant(hasText("Маркетинг")))
        assertNull(screen.chrome.value.title)
    }

    @Test
    fun theChipsOfTheSummaryAskTheServerForAnotherPeriod() = marketing {
        // The number is a tile of the summary (P7) now, and the card does not repeat it.
        fun applications(text: String) = ui.onNode(hasText(text) and hasAnyAncestor(hasTestTag("marketing:summary-applications"))).assertExists()
        assertAbsent("marketing:field-applications")
        applications("3")
        tap("marketing:chip-7")
        idle()
        // Only what was typed within the week is counted then.
        applications("2")
        tap("marketing:chip-90")
        idle()
        applications("4")
        // The chip travels with the frame: its snapshot brings the same period back.
        assertEquals("90", screen.currentChip())
    }

    @Test
    fun aPercentageIsShownAsOneAndAFlatAmountAsMoney() = marketing {
        tap("marketing:link-marketing.promotions")
        idle()
        tap("marketing:row-pr-summer")
        idle()
        node("marketing:field-discountPercent:value").assertTextEquals("10\u00A0%")
        // The other field is not in the record, so the card does not draw it at all.
        assertAbsent("marketing:field-discountFlat")

        tap("marketing:back")
        idle()
        tap("marketing:row-pr-lunch")
        idle()
        node("marketing:field-discountFlat:value").assertTextEquals("150\u00A0₽")
        assertAbsent("marketing:field-discountPercent")
    }

    @Test
    fun theSearchAndTheChipsOfAPushedListAreItsOwn() = marketing {
        tap("marketing:link-marketing.promocodes")
        idle()
        tap("marketing:chip-scheduled")
        idle()
        assertEquals(listOf("ОСЕНЬ"), screen.list.value.items.map { (it["code"] as JsonPrimitive).content })

        tap("marketing:chip-all")
        idle()
        type("marketing:search", "летняя")
        advance(400)
        // Found by the name of a promotion of the code, as the server searches.
        assertEquals(listOf("ЛЕТО10"), screen.list.value.items.map { (it["code"] as JsonPrimitive).content })
    }

    @Test
    fun theToggleOnARowSwitchesACodeOffAndTheWordFollows() = marketing {
        tap("marketing:link-marketing.promocodes")
        idle()
        assertEquals(JsonPrimitive("active"), screen.list.value.items.first { it["code"] == JsonPrimitive("ЛЕТО10") }["status"])
        tap("marketing:row-code-leto:marketing.setCodeEnabled")
        idle()
        val code = screen.list.value.items.first { it["code"] == JsonPrimitive("ЛЕТО10") }
        assertEquals(JsonPrimitive("disabled"), code["status"])
        assertEquals(JsonPrimitive(false), code["worksNow"])
    }

    /** A promotion that is switched off applies nowhere: «только по коду» is not offered for it (C22). */
    @Test
    fun aSwitchedOffPromotionHasNothingToSayAboutApplyingByItself() = marketing {
        tap("marketing:link-marketing.promotions")
        idle()
        tap("marketing:row-pr-lunch")
        idle()
        assertShown("marketing:marketing.setPromotionOnlyByCode")
        tap("marketing:marketing.setPromotionEnabled")
        idle()
        assertAbsent("marketing:marketing.setPromotionOnlyByCode")
    }

    /** The editor of a promotion: an action with a screen of the client's own (Q43). */
    @Test
    fun theEditorIsTheClientsOwnScreenAndItsAnswerIsTheRecord() {
        var seen: NativeActionScope? = null
        NativeActions.register("promotion-editor") { scope ->
            seen = scope
            Column(modifier = Modifier.testTag("editor")) {
                Text(text = (scope.record["name"] as JsonPrimitive).content, modifier = Modifier.testTag("editor:name"))
                Button(
                    text = "Сохранить",
                    onClick = {
                        scope.submit(
                            buildJsonObject {
                                put("id", scope.record["id"]!!)
                                put("name", "Зимняя скидка")
                                put("enable", true)
                                put("onlyByCode", true)
                                put("configDiscount", buildJsonObject { put("discountType", "percentage"); put("discountAmount", 25) })
                            },
                        )
                    },
                    modifier = Modifier.testTag("editor:save"),
                    size = ButtonSize.Lg,
                )
            }
        }
        marketing {
            tap("marketing:link-marketing.promotions")
            idle()
            tap("marketing:row-pr-summer")
            idle()
            tap("marketing:marketing.savePromotion")
            idle()
            assertShown("editor")
            node("editor:name").assertTextEquals("Летняя скидка")
            assertEquals("Летняя скидка", (seen!!.record["name"] as JsonPrimitive).content)

            tap("editor:save")
            idle()
            // The screen closes itself once the server answered, and the card shows what was saved.
            assertAbsent("editor")
            assertShown("marketing:detail")
            node("marketing:field-discountPercent:value").assertTextEquals("25\u00A0%")
        }
    }

    /** A client that does not have the screen draws no button for the action — and nothing breaks. */
    @Test
    fun withoutTheScreenTheActionIsNotDrawn() = marketing {
        tap("marketing:link-marketing.promotions")
        idle()
        tap("marketing:row-pr-summer")
        idle()
        assertAbsent("marketing:marketing.savePromotion")
    }

    /** The marketer may not read an order: no money on the summary, and no link to the applications. */
    @Test
    fun theMarketerSeesNoMoneyAndNoLinkToTheApplications() = marketing(DemoRole.Marketer) {
        assertShown("marketing:detail")
        node("marketing:field-codesTotal:value").assertTextEquals("4")
        assertAbsent("marketing:field-applications")
        assertAbsent("marketing:link-marketing.applications")
        ui.onNodeWithText("Промокоды · 4").assertExists()
    }

    /** A row that lacks what the list names under its title: nothing is drawn there, not a «—» pill. */
    @Test
    fun aRowWithoutAFieldDrawsNothingInItsPlace() {
        val demo = DemoServer.demo(DemoRole.Admin)
        val named = runBlocking { demo.describe("demo", "ru").value }.app("marketing")!!.method("marketing.promocodes")!!.ui!!.item!!
        val api = object : cx.m42.restoapp.protocol.MobileApi by demo {
            override suspend fun call(token: String, method: String, params: JsonObject, place: String?, lang: String): Answer<JsonElement> {
                val answer = demo.call(token, method, params, place, lang)
                if (method != "marketing.promocodes") return answer
                val root = answer.value.jsonObject
                val rows = root.getValue("items").jsonArray.map { row ->
                    JsonObject(row.jsonObject - setOfNotNull(named.subtitle, named.meta, named.badge, named.value))
                }
                return answer.copy(value = JsonObject(root + ("items" to JsonArray(rows))))
            }
        }
        val server = DemoServer(api, "marketing")
        runAppTest(ServerApp(server.app.value!!, "ru"), prepare = { it.provide(RestoServer.Key, server) }) {
            idle()
            tap("marketing:link-marketing.promocodes")
            idle()
            node("marketing:row-code-leto").assertExists()
            assertTrue(ui.onAllNodesWithText("—", substring = true).fetchSemanticsNodes().isEmpty())
        }
    }

    // ---- a line that leaves the screen: an address (Q53) or another app ----

    /** The home screen of «Маркетинг», with the lines a server may add to any card. */
    private fun withLines(lines: String, address: String = "https://resto.example", block: AppTestScope<ServerAppConfig>.() -> Unit) {
        val demo = DemoServer.demo(DemoRole.Admin)
        val api = object : cx.m42.restoapp.protocol.MobileApi by demo {
            override suspend fun call(token: String, method: String, params: JsonObject, place: String?, lang: String): Answer<JsonElement> {
                val answer = demo.call(token, method, params, place, lang)
                if (method != "marketing.home") return answer
                val root = answer.value.jsonObject
                val item = root.getValue("item").jsonObject
                val links = ProtocolJson.parseToJsonElement(lines)
                return answer.copy(value = JsonObject(root + ("item" to JsonObject(item + ("_links" to links)))))
            }
        }
        val server = DemoServer(api, "marketing", address = address)
        runAppTest(ServerApp(server.app.value!!, "ru"), prepare = { it.provide(RestoServer.Key, server) }) {
            idle()
            block()
        }
    }

    @Test
    fun aLineWithAPathOpensThePageOfThisServerInTheBrowser() =
        withLines("""[{"title":"Настроить","open":{"url":"/admin/frontend/setup?channel=c1"}}]""") {
            ui.onNodeWithText("Настроить").assertExists()
            // A line that leaves the app says so, and the screen stays where it was.
            ui.onNodeWithContentDescription("Откроется в браузере", useUnmergedTree = true).assertExists()
            tap("marketing:link-url-0")
            idle()
            assertEquals(listOf("https://resto.example/admin/frontend/setup?channel=c1"), runtime.navigation.urls)
            assertEquals(1, screen.frames.value.size)
        }

    @Test
    fun aLineWithAnAddressOpensItAsItIs() =
        withLines("""[{"title":"Открыть","open":{"url":"https://shop.example/menu"}}]""") {
            tap("marketing:link-url-0")
            idle()
            assertEquals(listOf("https://shop.example/menu"), runtime.navigation.urls)
        }

    @Test
    fun anAddressOfAnotherProgramIsNotOpenedAndTheScreenSaysSo() =
        withLines("""[{"title":"Открыть","open":{"url":"file:///etc/passwd"}}]""") {
            tap("marketing:link-url-0")
            idle()
            assertTrue(runtime.navigation.urls.isEmpty())
            // Over the summary, above the line that was tapped.
            ui.onNodeWithTag("marketing:notice").assertExists()
            ui.onNodeWithText("Не удалось открыть ссылку").assertExists()
        }

    @Test
    fun aPathWithNoAddressToResolveItAgainstIsNotOpened() =
        withLines("""[{"title":"Настроить","open":{"url":"/admin/setup"}}]""", address = "") {
            tap("marketing:link-url-0")
            idle()
            assertTrue(runtime.navigation.urls.isEmpty())
            // Over the summary, above the line that was tapped.
            ui.onNodeWithTag("marketing:notice").assertExists()
        }

    @Test
    fun aLineIntoAnotherAppCarriesWhereItLeads() =
        withLines("""[{"title":"Установить провайдера","open":{"app":"system","method":"system.catalog","params":{"appId":"sales-channel-kiosk"}}}]""") {
            tap("marketing:link-system.catalog")
            idle()
            val (id, config) = runtime.navigation.opened.single()
            assertEquals("system", id.value)
            val target = config.json.getValue("target").jsonObject
            assertEquals(JsonPrimitive("system.catalog"), target["method"])
            assertEquals(JsonPrimitive("sales-channel-kiosk"), target.getValue("params").jsonObject["appId"])
        }

    @Test
    fun aLineIntoAnAppThatIsNotThereSaysSo() =
        withLines("""[{"title":"Установить провайдера","open":{"app":"system","method":"system.catalog"}}]""") {
            runtime.navigation.unknown += cx.m42.superizer.app.AppId("system")
            tap("marketing:link-system.catalog")
            idle()
            ui.onNodeWithTag("marketing:notice").assertExists()
            ui.onNodeWithText("Этот переход сейчас недоступен").assertExists()
        }

    @Test
    fun anAppOpenedByALineOpensOnThatScreenAndBackIsItsOwnHome() {
        val server = DemoServer(DemoServer.demo(DemoRole.Admin), "marketing")
        val config = buildJsonObject {
            put("target", buildJsonObject { put("method", "marketing.promotions") })
        }.asConfig()
        runAppTest(ServerApp(server.app.value!!, "ru"), config = config, prepare = { it.provide(RestoServer.Key, server) }) {
            idle()
            assertShown("marketing:list")
            assertEquals(listOf("marketing.home", "marketing.promotions"), screen.frames.value.map { it.method })
            screen.back()
            idle()
            assertShown("marketing:detail")
            assertEquals(1, screen.frames.value.size)
        }
    }

    @Test
    fun aLineToAMethodThisPersonDoesNotHaveLeavesTheHomeScreenAndSaysSo() {
        val server = DemoServer(DemoServer.demo(DemoRole.Admin), "marketing")
        val config = buildJsonObject {
            put("target", buildJsonObject { put("method", "marketing.nothing") })
        }.asConfig()
        runAppTest(ServerApp(server.app.value!!, "ru"), config = config, prepare = { it.provide(RestoServer.Key, server) }) {
            idle()
            assertShown("marketing:detail")
            assertEquals(1, screen.frames.value.size)
            assertShown("marketing:notice")
        }
    }
}
