@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package cx.m42.restoapp

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.restoapp.protocol.KotlinPuzzleSolver
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** RestoApp in a browser tab: the same host as the desktop's, its files in `localStorage`. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val host = RestoAppHost(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        store = WebServerStore(),
        debug = true,
        device = DeviceInfo("Browser", "web"),
        // The plain-Kotlin solver, as on the iPhone: `CryptoPuzzleSolver` is JVM code.
        solver = KotlinPuzzleSolver,
        // `?cloud=http://192.168.1.11:17280` — a cloud panel other than dev.resto.cloud, as `--cloud=` of the desktop.
        cloudAddress = cloudFromQuery() ?: ServerProfile.CLOUD_ADDRESS,
    )
    ComposeViewport(document.body!!) { RestoApp(host) }
}

/** The `cloud` parameter of the page's address, if it is an http(s) address. */
private fun cloudFromQuery(): String? =
    kotlinx.browser.window.location.search.removePrefix("?").split('&')
        .map { it.substringBefore('=') to it.substringAfter('=', "") }
        .firstOrNull { it.first == "cloud" }?.second
        ?.let { decodeURIComponent(it) }
        ?.trim()?.trimEnd('/')
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

private external fun decodeURIComponent(value: String): String
