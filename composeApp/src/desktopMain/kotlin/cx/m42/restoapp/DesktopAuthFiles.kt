package cx.m42.restoapp

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The file of sign-ins on the desktop: the system's file dialog, on the UI thread it wants. The file
 * is written whole or not at all, readable by this user only — it holds every server's token.
 */
internal class DesktopAuthFiles : AuthFiles {
    override suspend fun save(suggestedName: String, bytes: ByteArray): Boolean {
        val target = withContext(Dispatchers.Main) {
            ask(FileDialog(null as Frame?, "RestoApp", FileDialog.SAVE).apply { file = suggestedName })
        } ?: return false
        withContext(Dispatchers.IO) {
            val file = if (target.name.contains('.')) target else File(target.parentFile, "${target.name}.${AuthFile.EXTENSION}")
            val temp = File(file.parentFile, ".${file.name}.tmp")
            temp.writeBytes(bytes)
            temp.setReadable(false, false)
            temp.setReadable(true, true)
            temp.setWritable(false, false)
            temp.setWritable(true, true)
            if (!temp.renameTo(file)) {
                file.delete()
                check(temp.renameTo(file)) { "Cannot write $file" }
            }
        }
        return true
    }

    override suspend fun open(): ByteArray? {
        val picked = withContext(Dispatchers.Main) {
            ask(FileDialog(null as Frame?, "RestoApp", FileDialog.LOAD).apply {
                setFilenameFilter { _, name -> name.endsWith(".${AuthFile.EXTENSION}") }
            })
        } ?: return null
        return withContext(Dispatchers.IO) { picked.readBytes() }
    }

    private fun ask(dialog: FileDialog): File? {
        dialog.isVisible = true
        val name = dialog.file ?: return null
        return File(dialog.directory, name)
    }
}
