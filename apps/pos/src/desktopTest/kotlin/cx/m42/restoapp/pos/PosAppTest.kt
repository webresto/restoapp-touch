@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.pos

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import cx.m42.restoapp.demo.DemoBackend
import cx.m42.restoapp.protocol.DeclaredApp
import cx.m42.restoapp.render.RestoServer
import cx.m42.superizer.testing.AppContractTest
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.FakeAppRuntime
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val declared: DeclaredApp = PosServerStub.declared()

/** Everything the host assumes about an app, on the POS of the demo server. */
class PosAppContractTest : AppContractTest(PosApp(declared, "ru")) {
    override fun provideServices(runtime: FakeAppRuntime) {
        runtime.provide(RestoServer.Key, PosServerStub(PosServerStub.demo()))
    }

    override val configFixtures: List<String> = listOf("""{"schemaVersion":1}""", "{}")
    override val stateFixtures: List<String> = listOf("""{"place":"p2","order":"draft1"}""", """{"place":"p1"}""", "{}")
}

class PosAppTest {

    private val AppTestScope<*>.server get() = runtime.service(RestoServer.Key) as PosServerStub
    private val AppTestScope<*>.screen get() = instance as PosInstance

    /** Scrolls the receipt to a row, lets the frame settle, then taps it. */
    private fun AppTestScope<*>.tap(tag: String) {
        runCatching { node(tag).performScrollTo() }
        idle()
        click(tag)
    }

    private fun pos(
        backend: DemoBackend = PosServerStub.demo(),
        restore: JsonObject? = null,
        block: AppTestScope<PosConfig>.() -> Unit,
    ) {
        val server = PosServerStub(backend)
        runAppTest(
            app = PosApp(PosServerStub.declared(backend), "ru"),
            restore = restore,
            prepare = { runtime -> runtime.provide(RestoServer.Key, server) },
            block = block,
        )
    }

    @Test
    fun theDeclarationIsWhatTheServerSaysAndTheScreenIsOurs() {
        // The app is the server's — id, title, icon and points from the declaration — and only the
        // drawing is this module's.
        val app = PosApp(declared, "ru")
        assertEquals("pos", app.manifest.id.value)
        assertEquals("Касса", app.manifest.metadata.title.resolve("ru"))
        assertTrue(PosApp.canDraw(declared))
        // A native screen this build does not have, and a newer client's version of this one.
        assertTrue(!PosApp.canDraw(declared.copy(client = declared.client!!.copy(native = "kitchen"))))
        assertTrue(!PosApp.canDraw(declared.copy(client = declared.client!!.copy(min = PosApp.VERSION + 1))))
        assertTrue(!PosApp.canDraw(declared.copy(client = null)))
    }

    @Test
    fun anEmptyPointOffersOneButtonAndStartingAnOrderOpensIt() = pos {
        assertShown("pos:empty")
        assertAbsent("pos:receipt")
        tap("pos:new")
        // The receipt of the new draft, with the number and the totals the server counted.
        assertShown("pos:receipt")
        assertShown("pos:no-lines")
        node("pos:total").assertExists()
        val order = assertNotNull(screen.state.value.order)
        assertEquals("CART", order.state)
        assertEquals(0.0, order.total)
        // Never a call without a point: the POS works on one at a time.
        assertTrue(server.calls.all { it.second == "p1" }, "a call went out without a point: ${server.calls}")
    }

    @Test
    fun aDraftGoesBackToTheListAndOpensAgain() = pos {
        tap("pos:new")
        val id = assertNotNull(screen.state.value.order).id
        tap("pos:back")
        assertShown("pos:draft-$id")
        assertAbsent("pos:receipt")
        tap("pos:draft-$id")
        assertEquals(id, screen.state.value.order?.id)
    }

    @Test
    fun droppingADraftAsksAndThenRemovesIt() = pos {
        tap("pos:new")
        val id = assertNotNull(screen.state.value.order).id
        tap("pos:drop")
        // The first tap asks; the receipt is still there until the question is answered.
        assertShown("pos:drop:confirm")
        assertShown("pos:receipt")
        tap("pos:drop:yes")
        idle()
        assertNull(screen.state.value.order)
        assertShown("pos:empty")
        assertAbsent("pos:draft-$id")
    }

