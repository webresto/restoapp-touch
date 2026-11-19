@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package cx.m42.restoapp

private fun randomByte(): Int = js("crypto.getRandomValues(new Uint8Array(1))[0]")

/** The browser's CSPRNG, a byte at a time (the sizes asked are a few dozen bytes). */
internal actual fun secureRandom(size: Int): ByteArray = ByteArray(size) { randomByte().toByte() }
