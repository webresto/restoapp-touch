package cx.m42.restoapp

import cx.m42.superizer.host.storage.PrefsStorage
import java.io.File

/**
 * The servers on the desktop (D6): the list in `<base>/servers.json`, and each server's settings —
 * Home, session, language, every app's data — in `<base>/servers/<id>/settings.properties`.
 */
public class DesktopServerStore(private val base: File) : ServerStore {
    private val file: File get() = File(base, "servers.json")

    private fun dirOf(serverId: String): File = File(File(base, "servers"), serverId)

    override fun load(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    override fun save(text: String) {
        runCatching {
            base.mkdirs()
            file.writeText(text)
        }
    }

    override fun useStorageOf(serverId: String) {
        PrefsStorage.useDirectory(dirOf(serverId).apply { mkdirs() })
    }

    override fun erase(serverId: String) {
        runCatching { dirOf(serverId).deleteRecursively() }
    }
}
