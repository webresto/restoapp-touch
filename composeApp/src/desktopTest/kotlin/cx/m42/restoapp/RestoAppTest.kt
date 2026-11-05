package cx.m42.restoapp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
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
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.restoapp.demo.DemoRole
import cx.m42.superizer.app.AppId
import java.io.File
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * The host (D6, D8): a fresh install has two demo servers whose menus are what the demo server
 * declares; the drawer starts with the server, the sheet switches between them, and a role switch on
 * the demo changes the menu the way a group in the admin panel would.
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
            clock = { 1_700_000_000_000 },
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

    private fun RestoAppHost.registered(): Set<String> = active.value.superizer.registry.let { registry ->
        listOf("dishes", "orders").filter { registry.get(AppId(it)) != null }.toSet()
    }

    /** Every app of the current server, whoever draws it. */
    private fun RestoAppHost.allRegistered(): Set<String> = active.value.superizer.registry.let { registry ->
        listOf("dishes", "orders", "clients", "pos").filter { registry.get(AppId(it)) != null }.toSet()
    }

    @Test
    fun aFreshInstallOpensTheDemoRestaurantWithTheAppsItDeclares() = runComposeUiTest {
        val host = restoApp()
        setContent { RestoApp(host) }
        waitUntil(timeoutMillis = 5_000L) { host.registered() == setOf("dishes", "orders") }
        waitForIdle()

        onNodeWithText("Демо-ресторан").assertIsDisplayed()
        onNodeWithTag("home:tile-dishes").assertIsDisplayed()
        onNodeWithTag("home:tile-orders").assertIsDisplayed()
        onNodeWithTag("home:demo").assertIsDisplayed()
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
        waitUntil(timeoutMillis = 5_000L) { host.current.value?.id == "demo-point" && host.registered() == setOf("dishes") }
        waitForIdle()

        onNodeWithText("Демо-точка").assertIsDisplayed()
        onNodeWithTag("home:tile-dishes").assertIsDisplayed()
        onNodeWithTag("home:tile-orders").assertDoesNotExist()
        // The language follows the person, not the server.
        assertEquals("ru", host.active.value.superizer.settings.langTag.value)
    }

    @Test
    fun eachServerKeepsItsOwnStorageAndTheLastOneIsWhereTheNextStartBegins() {
        val host = restoApp()
        runBlocking { host.switchNow("demo-point") }

        assertTrue(File(dir, "servers/demo-restaurant/settings.properties").isFile)
        assertTrue(File(dir, "servers/demo-point/declaration.json").isFile)
        assertTrue(File(dir, "servers/demo-point/demo.json").isFile)

        val again = restoApp()
        assertEquals("demo-point", again.current.value?.id)
        assertEquals("demo-restaurant", again.previous()?.id)
        // The menu is there before any answer: it is the declaration of last time.
        assertNotNull(again.active.value.superizer.registry.get(AppId("dishes")))
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
        waitUntil(timeoutMillis = 5_000L) { host.current.value?.id == "demo-2" && host.registered() == setOf("dishes", "orders") }
        waitForIdle()

        assertEquals(3, host.contexts.value.size)
        onNodeWithText("Демо-сервер 2").assertIsDisplayed()
        onNodeWithTag("home:tile-orders").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.has(tag: String) =
        runCatching { onNodeWithTag(tag).assertExists() }.isSuccess

    /** A card of a new customer on top of the list: two screens of one app, and a form to fill. */
    private fun androidx.compose.ui.test.ComposeUiTest.openClients(host: RestoAppHost) {
        waitUntil(timeoutMillis = 5_000L) { "clients" in host.allRegistered() }
        waitForIdle()
        onNodeWithTag("home:tile-clients").performClick()
        waitUntil(timeoutMillis = 5_000L) { has("clients:list") }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.toCard() {
        onNodeWithTag("clients:add").performClick()
        waitForIdle()
        onNodeWithTag("clients:form:name").performTextInput("Сергей Крылов")
        onNodeWithTag("clients:form:phone").performTextInput("+7 999 123-45-67")
        onNodeWithTag("clients:form:submit").performClick()
        waitUntil(timeoutMillis = 5_000L) { has("clients:detail") }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.arrow() {
        onNodeWithContentDescription("Назад").performClick()
        waitForIdle()
    }

    /** The bug itself: the arrow of the top bar dropped to the menu from any depth. */
    @Test
    fun theArrowWalksBackThroughTheScreensAndOnlyThenToTheMenu() = runComposeUiTest {
        val host = restoApp()
        setContent { RestoApp(host) }
        openClients(host)
        toCard()
        arrow()
        waitUntil(timeoutMillis = 5_000L) { has("clients:list") }
        onNodeWithTag("clients:detail").assertDoesNotExist()
        arrow()
        waitUntil(timeoutMillis = 5_000L) { has("home:tile-clients") }
    }

    @Test
    fun theSwipeFromTheRightEdgeGoesBackOneScreen() = runComposeUiTest {
        val host = restoApp()
        setContent { RestoApp(host) }
        openClients(host)
        toCard()
        onRoot().performTouchInput { swipe(Offset(right - 2f, centerY), Offset(left + 2f, centerY), durationMillis = 300) }
        mainClock.advanceTimeBy(1_000)
        waitUntil(timeoutMillis = 5_000L) { has("clients:list") }
        onNodeWithTag("clients:detail").assertDoesNotExist()
    }

    /** Half a swipe, written out for the eye: `build/swipe-back.png`. */
    @Test
    fun halfASwipeShowsTheCardShrunkOverThePreviousScreen() = runComposeUiTest {
        val host = restoApp()
        setContent { RestoApp(host) }
        openClients(host)
        toCard()
        waitForIdle()
        onRoot().performTouchInput {
            down(Offset(right - 2f, centerY))
            moveBy(Offset(-width * 0.45f, 0f))
        }
        waitForIdle()
        onNodeWithTag("shell:back-preview").assertExists()
        val shot = File("build/swipe-back.png").apply { parentFile.mkdirs() }
        shot.writeBytes(
            org.jetbrains.skia.Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap())
                .encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes,
        )
    }

    @Test
    fun theArrowAsksAboutAFilledFormInsteadOfLosingIt() = runComposeUiTest {
        val host = restoApp()
        setContent { RestoApp(host) }
        openClients(host)
        onNodeWithTag("clients:add").performClick()
        waitForIdle()
        onNodeWithTag("clients:form:name").performTextInput("Сергей Крылов")
        waitForIdle()
        arrow()
        onNodeWithTag("unsaved:question").assertExists()
        onNodeWithTag("clients:list").assertExists()
        onNodeWithTag("unsaved:leave").performClick()
        waitForIdle()
        onNodeWithTag("clients:form").assertDoesNotExist()
        onNodeWithTag("clients:list").assertExists()
    }

    @Test
    fun theMenuAsksAboutAFilledFormToo() = runComposeUiTest {
        val host = restoApp()
        setContent { RestoApp(host) }
        openClients(host)
        onNodeWithTag("clients:add").performClick()
        waitForIdle()
        onNodeWithTag("clients:form:name").performTextInput("Сергей Крылов")
        waitForIdle()
        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()
        onNodeWithText("Главная").performClick()
        waitForIdle()
        onNodeWithTag("shell:close-dialog").assertExists()
        onNodeWithTag("shell:close-dialog:save").assertExists()
    }

    @Test
    fun aRoleOnTheDemoIsAnotherMenuAndTheOpenAppComesBack() = runComposeUiTest {
        val host = restoApp()
        setContent { RestoApp(host) }
        waitUntil(timeoutMillis = 5_000L) { host.registered() == setOf("dishes", "orders") }
        waitForIdle()

        onNodeWithTag("home:tile-dishes").performClick()
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("dishes:list").assertExists() }.isSuccess }
        val before = host.active.value

        host.setDemoRole(DemoRole.Stock)
        waitUntil(timeoutMillis = 5_000L) { host.active.value !== before && host.registered() == setOf("dishes") }
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("dishes:list").assertExists() }.isSuccess }

        host.setDemoRole(DemoRole.Operator)
        waitUntil(timeoutMillis = 5_000L) { host.registered() == setOf("orders") }
        waitForIdle()
        onNodeWithTag("dishes:list").assertDoesNotExist()
    }

    @Test
    fun aPointPickedInOneAppIsWhereTheOtherOpens() = runComposeUiTest {
        val host = restoApp()
        setContent { RestoApp(host) }
        waitUntil(timeoutMillis = 5_000L) { host.registered() == setOf("dishes", "orders") }
        waitForIdle()

        onNodeWithTag("home:tile-orders").performClick()
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("orders:place").assertExists() }.isSuccess }
        onNodeWithTag("orders:place").performClick()
        waitForIdle()
        onNodeWithTag("orders:places:item-p2").performClick()
        waitForIdle()
        assertEquals("p2", host.active.value.session.bind("orders").place.value?.id)

        assertEquals("p2", host.active.value.session.bind("dishes").place.value?.id)
        assertTrue(File(dir, "servers/demo-restaurant/place.json").readText().contains("p2"))
    }

    /**
     * The POS of the declaration is drawn by the native app and the rest by the renderer: the host
     * picks the class by `client: {native}`, and nothing else about the app changes (D10).
     */
    @Test
    fun theDeclaredPosIsDrawnByTheNativeApp() = runComposeUiTest {
        val host = restoApp()
        setContent { RestoApp(host) }
        waitUntil(timeoutMillis = 5_000L) { host.allRegistered() == setOf("dishes", "orders", "clients", "pos") }
        waitForIdle()

        assertTrue(host.active.value.superizer.registry.get(AppId("pos")) is cx.m42.restoapp.pos.PosApp)
        assertTrue(host.active.value.superizer.registry.get(AppId("clients")) is cx.m42.restoapp.render.ServerApp)

        onNodeWithTag("home:tile-pos").performClick()
        // The POS screen, not the renderer's «needs a newer version» tile.
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("pos").assertExists() }.isSuccess }
        onNodeWithTag("pos:needs-update").assertDoesNotExist()
        onNodeWithTag("pos:new").assertIsDisplayed()
    }

    @Test
    fun theCurrentServerCannotBeRemovedAndAnotherOneCan() {
        val host = restoApp()
        host.remove("demo-restaurant")
        assertEquals(2, host.servers.value.size)

        host.remove("demo-point")
        assertEquals(listOf("demo-restaurant"), host.servers.value.map { it.id })
        assertNull(DesktopServerStore(dir).read("demo-point", "demo.json"))
    }
}
