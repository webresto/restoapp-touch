@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package cx.m42.restoapp.pos

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.protocol.MobileApi
import cx.m42.restoapp.protocol.MobileApiException
import cx.m42.restoapp.protocol.Place
import cx.m42.restoapp.protocol.ProtocolJson
import cx.m42.restoapp.render.RestoServer
import cx.m42.restoapp.render.ServerApp
import cx.m42.restoapp.render.ServerAppConfig
import cx.m42.superizer.app.AppInstance
import cx.m42.superizer.runtime.LocalAppRuntime
import cx.m42.superizer.testing.FakeAppRuntime
import cx.m42.superizer.theme.SuperizerTheme
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Not a check but a look: every state the POS can be in, as PNGs in `apps/pos/build/screenshots`,
 * at the window's size. Two of them are the answers of a stubbed server — a receipt with a basket
 * and one cooked at another kitchen — because the methods that fill a basket are slice B of
 * `order-entry.plan.md`; the file names say so.
 */
class PosScreenshotsTest {

    private val out = File("build/screenshots").apply { mkdirs() }

    private fun ComposeUiTest.shot(name: String) {
        waitForIdle()
        // The whole scene, dialogs included: a dialog is a popup, a second root `onRoot` would refuse.
        val image = (this as? androidx.compose.ui.test.SkikoComposeUiTest)?.captureToImage() ?: onRoot().captureToImage()
        javax.imageio.ImageIO.write(image.toAwtImage(), "png", File(out, "$name.png"))
    }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /**
     * A search of the POS waits out the typing before it asks the server. These screens run on a
     * real dispatcher, not a test one, so the wait is a real one too.
     */
    /** The receipt is longer than the window: scroll a row into view, then tap it. */
    private fun ComposeUiTest.tapBelow(tag: String) {
        runCatching { onNodeWithTag(tag).performScrollTo() }
        waitForIdle()
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /** The «+» of the kit's stepper inside [tag]. */
    private fun ComposeUiTest.plus(tag: String) {
        onNode(hasContentDescription("+") and hasAnyAncestor(hasTestTag(tag))).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.settle(millis: Long = 450) {
        Thread.sleep(millis)
        waitForIdle()
    }

    /** The POS alone, as the host composes it: the theme, the runtime, the server behind it. */
    private fun screen(server: RestoServer, declared: DeclaredApp, block: ComposeUiTest.(AppInstance) -> Unit) =
        runDesktopComposeUiTest(width = 420, height = 820) {
            val runtime = FakeAppRuntime(
                appId = cx.m42.superizer.app.AppId(declared.id),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                declaredServices = setOf(RestoServer.Key),
            )
            runtime.provide(RestoServer.Key, server)
            val app = if (PosApp.canDraw(declared)) PosApp(declared, "ru") else ServerApp(declared, "ru")
            val instance = when (app) {
                is PosApp -> app.launch(runtime, PosConfig())
                else -> (app as ServerApp).launch(runtime, ServerAppConfig())
            }
            runBlocking { instance.onLaunch() }
            setContent {
                SuperizerTheme {
                    CompositionLocalProvider(LocalAppRuntime provides runtime) {
                        Box(Modifier) { instance.Content() }
                    }
                }
            }
            waitForIdle()
            block(instance)
        }

    private fun demo(places: List<Place> = DemoBackend.TWO_POINTS) = PosServerStub.demo(places)

    @Test
    fun everyStateOfTheTill() {
        // 1. Нет точек: группе не отметили ни одной — приложение есть, работать негде.
        screen(NoPlacesServer(PosServerStub(demo())), PosServerStub.declared().copy(places = emptyList())) { shot("pos-01-no-places") }

        // 2. Пусто: точка выбрана, черновиков нет.
        screen(PosServerStub(demo()), PosServerStub.declared()) { shot("pos-02-no-drafts") }

        // 3. Три черновика, один — на найденного клиента.
        val backend = demo()
        val filling = PosServerStub(backend)
        runBlocking {
            filling.call("pos.start", JsonObject(emptyMap()))
            filling.call("pos.start", buildJsonObject { put("clientId", JsonPrimitive("u1")) })
            filling.call("pos.start", JsonObject(emptyMap()))
        }
        screen(PosServerStub(backend), PosServerStub.declared(backend)) { shot("pos-03-drafts") }

        // 4–16. Один заказ от пустого чека до принятого.
        screen(PosServerStub(demo()), PosServerStub.declared()) { app ->
            val pos = app as PosInstance
            tap("pos:new")
            shot("pos-04-receipt-new")

            tap("pos:add")
            shot("pos-05-catalog-groups")
            tap("pos:group-Пицца")
            shot("pos-06-catalog-products")
            val product = pos.state.value.catalog?.catalog?.items?.first()!!
            // A pizza is composed before it goes in: the chooser opens with the server's defaults
            // ticked, and confirming them is the «+» of an operator in a hurry.
            tap("pos:product-${product.id}:add")
            tap("pos:options-save")
            tap("pos:catalog-search")
            onNodeWithTag("pos:catalog-search").performTextInput("мор")
            settle()
            shot("pos-07-catalog-search")
            val drink = pos.state.value.catalog?.catalog?.items?.firstOrNull()
            drink?.let { tap("pos:product-${it.id}:add") }
            // Out of the catalog, to the receipt.
            tap("pos:to-receipt")
            shot("pos-08-receipt-lines")

            tap("pos:step-service")
            shot("pos-09-sheet-service")
            tap("pos:service-dine-in")
            tap("pos:step-service")
            onNodeWithTag("pos:table-input").performTextInput("12")
            tap("pos:table-save")
            waitForIdle()

            tap("pos:step-guest")
            onNodeWithTag("pos:guest-name").performTextInput("Сергей Крылов")
            onNodeWithTag("pos:guest-phone").performTextInput("+7 999 123-45-67")
            shot("pos-10-sheet-guest")
            tap("pos:guest-save")
            waitForIdle()

            tap("pos:step-time")
            shot("pos-11-sheet-time")
            tap("pos:time-asap")
            waitForIdle()

            tap("pos:step-payment")
            shot("pos-12-sheet-payment")
            tap("pos:payment-cash")
            waitForIdle()

            tap("pos:step-promocode")
            onNodeWithTag("pos:promocode-input").performTextInput("DEMO10")
            tap("pos:promocode-save")
            waitForIdle()
            shot("pos-13-receipt-discount")

            tapBelow("pos:check")
            settle(200)
            // The end of the receipt, where the answer of the check and the two buttons are.
            runCatching { onNodeWithTag("pos:place-order").performScrollTo() }
            waitForIdle()
            shot("pos-14-checked")

            tapBelow("pos:place-order")
            settle(200)
            shot("pos-16-placed")
        }

        // 17–19. Доставка: свой шаг адреса, цена доставки в чеке, поиск клиента.
        screen(PosServerStub(demo()), PosServerStub.declared()) { app ->
            val pos = app as PosInstance
            tap("pos:new")
            tap("pos:step-service")
            tap("pos:service-delivery")
            waitForIdle()
            tap("pos:step-address")
            onNodeWithTag("pos:address-street").performTextInput("Садовая")
            settle()
            shot("pos-17-sheet-address")
            onNodeWithTag("pos:address-home").performTextInput("3")
            tap("pos:address-save")
            waitForIdle()
            tap("pos:add")
            tap("pos:group-Супы")
            val soup = pos.state.value.catalog?.catalog?.items?.first()!!
            tap("pos:product-${soup.id}:add")
            tap("pos:to-receipt")
            shot("pos-18-receipt-delivery")

            tap("pos:step-guest")
            tap("pos:guest-find")
            waitForIdle()
            shot("pos-19-sheet-clients")
        }

        // 20. Точка самовывоза: тот же чек, другой способ получения.
        screen(PosServerStub(demo()), PosServerStub.declared()) {
            tap("pos:place")
            mainClock.advanceTimeBy(600)
            shot("pos-20-points")
            tap("pos:places:item-p2")
            tap("pos:new")
            shot("pos-21-receipt-pickup")
        }

        // 22. Одна точка у сервера — строки переключателя нет вовсе.
        val single = demo(DemoBackend.ONE_POINT)
        screen(PosServerStub(single), PosServerStub.declared(single)) {
            tap("pos:new")
            shot("pos-22-one-point")
        }

        // 23. Сервер отказал: красная строка и «Повторить» вместо выдуманного экрана.
        screen(FailingServer(PosServerStub(demo())), PosServerStub.declared()) { shot("pos-23-error") }

        // 24. Слово хоста — права изменились: жёлтая строка над списком.
        screen(PosServerStub(demo()), PosServerStub.declared()) { app ->
            val server = (app as PosInstance).server as PosServerStub
            runBlocking { server.notices.emit("Права изменились") }
            shot("pos-24-notice")
        }

        // 25–26. Проверять нечего: ни блюд, ни гостя — кнопка выключена, пока сервер не скажет
        // «можно». И отказ сервера на шаге: чек остаётся, причина написана.
        screen(PosServerStub(demo()), PosServerStub.declared()) { app ->
            val pos = app as PosInstance
            tap("pos:new")
            shot("pos-25-nothing-to-check")

            tap("pos:step-promocode")
            onNodeWithTag("pos:promocode-input").performTextInput("НЕТ")
            tap("pos:promocode-save")
            settle(200)
            shot("pos-26-refused-promocode")
            tap("pos:sheet-promocode:close")
            tapBelow("pos:more")
            tap("pos:drop")
            shot("pos-27-drop-confirm")
        }

        // 27. Заказ, который по адресу готовит другая кухня (ответ на Q27) — ответ подставного
        // сервера: демо так не отвечает, а экран это рисует.
        screen(ScriptedServer(PosServerStub(demo()), OTHER_KITCHEN), PosServerStub.declared()) { shot("pos-28-other-kitchen-stub") }

        // 28. Сборка клиента без нативной кассы: приложение не прячется и не падает.
        screen(PosServerStub(demo()), PosServerStub.declared().copy(client = cx.m42.restoapp.protocol.ClientRender("kitchen", 1))) {
            shot("pos-29-needs-newer-client")
        }

        // 30–32. Состав блюда: выбор по группам, собранная позиция в чеке и отказ сервера, когда
        // обязательную группу оставили пустой.
        screen(PosServerStub(demo()), PosServerStub.declared()) { app ->
            val pos = app as PosInstance
            tap("pos:new")
            tap("pos:add")
            tap("pos:group-Пицца")
            val pizza = pos.state.value.catalog?.catalog?.items?.first()!!
            tap("pos:product-${pizza.id}:add")
            shot("pos-30-sheet-options")

            // Тесто подороже, соус другой и две порции сыра — первая бесплатно.
            tap("pos:option-o-thick")
            tap("pos:option-o-hot")
            plus("pos:option-o-cheese:amount")
            plus("pos:option-o-cheese:amount")
            shot("pos-31-sheet-options-chosen")
            tap("pos:options-save")
            tap("pos:to-receipt")
            shot("pos-32-receipt-options")

            // Обязательную группу оставили пустой: сервер отказывает, шторка остаётся.
            val line = pos.state.value.order?.lines?.first()!!
            tapBelow("pos:line-${line.lineId}:options")
            tap("pos:option-o-hot")
            tap("pos:options-save")
            settle(200)
            shot("pos-33-refused-options")
        }
    }

    /** A server whose app has no points at all. */
    private class NoPlacesServer(private val inner: PosServerStub) : RestoServer by inner {
        override val places = kotlinx.coroutines.flow.MutableStateFlow(emptyList<Place>())
        override val place = kotlinx.coroutines.flow.MutableStateFlow<Place?>(null)
    }

    /** Every call refused, as a server under maintenance would. */
    private class FailingServer(private val inner: PosServerStub) : RestoServer by inner {
        override suspend fun call(method: String, params: JsonObject): JsonElement =
            throw MobileApiException("unavailable", "Сервер сейчас недоступен, попробуйте ещё раз", retryable = true)
    }

    /** Answers `pos.order` with a prepared receipt: what the screen does with a basket. */
    private class ScriptedServer(private val inner: PosServerStub, private val order: String) : RestoServer by inner {
        override suspend fun call(method: String, params: JsonObject): JsonElement =
            if (method == "pos.order" || method == "pos.start") {
                ProtocolJson.parseToJsonElement("""{"order":$order}""")
            } else {
                inner.call(method, params)
            }
    }

    private companion object {
        /** The same order, but the address belongs to another kitchen's zone (Q27). */
        const val OTHER_KITCHEN = """
        {
          "id": "d2", "number": "D1043", "state": "CART", "serviceType": "delivery",
          "placeId": "p1", "cookingPlaceId": "p2", "cookingPlaceTitle": "Мира, 3", "table": null,
          "customerName": "Игорь Петров", "customerPhone": "+7 913 555-00-33", "clientId": "u2",
          "address": "пр. Мира, 7, кв. 18", "comment": null, "personsCount": null,
          "date": null, "maxWaitMinutes": null, "paymentMethodId": null, "paymentMethodTitle": null,
          "promotionCode": null,
          "lines": [
            {"lineId": 1, "dishId": "d2", "name": "Том ям", "amount": 1, "price": 420, "total": 420, "oldPrice": null, "comment": null, "hasOptions": false}
          ],
          "dishesCount": 1, "basketTotal": 420, "discountTotal": 0, "deliveryCost": 250,
          "bonusesTotal": 0, "total": 670, "message": "Этот адрес готовит «Мира, 3»",
          "createdAt": 1700000000000, "updatedAt": 1700000000000
        }
        """
    }
}
