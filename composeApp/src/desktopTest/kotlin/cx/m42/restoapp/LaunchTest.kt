package cx.m42.restoapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import cx.m42.superizer.app.AppId
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * The start (02-host.md, В2/А2): the last server, then the app that was open on it — however long
 * ago — and Home when the person had closed it themselves. A "restart" is a new [RestoAppHost] on the
 * same storage after the old one let go of its server the way the platform would.
 */
@OptIn(ExperimentalTestApi::class)
class LaunchTest {

    private val dir: File = Files.createTempDirectory("restoapp-launch").toFile()

    private fun restoApp(now: Long): RestoAppHost =
        RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            store = DesktopServerStore(dir),
            debug = true,
            clock = { now },
        ).also { it.active.value.superizer.settings.chooseLanguage("ru") }

    private fun ComposeUiTest.appears(tag: String) =
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag(tag).assertExists() }.isSuccess }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    /** The old host lets go (its open app writes a snapshot), the window draws a new one. */
    private fun ComposeUiTest.restart(old: RestoAppHost, after: Long, show: (RestoAppHost) -> Unit): RestoAppHost {
        runBlocking { old.active.value.leave() }
        return restoApp(DAY0 + after).also { show(it); waitForIdle() }
    }

    @Test
    fun theAppLeftOpenIsWhatTheNextMorningStartsWith() = runComposeUiTest {
        var host by mutableStateOf(restoApp(DAY0))
        setContent { Box(Modifier.size(420.dp, 820.dp)) { RestoApp(host) } }
        appears("home:summary")
        openApp("orders")
        appears("orders:list")

        host = restart(host, after = 2 * DAY) { host = it }
        appears("orders:list")
        assertEquals("orders", host.active.value.superizer.handler.current.value?.app?.id?.value)
    }

    @Test
    fun theAppClosedBackToHomeDoesNotComeBack() = runComposeUiTest {
        var host by mutableStateOf(restoApp(DAY0))
        setContent { Box(Modifier.size(420.dp, 820.dp)) { RestoApp(host) } }
        appears("home:summary")
        openApp("orders")
        appears("orders:list")
        // The first screen of an app is a destination of the bottom bar: no arrow, «Главная» of the bar.
        openHome()
        appears("home:summary")

        host = restart(host, after = 2 * DAY) { host = it }
        appears("home:summary")
        assertEquals(null, host.active.value.superizer.handler.current.value)
    }

    @Test
    fun theServerComesBackFirstAndThenItsApp() = runComposeUiTest {
        var host by mutableStateOf(restoApp(DAY0))
        setContent { Box(Modifier.size(420.dp, 820.dp)) { RestoApp(host) } }
        appears("home:summary")
        val point = "demo-point"
        host.switchTo(point)
        waitUntil(timeoutMillis = 5_000L) {
            host.active.value.profile.id == point && host.active.value.superizer.registry.get(AppId("dishes")) != null
        }
        appears("home:summary")
        openApp("dishes")
        appears("dishes:list")

        host = restart(host, after = 2 * DAY) { host = it }
        assertEquals(point, host.active.value.profile.id)
        appears("dishes:list")
        assertEquals("dishes", host.active.value.superizer.handler.current.value?.app?.id?.value)
    }

    private companion object {
        const val DAY0 = 1_700_000_000_000
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
