package cx.m42.restoapp

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

/*
 * The apps as a person reaches them under the navigation of the prototype (`_plan/07-prototype-1to1.md`): the
 * bottom bar on a phone, the rail on a tablet, the sidebar on a desktop — and «Ещё» for whatever did not fit.
 * There are no tiles on «Главная» any more; these replace `home:tile-<id>` in the walks.
 */

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.tagged(tag: String) = runCatching { onNodeWithTag(tag).assertExists() }.isSuccess

/** Opens the app: its item of the bar, the rail or the sidebar, or its row of «Ещё». */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.openApp(id: String) {
    waitForIdle()
    if (tagged("shell:nav-$id")) {
        onNodeWithTag("shell:nav-$id").apply { runCatching { performScrollTo() } }.performClick()
    } else {
        onNodeWithTag("shell:nav-more").performClick()
        waitUntil(timeoutMillis = 10_000L) { tagged("more:$id") }
        onNodeWithTag("more:$id").apply { runCatching { performScrollTo() } }.performClick()
    }
    waitForIdle()
}

/**
 * Whether the person has the app at all. What is not in the bar is looked for in «Ещё», and the walk is then
 * back on «Главная» — where the walks always asked it.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.hasApp(id: String): Boolean {
    waitForIdle()
    if (tagged("shell:nav-$id")) return true
    if (!tagged("shell:nav-more")) return false
    onNodeWithTag("shell:nav-more").performClick()
    waitForIdle()
    val found = tagged("more:$id")
    if (tagged("shell:nav-home")) onNodeWithTag("shell:nav-home").performClick()
    waitForIdle()
    return found
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertApp(id: String) {
    check(hasApp(id)) { "no app «$id» in the navigation" }
}

@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.assertNoApp(id: String) {
    check(!hasApp(id)) { "the app «$id» is in the navigation, and it should not be" }
}

/** «Главная» from anywhere. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.openHome() {
    waitForIdle()
    onNodeWithTag("shell:nav-home").performClick()
    waitForIdle()
}

/** The list of the servers and accounts of the device: the context line of the top bar or of the sidebar. */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.openContexts() {
    waitForIdle()
    val tag = listOf("context:row", "shell:rail-context").firstOrNull { tagged(it) } ?: "context:row"
    onNodeWithTag(tag).performClick()
    waitForIdle()
}
