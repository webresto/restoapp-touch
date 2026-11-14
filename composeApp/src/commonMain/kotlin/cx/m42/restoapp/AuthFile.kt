package cx.m42.restoapp

import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The file the sign-ins of this app travel in to another one (`ManageServersScreen`, «Перенос входов»):
 * every server's address, name and device token, sealed by a password the person types.
 *
 * A copy, not a move: the server sees one device — the one that signed in — and a sign-out on either
 * side revokes it on both. That is why the platforms keep the app out of their backups (`allowBackup`,
 * `data_extraction_rules.xml`): a token leaves the device only when its owner asks, and only sealed.
 *
 * The cipher is written here, in common code, as `Sha256.kt` of :protocol is — every target reads the
 * file another one wrote, and none needs a platform API but its random numbers ([secureRandom]):
 * PBKDF2-HMAC-SHA256 makes a key of the password, HMAC splits it into one for ChaCha20 (RFC 8439)
 * and one for HMAC-SHA256 over everything the file says (encrypt-then-MAC). The desktop tests check
 * each piece against the JDK's own.
 */

/** Random bytes from the platform's cryptographic source: the salt and the nonce. */
internal expect fun secureRandom(size: Int): ByteArray

/** Why a file could not be opened. */
internal class AuthFileException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NotAuthFile, Unsupported, WrongPassword }
}

/** The file as it lies on the disk: JSON, everything but the format in Base64. */
@Serializable
internal data class SealedAuthFile(
    val format: String,
    val version: Int,
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val cipher: String,
    val nonce: String,
    val data: String,
    val mac: String,
)

/** What is sealed: the servers, in the order of the list, a cloud account's servers right after it. */
@Serializable
internal data class AuthBundle(val servers: List<AuthEntry>)

/** One server: its profile as the switcher has it, and who it is signed in as — null when signed out. */
@Serializable
internal data class AuthEntry(
    val id: String,
    val name: String,
    val address: String,
    val cloud: Boolean = false,
    val parent: String? = null,
    val cloudId: String? = null,
    val login: String? = null,
    val token: String? = null,
)

internal object AuthFile {
    const val FORMAT = "restoapp-auth"
    const val VERSION = 1
    const val KDF = "pbkdf2-sha256"
    const val CIPHER = "chacha20-hmac-sha256"

    /** OWASP's figure for PBKDF2-HMAC-SHA256 is higher; this is about a second on a phone, and a file is opened rarely. */
    const val ITERATIONS = 310_000

    /** What a file may ask for: fewer is too weak to have been written by this app, more is a file that hangs it. */
    private val ACCEPTED_ITERATIONS = 100_000..5_000_000

    const val MIN_PASSWORD = 8

    /** What a file of this app is called: the extension the pickers filter by. */
    const val EXTENSION = "restoapp-auth"
    const val MIME = "application/octet-stream"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun seal(bundle: AuthBundle, password: String, iterations: Int = ITERATIONS): ByteArray {
        val salt = secureRandom(16)
        val nonce = secureRandom(12)
        val (encKey, macKey) = keysOf(password, salt, iterations)
        val plain = json.encodeToString(AuthBundle.serializer(), bundle).encodeToByteArray()
        val data = chacha20(encKey, nonce, 0, plain)
        plain.fill(0)
        val head = SealedAuthFile(FORMAT, VERSION, KDF, iterations, Base64.encode(salt), CIPHER, Base64.encode(nonce), Base64.encode(data), mac = "")
        val sealed = head.copy(mac = Base64.encode(hmacSha256(macKey, macInput(head, data))))
        encKey.fill(0)
        macKey.fill(0)
        return Json { prettyPrint = true }.encodeToString(SealedAuthFile.serializer(), sealed).encodeToByteArray()
    }