    @Test
    fun theDraftsOfOnePointAreNotShownAtAnother() = pos {
        tap("pos:new")
        val id = assertNotNull(screen.state.value.order).id
        tap("pos:back")
        assertShown("pos:draft-$id")

        screen.server.selectPlace("p2")
        idle()
        // Another point: nothing of the first one is on the screen, and the receipt is closed.
        assertNull(screen.state.value.order)
        assertAbsent("pos:draft-$id")
        assertShown("pos:empty")

        screen.server.selectPlace("p1")
        idle()
        assertShown("pos:draft-$id")
    }

    /** The whole of it: the catalog, the guest, the checks of the server, and the order placed. */
    @Test
    fun takesAnOrderFromTheCatalogToPlaced() = pos {
        tap("pos:new")
        assertShown("pos:receipt")
        assertShown("pos:no-lines")

        // The catalog: the groups of the menu, then a product of one, added by its «+».
        tap("pos:add")
        assertShown("pos:catalog")
        click("pos:group-Пицца")
        val product = assertNotNull(screen.state.value.catalog?.catalog?.items?.firstOrNull())
        // A pizza has something to choose, so the «+» opens the chooser; the server's own defaults
        // are ticked in it, and confirming them is the whole of the «+» for an operator in a hurry.
        click("pos:product-${product.id}:add")
        idle()
        assertShown("pos:sheet-options")
        click("pos:options-save")
        idle()
        assertEquals(1, screen.state.value.order?.lines?.size)
        // The catalog says how many of it the receipt already has, without leaving the catalog.
        assertShown("pos:product-${product.id}:in-order")
        click("pos:catalog-back")
        click("pos:catalog-back")
        assertShown("pos:receipt")

        // − and + in the receipt are the server's answer, not arithmetic on the device.
        val lineId = assertNotNull(screen.state.value.order?.lines?.firstOrNull()?.lineId)
        tap("pos:line-$lineId:amount:plus")
        tap("pos:line-$lineId:amount:plus")
        idle()
        assertEquals(3, screen.state.value.order?.lines?.firstOrNull()?.amount)
        assertEquals(product.price * 3, screen.state.value.order?.total)

        // Checking before the guest is named is refused in words, and the receipt stays.
        tap("pos:check")
        idle()
        assertEquals(false, screen.state.value.order?.canCheck)
        assertShown("pos:receipt")

        click("pos:step-guest")
        type("pos:guest-phone", "+7 999 123-45-67")
        type("pos:guest-name", "Сергей")
        click("pos:guest-save")
        idle()
        assertEquals("Сергей", screen.state.value.order?.customerName)

        click("pos:step-payment")
        click("pos:payment-cash")
        idle()
        assertEquals("Наличные", screen.state.value.order?.paymentMethodTitle)

        tap("pos:check")
        idle()
        assertEquals("CHECKOUT", screen.state.value.order?.state)
        assertEquals(true, screen.state.value.order?.canPlace)

        tap("pos:place-order")
        tap("pos:place-order:yes")
        idle()
        // Placed: the «accepted» screen, and the draft is gone from the point.
        assertShown("pos:placed")
        assertNull(screen.state.value.order)
        assertEquals(emptyList(), screen.state.value.drafts)
        tap("pos:placed-done")
        assertShown("pos:empty")
    }

