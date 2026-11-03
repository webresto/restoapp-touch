package cx.m42.restoapp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.v2.runComposeUiTest
import java.io.File
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * The host (D6): a fresh install has two demo servers, the drawer starts with the server, and the
 * sheet switches between them — each with its own menu, Home and storage.
 */
@OptIn(ExperimentalTestApi::class)
class RestoAppTest {

    /** A fresh directory each time, as in Unitool's tests: never the developer's own servers. */
    private lateinit var dir: File

    @BeforeTest
    fun isolateStorage() {
        dir = Files.createTempDirectory("restoapp-test").toFile()
    }

    private fun restoApp(): RestoAppHost =
        RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(dir),
            debug = true,
            checkDelayMs = 0,
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

    @Test
    fun aFreshInstallOpensTheDemoRestaurantWithBothApps() = runComposeUiTest {
        val host = restoApp()
        setContent { RestoApp(host) }
        waitForIdle()

        onNodeWithText("Демо-ресторан").assertIsDisplayed()
        onNodeWithTag("home:tile-orders").assertIsDisplayed()
        onNodeWithTag("home:tile-stock").assertIsDisplayed()
        assertEquals(listOf("demo-restaurant", "demo-point"), host.contexts.value.map { it.id })
    }

    @Test
    fun swipingInFromTheLeftEdgeShowsTheServerAtTheTopOfTheDrawer() = runComposeUiTest {
        setContent { RestoApp(restoApp()) }
        waitForIdle()

        onRoot().performTouchInput { swipeRight(startX = 1f, endX = centerX) }
        waitForIdle()

        onNodeWithTag("context:row").assertIsDisplayed()
    }

    /** With a mouse, which is what a desktop window delivers, rather than a finger. */
    @Test
    fun clickingTheBurgerWithAMouseOpensTheDrawer() = runComposeUiTest {
        setContent { RestoApp(restoApp()) }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performMouseInput { click() }
        waitForIdle()

        onNodeWithTag("context:row").assertIsDisplayed()
    }

    @Test
    fun pickingAnotherServerSwitchesTheMenuAndHome() = runComposeUiTest {
        val host = restoApp()
        setContent { RestoApp(host) }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithTag("context:row").performClick()
        waitForIdle()
        onNodeWithTag("context:item-demo-point").performClick()
        waitForIdle()

        assertEquals("demo-point", host.current.value?.id)
        onNodeWithText("Демо-точка").assertIsDisplayed()
        onNodeWithTag("home:tile-stock").assertIsDisplayed()
        onNodeWithTag("home:tile-orders").assertDoesNotExist()
        // The language follows the person, not the server.
        assertEquals("ru", host.active.value.superizer.settings.langTag.value)
    }

    @Test
    fun eachServerKeepsItsOwnStorageAndTheLastOneIsWhereTheNextStartBegins() {
        val host = restoApp()
        runBlocking { host.switchNow("demo-point") }

        assertTrue(File(dir, "servers/demo-restaurant/settings.properties").isFile)
        assertTrue(File(dir, "servers/demo-point/settings.properties").isFile)

        val again = restoApp()
        assertEquals("demo-point", again.current.value?.id)
        assertEquals("demo-restaurant", again.previous()?.id)
    }

    @Test
    fun aDemoServerFromTheSheetIsAddedAndOpened() = runComposeUiTest {
        val host = restoApp()
        setContent { RestoApp(host) }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithTag("context:row").performClick()
        waitForIdle()
        onNodeWithTag("context:add").performClick()
        waitForIdle()
        onNodeWithTag("servers:add-demo").performClick()
        waitForIdle()

        assertEquals("demo-2", host.current.value?.id)
        assertEquals(3, host.contexts.value.size)
        onNodeWithText("Демо-сервер 2").assertIsDisplayed()
        onNodeWithTag("home:tile-orders").assertIsDisplayed()
    }

    @Test
    fun aServerOfYourOwnIsCheckedAddedAndOpenedWithTheAppsTicked() = runComposeUiTest {
        val host = restoApp()
        setContent { RestoApp(host) }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithTag("context:row").performClick()
        waitForIdle()
        onNodeWithTag("context:add").performClick()
        waitForIdle()
        onNodeWithTag("servers:add-custom").performClick()
        waitForIdle()
        onNodeWithTag("servers:address").performTextInput("https://admin.cafe.ru")
        onNodeWithTag("servers:app-stock").performClick()
        waitForIdle()
        onNodeWithTag("servers:connect").performClick()
        waitForIdle()

        assertEquals("admin-cafe-ru", host.current.value?.id)
        onNodeWithText("admin.cafe.ru").assertIsDisplayed()
        onNodeWithTag("home:tile-orders").assertIsDisplayed()
        onNodeWithTag("home:tile-stock").assertDoesNotExist()
    }

    @Test
    fun theCurrentServerCannotBeRemovedAndAnotherOneCan() {
        val host = restoApp()
        host.remove("demo-restaurant")
        assertEquals(2, host.servers.value.size)

        host.remove("demo-point")
        assertEquals(listOf("demo-restaurant"), host.servers.value.map { it.id })
    }
}
