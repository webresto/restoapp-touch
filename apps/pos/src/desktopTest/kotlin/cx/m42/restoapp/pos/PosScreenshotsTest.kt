@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package cx.m42.restoapp.pos

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
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
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "$name.png"))
    }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performClick()
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
        val noPlaces = PosServerStub(demo())
        screen(NoPlacesServer(noPlaces), PosServerStub.declared().copy(places = emptyList())) { shot("pos-01-no-places") }

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

        // 4. Чек нового заказа: сервер сам выбрал «в зале» — у точки есть зал.
        screen(PosServerStub(demo()), PosServerStub.declared()) {
            tap("pos:new")
            shot("pos-04-receipt-new")
            // 5. «Бросить черновик» сначала спрашивает.
            tap("pos:drop")
            shot("pos-05-drop-confirm")
        }

        // 6. Точка самовывоза: тот же чек, другой способ получения.
        screen(PosServerStub(demo()), PosServerStub.declared()) {
            (this@PosScreenshotsTest.javaClass) // keep the block honest about its scope
            onNodeWithTag("pos:place").performClick()
            waitForIdle()
            mainClock.advanceTimeBy(600)
            shot("pos-06-points")
            tap("pos:places:item-p2")
            tap("pos:new")
            shot("pos-07-receipt-pickup")
        }

        // 8. Одна точка у сервера — строки переключателя нет вовсе.
        val single = demo(DemoBackend.ONE_POINT)
        screen(PosServerStub(single), PosServerStub.declared(single)) {
            tap("pos:new")
            shot("pos-08-one-point")
        }

        // 9. Сервер ответил отказом: красная строка и «Повторить» вместо выдуманного экрана.
        screen(FailingServer(PosServerStub(demo())), PosServerStub.declared()) { shot("pos-09-error") }

        // 10. Слово хоста — права изменились, точку сняли: жёлтая строка над списком.
        screen(PosServerStub(demo()), PosServerStub.declared()) { instance ->
            val server = (instance as PosInstance).server as PosServerStub
            runBlocking { server.notices.emit("Права изменились") }
            shot("pos-10-notice")
        }

        // 11–12. Ответы сервера, которых слой B ещё не умеет присылать: чек с составом, скидкой и
        // доставкой, и заказ, который готовит другая кухня (решение по Q27 — показываем, кто).
        screen(ScriptedServer(PosServerStub(demo()), FILLED), PosServerStub.declared()) { shot("pos-11-receipt-filled-stub") }
        screen(ScriptedServer(PosServerStub(demo()), OTHER_KITCHEN), PosServerStub.declared()) { shot("pos-12-other-kitchen-stub") }

        // 13. Сборка клиента без нативной кассы: приложение не прячется и не падает.
        screen(PosServerStub(demo()), PosServerStub.declared().copy(client = cx.m42.restoapp.protocol.ClientRender("kitchen", 1))) {
            shot("pos-13-needs-newer-client")
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
        /** A receipt as the hook will answer it in slice B: three lines, a promotion, a delivery. */
        const val FILLED = """
        {
          "id": "d1", "number": "D1042", "state": "CHECKOUT", "serviceType": "delivery",
          "placeId": "p1", "cookingPlaceId": "p1", "cookingPlaceTitle": "Ленина, 5", "table": null,
          "customerName": "Анна Смирнова", "customerPhone": "+7 912 000-11-22", "clientId": "u1",
          "address": "ул. Садовая, 3, кв. 15", "comment": "Домофон не работает", "personsCount": null,
          "date": null, "maxWaitMinutes": null, "paymentMethodId": "cash", "paymentMethodTitle": "Наличные",
          "promotionCode": "LETO20",
          "lines": [
            {"lineId": 1, "dishId": "d1", "name": "Пепперони 30 см", "amount": 2, "price": 472, "total": 944, "oldPrice": 1180, "comment": null, "hasOptions": true},
            {"lineId": 2, "dishId": "d2", "name": "Цезарь с курицей", "amount": 1, "price": 380, "total": 380, "oldPrice": null, "comment": "без лука", "hasOptions": false},
            {"lineId": 3, "dishId": "d3", "name": "Морс клюквенный 0,5 л", "amount": 3, "price": 150, "total": 450, "oldPrice": null, "comment": null, "hasOptions": false}
          ],
          "dishesCount": 6, "basketTotal": 1774, "discountTotal": 236, "deliveryCost": 200,
          "bonusesTotal": 150, "total": 1824, "message": "Доставка 200 ₽,≈ 45 минут",
          "createdAt": 1700000000000, "updatedAt": 1700000000000
        }
        """

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
