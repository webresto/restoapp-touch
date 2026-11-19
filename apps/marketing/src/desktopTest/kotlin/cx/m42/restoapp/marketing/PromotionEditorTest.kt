@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.marketing

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.demo.DemoRole
import cx.m42.restoapp.demo.DemoStore
import cx.m42.restoapp.protocol.MobileApi
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.protocol.jsonObjectOf
import cx.m42.restoapp.protocol.recordOf
import cx.m42.restoapp.render.NativeActions
import cx.m42.restoapp.render.RestoServer
import cx.m42.restoapp.render.ServerApp
import cx.m42.restoapp.render.ServerAppConfig
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.runAppTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * The editor of a promotion on the demo server: the one screen of «Маркетинг» the client draws itself.
 *
 * It is opened the way a person opens it — from the card of a promotion, by the action the server declares with
 * `control: {type: "native", screen: "promotion-editor"}` — so what is checked here is the whole way through:
 * the record is read into the form, the catalog of the menu chooses products, and what is sent back is the
 * params of `marketing.savePromotion`.
 */
class PromotionEditorTest {

    private class MemoryStore : DemoStore {
        private var text: String? = null
        override suspend fun read(): String? = text
        override suspend fun write(text: String) {
            this.text = text
        }
    }

    private fun demo(): DemoBackend = DemoBackend(
        MemoryStore(),
        listOf(Place("p1", "Ленина, 5", "ул. Ленина, 5")),
        clock = { 1_700_000_000_000 },
        serverName = "Демо",
    ).also { runBlocking { it.setRole(DemoRole.Admin) } }

    private fun AppTestScope<*>.tap(tag: String) {
        runCatching { node(tag).performScrollTo() }
        click(tag)
    }

    /** `type` of the harness appends; a field that already has something in it is replaced. */
    private fun AppTestScope<*>.retype(tag: String, text: String) {
        runCatching { node(tag).performScrollTo() }
        ui.onNodeWithTag(tag).performTextReplacement(text)
        idle()
    }

    @BeforeTest
    fun registerTheEditor() {
        MarketingScreens.register()
    }

    @AfterTest
    fun forgetTheEditor() {
        NativeActions.clear()
    }

    private fun editor(block: AppTestScope<ServerAppConfig>.(MobileApi) -> Unit) {
        val backend = demo()
        val server = EditorServer(backend)
        runAppTest(ServerApp(server.app.value!!, "ru"), prepare = { it.provide(RestoServer.Key, server) }) {
            idle()
            // From the summary to the promotions, to a card, to the editor — as a person gets there.
            tap("marketing:link-marketing.promotions")
            idle()
            tap("marketing:row-pr-summer")
            idle()
            tap("marketing:marketing.savePromotion")
            idle()
            assertShown("marketing:editor")
            block(backend)
        }
    }

    @Test
    fun theRecordIsReadIntoTheFormAndTheSummarySaysWhatItWillGive() = editor {
        node("marketing:editor-name").assertTextEquals("Летняя скидка")
        node("marketing:editor-amount").assertTextEquals("10")
        node("marketing:editor-summary").assertTextEquals("−10% вся корзина")
        // «Только по промокоду» is on, as the promotion has it.
        ui.onNodeWithText("Только по промокоду").assertExists()
    }

    @Test
    fun aNameAndADescriptionAreRequiredBeforeAnythingIsSent() = editor { backend ->
        val before = runBlocking { recordOf(backend.call("demo", "marketing.promotion", jsonObjectOf("id" to JsonPrimitive("pr-summer")), null, "ru").value) }
        retype("marketing:editor-name", "")
        tap("marketing:editor-save")
        idle()
        assertShown("marketing:editor-refusal")
        ui.onNodeWithText("Название и описание обязательны").assertExists()
        // Nothing went to the server: the record is as it was.
        val after = runBlocking { recordOf(backend.call("demo", "marketing.promotion", jsonObjectOf("id" to JsonPrimitive("pr-summer")), null, "ru").value) }
        assertEquals(before!!["name"], after!!["name"])
        assertShown("marketing:editor")
    }

    @Test
    fun aPercentageOverAHundredIsRefusedBeforeTheServerIsAsked() = editor {
        retype("marketing:editor-amount", "120")
        tap("marketing:editor-save")
        idle()
        assertShown("marketing:editor-refusal")
        ui.onNodeWithText("Процент не больше 100").assertExists()
    }

