package cx.m42.restoapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import cx.m42.superizer.host.storage.PrefsStorage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The servers on Android, laid out as on the desktop (D6), in the app's own files: the list in
 * `files/restoapp/servers.json`, and each server's files in `files/restoapp/servers/<id>/` —
 * `session.json` with the device token, `declaration.json`, `place.json`, `demo.json`.
 *
 * A server's settings (Home, the snapshots, the language, every app's data) are Superizer's
 * `PrefsStorage`, which on Android is one `SharedPreferences` file with no per-server part. [useStorageOf]
 * gives it one without changing the library: `PrefsStorage.init` takes its preferences from the
 * context it is handed, and [ServerPrefs] is a context whose preferences, by whatever name, are the
 * server's own — `shared_prefs/restoapp.server.<id>.xml`. If a later Superizer stops reading them
 * through that context, the servers share the one file again, as on iOS; nothing breaks.
 *
 * Weaker than the plan wants (`order-entry.plan.md`, «Платформы»): the device token is a file only
 * this app can read, not the Keystore. None of it leaves the phone — no backup, no transfer to a new
 * one (`allowBackup`, `data_extraction_rules.xml`) — but by the person's own hand, in a file sealed
 * by a password («Перенос входов», `AuthFile.kt`).
 */
public class AndroidServerStore(context: Context) : ServerStore {
    private val app: Context = context.applicationContext

    private val base = File(app.filesDir, "restoapp")

    private val file: File get() = File(base, "servers.json")

    private fun dirOf(serverId: String): File = File(File(base, "servers"), serverId)

    private fun prefsOf(serverId: String): String = "restoapp.server.$serverId"

    override fun load(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    override fun save(text: String) {
        runCatching { writeWhole(file, text, private = false) }
    }

    override fun useStorageOf(serverId: String) {
        PrefsStorage.init(ServerPrefs(app, prefsOf(serverId)))
    }

    override fun erase(serverId: String) {
        runCatching { dirOf(serverId).deleteRecursively() }
        // Through the context, not by deleting the file: this also drops Android's cached copy, so a
        // server added again under the same id (the demo's `demo-2`) starts empty.
        runCatching { app.deleteSharedPreferences(prefsOf(serverId)) }
    }

    override fun read(serverId: String, name: String): String? =
        runCatching { File(dirOf(serverId), name).takeIf { it.isFile }?.readText() }.getOrNull()

    override fun write(serverId: String, name: String, text: String, private: Boolean) {
        runCatching { writeWhole(File(dirOf(serverId), name), text, private) }
    }

    override fun delete(serverId: String, name: String) {
        runCatching { File(dirOf(serverId), name).delete() }
    }

    /**
     * Through a temporary file, synced, and a rename: a crash or a dead battery leaves the old file
     * or the new one, never half. A private file is this user's only before a byte of it is written
     * (the app's directory is closed to other apps anyway).
     */
    private fun writeWhole(target: File, text: String, private: Boolean) {
        val dir = target.parentFile!!.apply { mkdirs() }
        val temp = File(dir, ".${target.name}.tmp")
        temp.delete()
        if (private) {
            temp.createNewFile()
            temp.setReadable(false, false)
            temp.setReadable(true, true)
            temp.setWritable(false, false)
            temp.setWritable(true, true)
        }
        FileOutputStream(temp).use { out ->
            out.write(text.encodeToByteArray())
            out.fd.sync()
        }
        if (!temp.renameTo(target)) throw IOException("Could not replace $target")
    }
}

/**
 * A context whose preferences, by any name, are [prefs]: what `PrefsStorage.init` is handed, so the
 * one store of Superizer reads and writes the file of one server. It is its own application context,
 * because that is the one `init` asks for its preferences.
 */
private class ServerPrefs(base: Context, private val prefs: String) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        super.getSharedPreferences(prefs, mode)
}
