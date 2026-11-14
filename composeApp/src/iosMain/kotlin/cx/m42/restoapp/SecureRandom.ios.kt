package cx.m42.restoapp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandom(size: Int): ByteArray {
    val bytes = ByteArray(size)
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.toULong(), it.addressOf(0)) }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    return bytes
}
