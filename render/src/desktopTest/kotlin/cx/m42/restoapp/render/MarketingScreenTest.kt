@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.render

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.composeunstyled.Text
import cx.m42.restoapp.demo.DemoRole
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.runAppTest
import cx.m42.superizer.ui.components.AppButton
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        node("marketing:title").assertTextEquals("Маркетинг")
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
        node("marketing:title").assertTextEquals("Маркетинг")
        assertNull(screen.chrome.value.title)
    }

    @Test
    fun theChipsOfTheSummaryAskTheServerForAnotherPeriod() = marketing {
        node("marketing:field-applications:value").assertTextEquals("3")
        tap("marketing:chip-7")
        idle()
        // Only what was typed within the week is counted then.
        node("marketing:field-applications:value").assertTextEquals("2")
        tap("marketing:chip-90")
        idle()
        node("marketing:field-applications:value").assertTextEquals("4")
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
                AppButton(
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
}
