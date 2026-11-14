package cx.m42.restoapp

import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import cx.m42.restoapp.protocol.CryptoPuzzleSolver
import cx.m42.restoapp.protocol.DeviceInfo
import cx.m42.superizer.host.platform.AndroidHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * RestoApp on Android: one activity drawing the host.
 *
 * A `FragmentActivity`, as in Unitool: Superizer's lock is the system's `BiometricPrompt`, which can
 * only be shown from one. No `initPrefs` here, unlike Unitool: the store points `PrefsStorage` at the
 * server in force ([AndroidServerStore.useStorageOf]), and a second activity calling it would point
 * it back at the shared file under a running server.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, where the window is first laid out. targetSdk 35+ draws behind the
        // bars anyway; this picks their glyphs dark over Superizer's white, whose scaffold pads for
        // the safe area itself.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, DarkScrim),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, DarkScrim),
        )
        super.onCreate(savedInstanceState)

        // Vibration, the lifecycle, the network's state and links out — before the first composition.
        AndroidHost.init(this)
        val host = Holder.host(applicationContext)

        setContent { RestoApp(host) }
    }

    /**
     * One host per process, not per activity: a recreated activity draws the same one, rather than
     * building every server's Superizer and session again.
     */
    private object Holder {
        private var host: RestoAppHost? = null

        fun host(app: Context): RestoAppHost = host ?: RestoAppHost(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            store = AndroidServerStore(app),
            debug = BuildConfig.DEBUG,
            device = DeviceInfo(deviceName(app), "android"),
            // The JVM's solver: Android is a JVM, so a real server's CAPTCHA opens here as on the desktop.
            solver = CryptoPuzzleSolver,
        ).also { host = it }
    }

    private companion object {
        /** Behind the bars on API 26 and below, where a light navigation bar cannot draw dark glyphs. */
        const val DarkScrim = 0x801B1B1B.toInt()
    }
}

/**
 * What the server's list of devices calls this phone: the name its owner gave it in Settings
 * (API 25+), else the model.
 */
private fun deviceName(context: Context): String {
    val given = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
    } else {
        null
    }
    return (given ?: Build.MODEL).orEmpty().trim().take(100).ifEmpty { "Android" }
}
