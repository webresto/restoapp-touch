package cx.m42.restoapp

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.v2.runComposeUiTest
import cx.m42.superizer.Superizer
import cx.m42.superizer.host.storage.PrefsStorage
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The empty host (D3) starts and draws the shell: Home with nothing but "+", and the drawer. */
@OptIn(ExperimentalTestApi::class)
class RestoAppTest {

    /** A fresh directory each time, as in Unitool's tests: never the developer's own settings. */
    @BeforeTest
    fun isolateStorage() {
        PrefsStorage.useDirectory(Files.createTempDirectory("restoapp-test").toFile())
    }

    private fun restoApp(): Superizer =
        buildRestoApp(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), debug = true)
            .also { it.settings.chooseLanguage("ru") }

    @Test
    fun aFreshInstallHasAnEmptyHome() = runComposeUiTest {
        setContent { RestoApp(restoApp()) }
        waitForIdle()

        onNodeWithTag("home:add").assertIsDisplayed()
    }

    @Test
    fun swipingInFromTheLeftEdgeOpensTheDrawer() = runComposeUiTest {
        setContent { RestoApp(restoApp()) }
        waitForIdle()

        onRoot().performTouchInput { swipeRight(startX = 1f, endX = centerX) }
        waitForIdle()

        onNodeWithText("Меню").assertIsDisplayed()
    }

    @Test
    fun clickingTheBurgerOpensTheDrawer() = runComposeUiTest {
        setContent { RestoApp(restoApp()) }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performClick()
        waitForIdle()

        onNodeWithText("Меню").assertIsDisplayed()
    }

    /** With a mouse, which is what a desktop window delivers, rather than a finger. */
    @Test
    fun clickingTheBurgerWithAMouseOpensTheDrawer() = runComposeUiTest {
        setContent { RestoApp(restoApp()) }
        waitForIdle()

        onNodeWithContentDescription("Открыть меню").performMouseInput { click() }
        waitForIdle()

        onNodeWithText("Меню").assertIsDisplayed()
    }

    @Test
    fun clickingPlusOpensAllApps() = runComposeUiTest {
        setContent { RestoApp(restoApp()) }
        waitForIdle()

        onNodeWithTag("home:add").performClick()
        waitForIdle()

        onNodeWithTag("home:add").assertDoesNotExist()
    }
}
