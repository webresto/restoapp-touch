package cx.m42.restoapp

import androidx.compose.ui.window.ComposeUIViewController
import cx.m42.restoapp.protocol.DeviceInfo
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
 * No solver is passed, so a server that asks for the login CAPTCHA says this platform cannot solve it
 * yet: `CryptoPuzzleSolver` is JVM code (`order-entry.plan.md`, «Платформы»). The demo servers need none.
 */
@OptIn(ExperimentalNativeApi::class)
private val host: RestoAppHost by lazy {
    RestoAppHost(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        store = IosServerStore(),
        debug = Platform.isDebugBinary,
        device = DeviceInfo(UIDevice.currentDevice.name, "ios"),
        // «Перенос входов»: on the iPhone it is also the way in to a real server — the sign-in of another device,
        // since there is no CAPTCHA solver here yet.
        files = IosAuthFiles(),
    )
}

/** The whole app as one UIKit controller, which `iosApp/ContentView.swift` wraps for SwiftUI. */
fun MainViewController(): UIViewController = ComposeUIViewController { RestoApp(host) }
