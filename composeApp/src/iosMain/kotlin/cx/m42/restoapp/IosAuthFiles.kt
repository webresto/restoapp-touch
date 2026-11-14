package cx.m42.restoapp

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * The file of sign-ins on the iPhone: the system's document picker, so the person puts the file in Files, iCloud
 * Drive or another app, and picks one from there. Both ways go through a copy (`asCopy`): the app never holds a
 * link to a file outside its sandbox, nor asks for access to one.
 *
 * Saving writes the file into the app's temporary directory first — the picker exports a file, not bytes — and
 * deletes it once the person has answered.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosAuthFiles : AuthFiles {
    /** The delegate of the picker on screen: UIKit holds it weakly, so it is held here until the answer. */
    private var delegate: Delegate? = null

    override suspend fun save(suggestedName: String, bytes: ByteArray): Boolean {
        val url = NSURL.fileURLWithPath(NSTemporaryDirectory() + suggestedName)
        val written = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()).writeToURL(url, atomically = true)
        }
        check(written) { "Cannot write the file" }
        try {
            return ask { UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true) } != null
        } finally {
            NSFileManager.defaultManager.removeItemAtURL(url, error = null)
        }
    }

    override suspend fun open(): ByteArray? {
        // Any file: other apps do not know this extension's type, and the content decides.
        val url = ask { UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true) } ?: return null
        val data = NSData.dataWithContentsOfURL(url) ?: error("Cannot read the file")
        val size = data.length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        // The copy the picker made for this app is not needed any more.
        NSFileManager.defaultManager.removeItemAtURL(url, error = null)
        return bytes
    }

    /** Shows the picker over whatever is on screen and waits for the person: the URL picked, or null when closed. */
    private suspend fun ask(make: () -> UIDocumentPickerViewController): NSURL? = withContext(Dispatchers.Main) {
        val answer = CompletableDeferred<NSURL?>()
        val picker = make()
        picker.allowsMultipleSelection = false
        val listener = Delegate { url ->
            delegate = null
            answer.complete(url)
        }
        delegate = listener
        picker.delegate = listener
        val over = topController() ?: run {
            delegate = null
            error("Nothing on screen to show the file picker over")
        }
        over.presentViewController(picker, animated = true, completion = null)
        answer.await()
    }

    private class Delegate(private val done: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            done(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            done(null)
        }
    }

    /** The controller on top: the key window's root, and whatever it presents. */
    private fun topController(): UIViewController? {
        val windows = UIApplication.sharedApplication.connectedScenes
            .mapNotNull { it as? UIWindowScene }
            .flatMap { scene -> scene.windows.mapNotNull { it as? UIWindow } }
        var top = (windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull())?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}