    @Test
    fun composesADishFromItsGroupsAndChangesItOnTheLine() = pos {
        tap("pos:new")
        tap("pos:add")
        click("pos:group-Пицца")
        val pizza = assertNotNull(screen.state.value.catalog?.catalog?.items?.firstOrNull())
        click("pos:product-${pizza.id}:add")
        idle()

        // The chooser opens at the server's answer: the groups of the dish, the defaults ticked.
        val chooser = assertNotNull(screen.state.value.chooser)
        assertEquals(listOf("dough", "sauce", "extra"), chooser.options.groups.map { it.id })
        assertEquals(setOf("o-thin", "o-tomato"), chooser.chosen.keys)

        // One of a group: the thick dough replaces the thin one rather than joining it.
        click("pos:option-o-thick")
        idle()
        assertEquals(1, assertNotNull(screen.state.value.chooser).chosen["o-thick"])
        assertNull(assertNotNull(screen.state.value.chooser).chosen["o-thin"])

        // A group that takes several is counted, and the server's ceiling is kept.
        click("pos:option-o-cheese:amount:plus")
        click("pos:option-o-cheese:amount:plus")
        click("pos:option-o-cheese:amount:plus")
        click("pos:option-o-cheese:amount:plus")
        idle()
        assertEquals(3, assertNotNull(screen.state.value.chooser).chosen["o-cheese"])

        click("pos:options-save")
        idle()
        val line = assertNotNull(screen.state.value.order?.lines?.firstOrNull())
        assertEquals(listOf("Пышное", "Томатный", "Сыр"), line.options.map { it.name })
        assertEquals(3, line.options.last().amount)
        // The money is the server's: 60 for the dough, and two of the three cheeses charged.
        assertEquals(pizza.price + 60.0 + 160.0, line.price)

        // The same chooser from the receipt, and the sauce changed on the line that is already in it.
        click("pos:catalog-back")
        click("pos:catalog-back")
        assertShown("pos:receipt")
        tap("pos:line-${line.lineId}:options")
        idle()
        assertEquals(line.lineId, assertNotNull(screen.state.value.chooser).options.lineId)
        click("pos:option-o-hot")
        click("pos:options-save")
        idle()
        val changed = assertNotNull(screen.state.value.order?.lines?.firstOrNull())
        assertEquals(line.lineId, changed.lineId)
        assertEquals(listOf("Пышное", "Острый", "Сыр"), changed.options.map { it.name })
        assertEquals(pizza.price + 60.0 + 40.0 + 160.0, changed.price)
        assertEquals(1, screen.state.value.order?.lines?.size)
    }

    @Test
    fun aGroupThatNeedsAChoiceIsRefusedByTheServerAndTheChooserStaysOpen() = pos {
        tap("pos:new")
        tap("pos:add")
        click("pos:group-Пицца")
        val pizza = assertNotNull(screen.state.value.catalog?.catalog?.items?.firstOrNull())
        click("pos:product-${pizza.id}:add")
        idle()
        // The sauce is taken off — the chooser lets it go, and the server is the one that says no.
        click("pos:option-o-tomato")
        idle()
        assertEquals(null, assertNotNull(screen.state.value.chooser).chosen["o-tomato"])
        click("pos:options-save")
        idle()
        assertShown("pos:sheet-options")
        assertShown("pos:sheet-message")
        assertEquals("Выберите, что идёт в «Соус»", screen.state.value.message?.text)
        assertEquals(emptyList(), screen.state.value.order?.lines)

        click("pos:option-o-cream")
        click("pos:options-save")
        idle()
        assertAbsent("pos:sheet-options")
        assertEquals("Сливочный", screen.state.value.order?.lines?.first()?.options?.get(1)?.name)
    }

    @Test
    fun theServerDecidesWhatThePointCanDoAndWhatTheCodeIsWorth() = pos {
        tap("pos:new")
        // «Ленина, 5» of the demo has a dining room: the table is asked for, and kept.
        click("pos:step-service")
        click("pos:service-dine-in")
        idle()
        click("pos:step-service")
        type("pos:table-input", "12")
        click("pos:table-save")
        idle()
        assertEquals("12", screen.state.value.order?.table)

        // A promo code the server does not know is refused, and says so.
        click("pos:step-promocode")
        type("pos:promocode-input", "НЕТ")
        click("pos:promocode-save")
        idle()
        assertEquals(null, screen.state.value.order?.promotionCode)
        assertEquals("danger", screen.state.value.message?.tone)
        // A refused step leaves the sheet open: the operator fixes what they are looking at.
        assertShown("pos:sheet-promocode")

        // The one the server knows is taken, and the sheet closes behind it.
        node("pos:promocode-input").performTextClearance()
        type("pos:promocode-input", "DEMO10")
        click("pos:promocode-save")
        idle()
        assertEquals("DEMO10", screen.state.value.order?.promotionCode)
        assertAbsent("pos:sheet-promocode")
    }

