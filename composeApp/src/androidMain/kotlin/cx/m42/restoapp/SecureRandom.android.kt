package cx.m42.restoapp

import java.security.SecureRandom

private val random = SecureRandom()

internal actual fun secureRandom(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)
