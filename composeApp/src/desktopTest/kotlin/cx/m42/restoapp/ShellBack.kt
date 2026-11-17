package cx.m42.restoapp

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick

/**
 * The way back as a person takes it in the window: the shell's arrow, which walks an app's screens and
 * then leaves it. The screens draw no «Назад» of their own inside the shell (`hostDrawsBack`).
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.shellBack() {
    onNodeWithContentDescription("Назад").performClick()
    waitForIdle()
}