    @Test
    fun aDeliveryAsksForAnAddressAndPricesItself() = pos {
        tap("pos:new")
        click("pos:step-service")
        click("pos:service-delivery")
        idle()
        assertEquals(PosOrder.DELIVERY, screen.state.value.order?.serviceType)
        // The address is a step of its own, and only a delivery has it.
        assertShown("pos:step-address")

        click("pos:step-address")
        type("pos:address-street", "ул. Садовая")
        type("pos:address-home", "3")
        click("pos:address-save")
        idle()
        assertEquals("ул. Садовая, 3", screen.state.value.order?.address)

        // Something in the basket, and the delivery the server prices on top of it.
        tap("pos:add")
        click("pos:group-Супы")
        val soup = assertNotNull(screen.state.value.catalog?.catalog?.items?.firstOrNull())
        click("pos:product-${soup.id}:add")
        click("pos:catalog-back")
        click("pos:catalog-back")
        idle()
        val order = assertNotNull(screen.state.value.order)
        assertEquals(soup.price + order.deliveryCost, order.total)
        assertTrue(order.deliveryCost > 0, "the demo charges for a small delivery")
    }

    @Test
    fun aSnapshotBringsBackThePointAndTheOpenReceipt() {
        val backend = PosServerStub.demo()
        // A draft made on «Мира, 3», as a terminal there would.
        val maker = PosServerStub(backend)
        maker.selectPlace("p2")
        val id = kotlinx.coroutines.runBlocking {
            cx.m42.restoapp.protocol.ProtocolJson.decodeFromJsonElement(
                PosOrderAnswer.serializer(),
                maker.call("pos.start", JsonObject(emptyMap())),
            ).order!!.id
        }
        // A server with no point chosen yet, as a freshly connected one: the snapshot's point is
        // what the app asks for.
        val server = PosServerStub(backend, selectFirst = false)
        runAppTest(
            app = PosApp(PosServerStub.declared(backend), "ru"),
            restore = buildJsonObject {
                put("place", JsonPrimitive("p2"))
                put("order", JsonPrimitive(id))
            },
            prepare = { runtime -> runtime.provide(RestoServer.Key, server) },
        ) {
            idle()
            assertEquals("p2", server.place.value?.id)
            assertEquals(id, (instance as PosInstance).state.value.order?.id)
            assertShown("pos:receipt")
        }
    }

    /** The shape the hook promises (`schema/pos.schema.json`) is the shape these classes read. */
    @Test
    fun theAnswerOfTheServerDecodesIntoTheReceipt() {
        val backend = PosServerStub.demo()
        val server = PosServerStub(backend)
        val answer = kotlinx.coroutines.runBlocking {
            cx.m42.restoapp.protocol.ProtocolJson.decodeFromJsonElement(
                PosOrderAnswer.serializer(),
                server.call("pos.start", JsonObject(emptyMap())),
            )
        }
        val order = assertNotNull(answer.order)
        assertEquals("CART", order.state)
        assertEquals("p1", order.placeId)
        assertEquals(PosOrder.DINE_IN, order.serviceType)
        assertTrue(order.lines.isEmpty())
        assertEquals(0.0, order.total)
        assertNull(order.table)
        // And the list, which is the same shape with the drafts in it.
        val drafts = kotlinx.coroutines.runBlocking {
            cx.m42.restoapp.protocol.ProtocolJson.decodeFromJsonElement(
                PosOrderAnswer.serializer(),
                server.call("pos.order", JsonObject(emptyMap())),
            )
        }
        assertNull(drafts.order)
        assertEquals(listOf(order.id), drafts.drafts.map { it.id })
    }

    @Test
    fun aDraftThatIsGoneClosesTheReceiptInsteadOfShowingAnError() = pos {
        tap("pos:new")
        val id = assertNotNull(screen.state.value.order).id
        // Dropped elsewhere — another terminal, the web — and then the screen comes back.
        kotlinx.coroutines.runBlocking {
            server.call("pos.cancel", buildJsonObject { put("id", JsonPrimitive(id)) })
        }
        screen.reload()
        idle()
        assertNull(screen.state.value.order)
        assertNull(screen.state.value.error)
        assertShown("pos:empty")
    }
}
