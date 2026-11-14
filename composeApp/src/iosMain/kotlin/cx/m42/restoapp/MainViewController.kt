package cx.m42.restoapp

import androidx.compose.ui.window.ComposeUIViewController
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.restoapp.protocol.KotlinPuzzleSolver
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController

/**
 * One host per process, not per controller: SwiftUI may make a second controller on a scene reconnect,
 * and it draws the same host.
 *
 * The login CAPTCHA of a real server (Resto Cloud, a RestoApp) is solved by `KotlinPuzzleSolver`, the
 * plain-Kotlin one: `CryptoPuzzleSolver` is JVM code. Without a solver every such sign-in failed with
 * «Не получилось решить задачу входа».
 */
@OptIn(ExperimentalNativeApi::class)
private val host: RestoAppHost by lazy {
    RestoAppHost(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        store = IosServerStore(),
        debug = Platform.isDebugBinary,
        device = DeviceInfo(UIDevice.currentDevice.name, "ios"),
        // «Перенос входов»: the sign-ins of another device, from a file.
        files = IosAuthFiles(),
        solver = KotlinPuzzleSolver,
    )
}

/** The whole app as one UIKit controller, which `iosApp/ContentView.swift` wraps for SwiftUI. */
fun MainViewController(): UIViewController = ComposeUIViewController { RestoApp(host) }
