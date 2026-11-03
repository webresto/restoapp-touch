@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.pos

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
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

    private fun AppTestScope<*>.tap(tag: String) {
        runCatching { node(tag).performScrollTo() }
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

    @Test
    fun theCatalogButtonIsThereAndSaysItIsNotReadyYet() = pos {
        tap("pos:new")
        node("pos:add").assertExists()
        node("pos:add-later").assertExists()
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
