package cx.m42.restoapp

/**
 * The platform's own «save a file» and «open a file» for the file of sign-ins ([AuthFile]): a file
 * dialog on the desktop, the Storage Access Framework on Android. The person picks where; the app
 * writes or reads the bytes and keeps no path. A platform without one has no «Перенос входов».
 */
public interface AuthFiles {
    /** Writes [bytes] where the person says, [suggestedName] offered. False when they closed the dialog. */
    public suspend fun save(suggestedName: String, bytes: ByteArray): Boolean

    /** The bytes of the file the person picked, or null when they closed the dialog. */
    public suspend fun open(): ByteArray?
}