    fun open(bytes: ByteArray, password: String): AuthBundle {
        val sealed = runCatching { json.decodeFromString(SealedAuthFile.serializer(), bytes.decodeToString()) }.getOrNull()
            ?.takeIf { it.format == FORMAT }
            ?: throw AuthFileException(AuthFileException.Reason.NotAuthFile, "Not a file of RestoApp's sign-ins")
        if (sealed.version != VERSION || sealed.kdf != KDF || sealed.cipher != CIPHER || sealed.iterations !in ACCEPTED_ITERATIONS) {
            throw AuthFileException(AuthFileException.Reason.Unsupported, "A file of another version: ${sealed.version}, ${sealed.kdf}, ${sealed.cipher}")
        }
        val salt: ByteArray
        val nonce: ByteArray
        val data: ByteArray
        val mac: ByteArray
        try {
            salt = Base64.decode(sealed.salt)
            nonce = Base64.decode(sealed.nonce)
            data = Base64.decode(sealed.data)
            mac = Base64.decode(sealed.mac)
        } catch (bad: IllegalArgumentException) {
            throw AuthFileException(AuthFileException.Reason.NotAuthFile, "The file is damaged")
        }
        if (nonce.size != 12 || salt.isEmpty()) throw AuthFileException(AuthFileException.Reason.NotAuthFile, "The file is damaged")
        val (encKey, macKey) = keysOf(password, salt, sealed.iterations)
        try {
            // A wrong password and a changed file look the same: neither gives this MAC.
            if (!sameBytes(hmacSha256(macKey, macInput(sealed, data)), mac)) {
                throw AuthFileException(AuthFileException.Reason.WrongPassword, "Wrong password, or the file was changed")
            }
            val plain = chacha20(encKey, nonce, 0, data)
            try {
                return json.decodeFromString(AuthBundle.serializer(), plain.decodeToString())
            } catch (bad: Exception) {
                throw AuthFileException(AuthFileException.Reason.NotAuthFile, "The file is damaged")
            } finally {
                plain.fill(0)
            }
        } finally {
            encKey.fill(0)
            macKey.fill(0)
        }
    }

    /** Everything the file says but the MAC, so that no field of it can be changed unnoticed. */
    private fun macInput(head: SealedAuthFile, data: ByteArray): ByteArray =
        "${head.format}|${head.version}|${head.kdf}|${head.iterations}|${head.salt}|${head.cipher}|${head.nonce}|".encodeToByteArray() + data

    private fun keysOf(password: String, salt: ByteArray, iterations: Int): Pair<ByteArray, ByteArray> {
        val secret = password.encodeToByteArray()
        val master = pbkdf2Sha256(secret, salt, iterations, 32)
        secret.fill(0)
        val keys = hmacSha256(master, "enc".encodeToByteArray()) to hmacSha256(master, "mac".encodeToByteArray())
        master.fill(0)
        return keys
    }

    private fun sameBytes(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

// ------------------------------------------------------------------ SHA-256, HMAC, PBKDF2

private val K = intArrayOf(
    0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
    -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
    -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
    -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
)

private val H0 = intArrayOf(0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19)

/** SHA-256 by parts, and copyable: HMAC keeps the state after its padded key and starts every message from it. */
internal class Sha256 {
    private val h = H0.copyOf()
    private val block = ByteArray(64)
    private var filled = 0
    private var length = 0L
    private val w = IntArray(64)

    fun update(bytes: ByteArray, from: Int = 0, size: Int = bytes.size - from): Sha256 {
        var at = from
        val end = from + size
        length += size
        while (at < end) {
            val take = minOf(64 - filled, end - at)
            bytes.copyInto(block, filled, at, at + take)
            filled += take
            at += take
            if (filled == 64) {
                compress()
                filled = 0
            }
        }
        return this
    }

    fun digest(): ByteArray {
        val bits = length * 8
        block[filled++] = 0x80.toByte()
        if (filled > 56) {
            block.fill(0, filled, 64)
            compress()
            filled = 0
        }
        block.fill(0, filled, 56)
        for (i in 0 until 8) block[63 - i] = (bits ushr (8 * i)).toByte()
        compress()
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (h[i] ushr 24).toByte()
            out[i * 4 + 1] = (h[i] ushr 16).toByte()
            out[i * 4 + 2] = (h[i] ushr 8).toByte()
            out[i * 4 + 3] = h[i].toByte()
        }
        return out
    }

    fun copy(): Sha256 = Sha256().also {
        h.copyInto(it.h)
        block.copyInto(it.block)
        it.filled = filled
        it.length = length
    }

    private fun compress() {
        for (i in 0 until 16) {
            val at = i * 4
            w[i] = ((block[at].toInt() and 0xff) shl 24) or ((block[at + 1].toInt() and 0xff) shl 16) or
                ((block[at + 2].toInt() and 0xff) shl 8) or (block[at + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (i in 0 until 64) {
            val t1 = hh + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) + ((e and f) xor (e.inv() and g)) + K[i] + w[i]
            val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) + ((a and b) xor (a and c) xor (b and c))
            hh = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh
    }
}

private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))

/** HMAC-SHA256 with the key's two padded states ready, for PBKDF2's many messages under one key. */
private class Hmac(key: ByteArray) {
    private val inner: Sha256
    private val outer: Sha256

