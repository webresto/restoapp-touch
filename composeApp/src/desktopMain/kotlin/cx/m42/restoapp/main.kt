package cx.m42.restoapp

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cx.m42.superizer.host.storage.PrefsStorage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * RestoApp as a desktop window. `--link=` and `--activate=` stand in for a QR code and a
 * notification tap, as in Unitool.
 */
fun main(args: Array<String>) {
    // Settings of its own (D2): on the library's default directory it would share Unitool's
    // Home, language and session snapshot on the same machine.
    PrefsStorage.useDirectory(File(System.getProperty("user.home"), ".restoapp").apply { mkdirs() })
    restoAppWindow(args)
}

private fun restoAppWindow(args: Array<String>) = application {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    val superizer = remember { buildRestoApp(scope, debug = true) }

    remember {
        args.firstOrNull { it.startsWith("--link=") || it.startsWith("--activate=") }
            ?.substringAfter('=')
            ?.let { superizer.route.deliver(it) }
    }

    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(width = 420.dp, height = 820.dp),
        title = "RestoApp",
    ) {
        RestoApp(superizer)
    }
}
