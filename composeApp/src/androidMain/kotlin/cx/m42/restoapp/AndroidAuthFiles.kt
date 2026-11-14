package cx.m42.restoapp

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The file of sign-ins on Android: the Storage Access Framework's «create» and «open», so the person
 * puts it where they like — Downloads, a drive, a messenger — and the app asks for no storage permission.
 *
 * One per activity: the launchers are registered in its `onCreate`, as the framework requires, and a
 * recreated activity hands the host a new one ([RestoAppHost.files]). A dialog left open across a
 * recreation answers as closed.
 */
internal class AndroidAuthFiles(private val activity: ComponentActivity) : AuthFiles {
    private var waiting: CompletableDeferred<Uri?>? = null

    private val creating: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.CreateDocument(AuthFile.MIME)) { waiting?.complete(it) }

    private val opening: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { waiting?.complete(it) }

    override suspend fun save(suggestedName: String, bytes: ByteArray): Boolean {
        val uri = ask { creating.launch(suggestedName) } ?: return false
        withContext(Dispatchers.IO) {
            // "wt": a file picked again is written over, not appended to.
            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("Cannot write the file")
        }
        return true
    }

    override suspend fun open(): ByteArray? {
        // Other apps rarely know the extension's type: every file, and the content decides.
        val uri = ask { opening.launch(arrayOf("*/*")) } ?: return null
        return withContext(Dispatchers.IO) {
            activity.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read the file")
        }
    }

    private suspend fun ask(launch: () -> Unit): Uri? {
        waiting?.complete(null)
        val answer = CompletableDeferred<Uri?>()
        waiting = answer
        withContext(Dispatchers.Main) { launch() }
        return answer.await().also { if (waiting === answer) waiting = null }
    }

    /** The activity is gone: whoever waits hears «closed». */
    fun release() {
        waiting?.complete(null)
        waiting = null
    }
}
