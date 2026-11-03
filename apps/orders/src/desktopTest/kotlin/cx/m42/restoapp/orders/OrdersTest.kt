@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.orders

import androidx.compose.ui.test.performScrollTo
import cx.m42.superizer.testing.AppContractTest
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Everything the host assumes about this app, checked in one line. */
class OrdersContractTest : AppContractTest(OrdersApp()) {
    override val configFixtures: List<String> = listOf("""{"schemaVersion":1}""", """{}""")
    override val stateFixtures: List<String> = listOf("""{"tab":"Done","open":"o1001"}""", """{"tab":"Active"}""", """{}""")
}

class OrdersInstanceTest {

    private val AppTestScope<*>.orders get() = instance as OrdersInstance

    private fun AppTestScope<*>.tap(tag: String) {
        node(tag).performScrollTo()
        click(tag)
    }

    @Test
    fun theFirstLaunchFillsTheDemoEveningAndShowsWhatIsStillGoing() = runAppTest(OrdersApp()) {
        assertShown("orders:list")
        assertEquals(12, orders.orders.value.size)
        assertTrue(runtime.storage.entries.keys.any { it.contains("orders.v1") })

        val active = orders.orders.value.filter { it.state.active }
        active.forEach { node("orders:row-${it.id}").assertExists() }
        orders.orders.value.filterNot { it.state.active }.forEach { assertAbsent("orders:row-${it.id}") }
    }

    @Test
    fun anOrderMovesOnAndTheMoveIsStored() = runAppTest(OrdersApp()) {
        val new = orders.orders.value.first { it.state == OrderState.ORDER }
        tap("orders:row-${new.id}")
        assertShown("orders:order")
        assertEquals("Заказ №${new.number}", orders.chrome.value.title)

        tap("orders:to-COOKING")
        assertEquals(OrderState.COOKING, orders.orders.value.first { it.id == new.id }.state)
        // Cooking cannot go back to "ordered", and the button for where it is now is gone.
        assertAbsent("orders:to-COOKING")
        assertShown("orders:to-DONE")

        val stored = runtime.storage.entries.values.single()
        assertTrue(""""id":"${new.id}"""" in stored && "COOKING" in stored)
    }

    @Test
    fun aRejectedOrderLeavesTheActiveTabForTheCompletedOne() = runAppTest(OrdersApp()) {
        val new = orders.orders.value.first { it.state == OrderState.ORDER }
        tap("orders:row-${new.id}")
        tap("orders:to-REJECT")
        tap("orders:back")

        assertAbsent("orders:row-${new.id}")
        click("orders:tab-done")
        assertShown("orders:row-${new.id}")
    }

    @Test
    fun aPickupIsNeverOnTheWay() {
        val pickup = Order(
            id = "p", number = 1, state = OrderState.ORDER, lines = emptyList(), deliveryCost = 0,
            customerName = "", customerPhone = "", selfService = true, paid = true, createdAt = 0,
        )
        assertEquals(listOf(OrderState.COOKING, OrderState.DONE, OrderState.REJECT), pickup.next())
        assertEquals(emptyList(), pickup.copy(state = OrderState.DONE).next())
    }

    @Test
    fun money() {
        assertEquals("1 330 ₽", rub(1330))
        assertEquals("90 ₽", rub(90))
    }

    @Test
    fun russianCountsAgree() {
        assertEquals("1 позиция", OrdersStringsRu.dishes(1))
        assertEquals("3 позиции", OrdersStringsRu.dishes(3))
        assertEquals("5 позиций", OrdersStringsRu.dishes(5))
        assertEquals("12 позиций", OrdersStringsRu.dishes(12))
        assertEquals("21 позиция", OrdersStringsRu.dishes(21))
    }
}
