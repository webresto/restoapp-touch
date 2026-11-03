@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package cx.m42.restoapp.stock

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import cx.m42.superizer.testing.AppContractTest
import cx.m42.superizer.testing.AppTestScope
import cx.m42.superizer.testing.runAppTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Everything the host assumes about this app, checked in one line. */
class StockContractTest : AppContractTest(StockApp()) {
    override val configFixtures: List<String> = listOf("""{"schemaVersion":1}""", """{}""")
    override val stateFixtures: List<String> = listOf(
        """{"filter":"All","query":"пицца","open":"d1"}""",
        """{"filter":"Stop"}""",
        """{}""",
    )
}

class StockInstanceTest {

    private val AppTestScope<*>.stock get() = instance as StockInstance

    // The balance is part of the clickable row, so it lives only in the unmerged tree.
    private fun AppTestScope<*>.balance(id: String) = ui.onNodeWithTag("stock:balance-$id", useUnmergedTree = true)

    private fun AppTestScope<*>.tap(tag: String) {
        node(tag).performScrollTo()
        click(tag)
    }

    @Test
    fun theFirstLaunchShowsTheCountedDishes() = runAppTest(StockApp()) {
        val items = stock.items.value
        assertEquals(18, items.size)
        assertEquals(2, items.count { it.balance == 0 })
        assertEquals(7, items.count { it.limited })

        items.filter { it.limited }.forEach { node("stock:row-${it.id}").assertExists() }
        items.filter { it.unlimited }.forEach { assertAbsent("stock:row-${it.id}") }
    }

    @Test
    fun minusToZeroPutsADishOnStopAndPlusTakesItOff() = runAppTest(StockApp()) {
        val counted = stock.items.value.first { it.balance in 1..12 }
        repeat(counted.balance) { tap("stock:minus-${counted.id}") }

        balance(counted.id).assertTextEquals("Нет в наличии")
        assertTrue(stock.items.value.first { it.id == counted.id }.onStop)

        tap("stock:plus-${counted.id}")
        balance(counted.id).assertTextEquals("1 ${counted.unit}")
        assertTrue("\"balance\":1" in runtime.storage.entries.values.single())
    }

    @Test
    fun theEditorSetsABalanceAndMakesADishUnlimited() = runAppTest(StockApp()) {
        click("stock:filter-all")
        val dish = stock.items.value.first { it.unlimited }
        tap("stock:row-${dish.id}")
        assertShown("stock:item")
        assertEquals(dish.name, stock.chrome.value.title)

        type("stock:balance-field", "15")
        click("stock:set")
        assertEquals(15, stock.items.value.first { it.id == dish.id }.balance)

        click("stock:unlimited")
        assertTrue(stock.items.value.first { it.id == dish.id }.unlimited)

        click("stock:enabled")
        assertFalse(stock.items.value.first { it.id == dish.id }.enabled)
    }

    @Test
    fun searchFindsAcrossTheWholeFilter() = runAppTest(StockApp()) {
        click("stock:filter-all")
        type("stock:search", "борщ")
        assertShown("stock:row-d9")
        assertAbsent("stock:row-d1")

        type("stock:search", "нет такого")
        assertShown("stock:empty")
    }
}
