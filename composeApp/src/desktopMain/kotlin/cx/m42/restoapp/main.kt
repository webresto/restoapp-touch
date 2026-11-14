package cx.m42.restoapp

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cx.m42.restoapp.protocol.CryptoPuzzleSolver
import cx.m42.restoapp.protocol.DeviceInfo
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * RestoApp as a desktop window. `--link=` and `--activate=` stand in for a QR code and a
 * notification tap, as in Unitool; they go to the server in force at start. `--cloud=<address>` (or
 * `RESTOAPP_CLOUD_URL`) points «Resto Cloud» at another cloud panel — one on this computer.
 */
fun main(args: Array<String>) {
    // Its own directory (D2), and inside it a list of servers and a directory per server (D6).
    val base = File(System.getProperty("user.home"), ".restoapp")
    application {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
        val host = remember {
            RestoAppHost(
                scope = scope,
                store = DesktopServerStore(base),
                debug = true,
                device = DeviceInfo(deviceName(), "desktop-" + System.getProperty("os.name").orEmpty().lowercase().substringBefore(' ')),
                solver = CryptoPuzzleSolver,
                cloudAddress = args.firstOrNull { it.startsWith("--cloud=") }?.substringAfter('=')
                    ?: System.getenv("RESTOAPP_CLOUD_URL")?.takeIf { it.isNotBlank() }
                    ?: ServerProfile.CLOUD_ADDRESS,
            )
        }
        val connection by host.active.collectAsState()

        remember {
            args.firstOrNull { it.startsWith("--link=") || it.startsWith("--activate=") }
                ?.substringAfter('=')
                ?.let { host.active.value.superizer.route.deliver(it) }
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(width = 420.dp, height = 820.dp),
            title = "RestoApp — ${connection.profile.name}",
            onPreviewKeyEvent = { event -> serverHotkey(event, host) },
        ) {
            RestoApp(host)
        }
    }
}

/** What the server's list of devices will call this computer: its host name, as the OS has it. */
private fun deviceName(): String =
    runCatching { InetAddress.getLocalHost().hostName.removeSuffix(".local") }.getOrNull()?.take(100)?.ifBlank { null } ?: "Desktop"

private val Digits = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

/** ⌘1…⌘9 (Ctrl on Windows and Linux): the n-th server, as Slack and browsers switch tabs. */
private fun serverHotkey(event: KeyEvent, host: RestoAppHost): Boolean {
    if (event.type != KeyEventType.KeyDown || !(event.isMetaPressed || event.isCtrlPressed)) return false
    val index = Digits.indexOf(event.key)
    if (index < 0) return false
    host.switchToIndex(index)
    return true
}