    init {
        val k = ByteArray(64)
        (if (key.size > 64) Sha256().update(key).digest() else key).copyInto(k)
        inner = Sha256().update(ByteArray(64) { (k[it].toInt() xor 0x36).toByte() })
        outer = Sha256().update(ByteArray(64) { (k[it].toInt() xor 0x5c).toByte() })
        k.fill(0)
    }

    fun mac(message: ByteArray): ByteArray = outer.copy().update(inner.copy().update(message).digest()).digest()
}

internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray = Hmac(key).mac(message)

internal fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, size: Int): ByteArray {
    require(iterations > 0 && size > 0)
    val prf = Hmac(password)
    val out = ByteArray(size)
    var block = 1
    var at = 0
    while (at < size) {
        val first = salt + byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte())
        var u = prf.mac(first)
        val t = u.copyOf()
        for (i in 1 until iterations) {
            u = prf.mac(u)
            for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
        }
        t.copyInto(out, at, 0, minOf(32, size - at))
        at += 32
        block++
    }
    return out
}

// ------------------------------------------------------------------ ChaCha20 (RFC 8439)

/** ChaCha20 with a 32-byte [key], a 12-byte [nonce] and the block counter starting at [counter]: encrypts and decrypts alike. */
internal fun chacha20(key: ByteArray, nonce: ByteArray, counter: Int, input: ByteArray): ByteArray {
    require(key.size == 32 && nonce.size == 12)
    val state = IntArray(16)
    state[0] = 0x61707865; state[1] = 0x3320646e; state[2] = 0x79622d32; state[3] = 0x6b206574
    for (i in 0 until 8) state[4 + i] = littleEndian(key, i * 4)
    state[12] = counter
    for (i in 0 until 3) state[13 + i] = littleEndian(nonce, i * 4)
    val x = IntArray(16)
    val out = ByteArray(input.size)
    var at = 0
    while (at < input.size) {
        state.copyInto(x)
        repeat(10) {
            quarter(x, 0, 4, 8, 12); quarter(x, 1, 5, 9, 13); quarter(x, 2, 6, 10, 14); quarter(x, 3, 7, 11, 15)
            quarter(x, 0, 5, 10, 15); quarter(x, 1, 6, 11, 12); quarter(x, 2, 7, 8, 13); quarter(x, 3, 4, 9, 14)
        }
        for (i in 0 until 16) {
            val word = x[i] + state[i]
            for (b in 0 until 4) {
                val n = at + i * 4 + b
                if (n < input.size) out[n] = (input[n].toInt() xor (word ushr (8 * b))).toByte()
            }
        }
        at += 64
        state[12]++
    }
    x.fill(0)
    state.fill(0)
    return out
}

private fun quarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
    x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
    x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
    x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
    x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
}

private fun Int.rotateLeft(bits: Int): Int = (this shl bits) or (this ushr (32 - bits))

private fun littleEndian(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or
        ((bytes[at + 2].toInt() and 0xff) shl 16) or ((bytes[at + 3].toInt() and 0xff) shl 24)
