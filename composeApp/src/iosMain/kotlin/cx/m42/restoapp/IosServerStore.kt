package cx.m42.restoapp

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionComplete
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * The servers on an iPhone, laid out as on the desktop: the list in `servers.json` and each server's
 * files in `servers/<id>/`, under Application Support.
 *
 * Two things are weaker than the plan wants (`order-entry.plan.md`, «Платформы»):
 * - the device token is a file (encrypted while the phone is locked, kept out of backups), not the Keychain;
 * - [useStorageOf] does nothing, because Superizer's `PrefsStorage` on iOS is one defaults suite with no
 *   per-server part. Home, the language and the apps' data are shared by all servers until it has one.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public class IosServerStore : ServerStore {
    private val files = NSFileManager.defaultManager

    private val base: String =
        (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String) + "/restoapp"

    private val list: String get() = "$base/servers.json"

    private fun dirOf(serverId: String): String = "$base/servers/$serverId"

    override fun load(): String? = readText(list)

    override fun save(text: String) = writeWhole(base, list, text, private = false)

    override fun useStorageOf(serverId: String) = Unit

    override fun erase(serverId: String) {
        files.removeItemAtPath(dirOf(serverId), error = null)
    }

    override fun read(serverId: String, name: String): String? = readText("${dirOf(serverId)}/$name")

    override fun write(serverId: String, name: String, text: String, private: Boolean) =
        writeWhole(dirOf(serverId), "${dirOf(serverId)}/$name", text, private)

    override fun delete(serverId: String, name: String) {
        files.removeItemAtPath("${dirOf(serverId)}/$name", error = null)
    }

    private fun readText(path: String): String? =
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)

    /** Atomically: a crash leaves the old file or the new one, never half. */
    private fun writeWhole(dir: String, path: String, text: String, private: Boolean) {
        files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val data = NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val options = if (private) NSDataWritingAtomic or NSDataWritingFileProtectionComplete else NSDataWritingAtomic
        if (data.writeToFile(path, options = options, error = null) && private) {
            NSURL.fileURLWithPath(path).setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
        }
    }
}