    @Test
    fun whatIsSavedIsWhatTheCardShowsAfterwards() = editor { backend ->
        retype("marketing:editor-name", "Зимняя скидка")
        retype("marketing:editor-amount", "25")
        tap("marketing:editor-day-monday")
        tap("marketing:editor-service-delivery")
        tap("marketing:editor-save")
        idle()

        // The editor closes itself, and the card of the promotion is what is under it.
        assertAbsent("marketing:editor")
        assertShown("marketing:detail")
        node("marketing:field-discountPercent:value").assertTextEquals("25 %")

        val saved = runBlocking { recordOf(backend.call("demo", "marketing.promotion", jsonObjectOf("id" to JsonPrimitive("pr-summer")), null, "ru").value) }!!
        assertEquals(JsonPrimitive("Зимняя скидка"), saved["name"])
        assertEquals(JsonPrimitive(25), saved["discountPercent"])
    }

    @Test
    fun theCatalogOfTheMenuChoosesTheProductsOfTheDiscount() = editor {
        tap("marketing:editor-dishes")
        idle()
        assertShown("marketing:picker")
        // The groups of the menu unfold here; a product is what is ticked, and «Готово» applies the ticks.
        tap("marketing:picker-open-Пицца")
        idle()
        tap("marketing:picker-dish-d1")
        idle()
        ui.onNodeWithText("Выбрано: 1").assertExists()
        ui.onNodeWithText("Готово").performClick()
        idle()
        assertAbsent("marketing:picker")
        assertShown("marketing:editor")
        // The chosen product is named on the field, as the catalog calls it.
        ui.onNodeWithText("Пепперони 30 см").assertExists()
        node("marketing:editor-summary").assertTextEquals("−10% выбранные блюда и группы")
    }

    @Test
    fun cancellingThePickerKeepsTheChoiceAsItWas() = editor {
        tap("marketing:editor-groups")
        idle()
        tap("marketing:picker-group-Пицца")
        idle()
        ui.onNodeWithText("Отмена").performClick()
        idle()
        assertAbsent("marketing:picker")
        assertAbsent("marketing:editor-unsaved")
        node("marketing:editor-summary").assertTextEquals("−10% вся корзина")
    }

    @Test
    fun anEditMarksTheFormNotSavedAndOnlyThenSaveIsOffered() = editor {
        assertAbsent("marketing:editor-unsaved")
        node("marketing:editor-save").assertIsNotEnabled()
        retype("marketing:editor-amount", "15")
        assertShown("marketing:editor-unsaved")
        node("marketing:editor-save").assertIsEnabled()
        node("marketing:editor-summary").assertTextEquals("−15% вся корзина")
    }

    /** core refuses a gift the guest could buy, and so does the screen: the rule is one (`validateGift`). */
    @Test
    fun aGiftMustBeAProductTheGuestCannotAdd() = editor {
        tap("marketing:editor-gift")
        idle()
        assertShown("marketing:picker")
        tap("marketing:picker-open-Пицца")
        idle()
        // Every product of the demo's menu is for sale: nothing here may be a gift, and tapping does nothing.
        tap("marketing:picker-dish-d1")
        idle()
        assertShown("marketing:picker")
    }

    @Test
    fun cancellingLeavesTheCardAsItWas() = editor {
        retype("marketing:editor-name", "Не сохранится")
        tap("marketing:editor-cancel")
        idle()
        assertShown("unsaved:question")
        tap("unsaved:leave")
        idle()
        assertAbsent("marketing:editor")
        assertShown("marketing:detail")
        node("marketing:title").assert(hasAnyDescendant(hasText("Летняя скидка")))
    }

    @Test
    fun nothingChangedLeavesWithoutAsking() = editor {
        tap("marketing:editor-cancel")
        idle()
        assertAbsent("unsaved:question")
        assertAbsent("marketing:editor")
    }

    @Test
    fun saveAndLeaveTakesTheNewNameToTheCard() = editor {
        retype("marketing:editor-name", "Зимняя скидка")
        tap("marketing:editor-cancel")
        idle()
        tap("unsaved:save")
        idle()
        assertAbsent("marketing:editor")
        node("marketing:title").assert(hasAnyDescendant(hasText("Зимняя скидка")))
    }

    @Test
    fun anInvalidFormStaysOpenAndSaysWhy() = editor {
        retype("marketing:editor-name", "")
        tap("marketing:editor-cancel")
        idle()
        tap("unsaved:save")
        idle()
        assertAbsent("unsaved:question")
        assertShown("marketing:editor-refusal")
        assertShown("marketing:editor")
    }
}
