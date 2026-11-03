package cx.m42.restoapp

import cx.m42.superizer.host.storage.PrefsStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The servers on the desktop (D6): the list in `<base>/servers.json`, and each server in
 * `<base>/servers/<id>/` — its settings (Home, session, language, every app's data) in
 * `settings.properties`, and the host's files next to them: `session.json` with the device token,
 * readable by this user only (mode 600), `declaration.json`, `place.json`, `demo.json`.
 */
public class DesktopServerStore(private val base: File) : ServerStore {
    private val file: File get() = File(base, "servers.json")

    private fun dirOf(serverId: String): File = File(File(base, "servers"), serverId)

    override fun load(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    override fun save(text: String) {
        runCatching { writeWhole(file, text, private = false) }
    }

    override fun useStorageOf(serverId: String) {
        PrefsStorage.useDirectory(dirOf(serverId).apply { mkdirs() })
    }

    override fun erase(serverId: String) {
        runCatching { dirOf(serverId).deleteRecursively() }
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
     * Through a temporary file and a rename: a crash leaves the old file or the new one, never half.
     * A private file is created with mode 600 before a byte of it is written.
     */
    private fun writeWhole(target: File, text: String, private: Boolean) {
        val dir = target.parentFile.apply { mkdirs() }
        val temp = File(dir, ".${target.name}.tmp")
        temp.delete()
        if (private && supportsPosix(dir)) {
            Files.createFile(temp.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        }
        temp.writeText(text)
        if (private && !supportsPosix(dir)) {
            temp.setReadable(false, false)
            temp.setReadable(true, true)
            temp.setWritable(false, false)
            temp.setWritable(true, true)
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun supportsPosix(dir: File): Boolean =
        runCatching { Files.getFileStore(dir.toPath()).supportsFileAttributeView("posix") }.getOrDefault(false)
}
