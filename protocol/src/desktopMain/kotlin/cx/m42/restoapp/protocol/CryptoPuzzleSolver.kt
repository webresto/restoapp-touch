package cx.m42.restoapp.protocol

import java.math.BigInteger
import java.nio.ByteBuffer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * `solve` of crypto-puzzle 5.0.x on the JVM, with nothing but the JDK (plan section 2).
 *
 * 1. The archive: `[len n][n][len a][a][len t][t][len Ck][Ck][Cm]`, lengths Int32 big-endian, numbers
 *    unsigned big-endian (`bigint-encoding`).
 * 2. `b = a^(2^t) mod n` — `t` squarings, the work the server sized in milliseconds.
 * 3. The key `K = Ck − b`, as bytes.
 * 4. `Cm` is tiny-encryptor v1: `[1][salt 32][rounds Int32][iv 16][ciphertext + tag]`; the AES key is
 *    PBKDF2-HMAC-SHA256(K, salt, rounds, 32), then AES-GCM with a 128-bit tag.
 * 5. The plaintext, UTF-8, is the answer.
 *
 * On step 3 this is deliberately *not* a copy of the JS. There `K` goes back to bytes without leading
 * zeros, while it was encrypted as the 32 bytes of a sha256 — so when that hash starts with a zero
 * byte (one task in 256) the JS solver of the web login cannot open its own task. Here the short key
 * is tried first, as the JS does, and the key padded back to 32 bytes when the tag says it was wrong.
 */
public object CryptoPuzzleSolver : PowSolver {

    private const val KEY_BYTES = 32
    private const val CHECK_EVERY = 1 shl 16

    override suspend fun solve(task: ByteArray, progress: (Float) -> Unit): String = withContext(Dispatchers.Default) {
        val puzzle = unarchive(task)
        val b = squarings(puzzle.a, puzzle.t, puzzle.n, progress)
        val k = puzzle.ck.subtract(b)
        if (k.signum() < 0) throw PowException("The task does not open: negative key")
        val short = unsignedBytes(k)
        val keys = if (short.size < KEY_BYTES) listOf(short, ByteArray(KEY_BYTES - short.size) + short) else listOf(short)
        for (key in keys) {
            decrypt(puzzle.cm, key)?.let { return@withContext it.decodeToString() }
        }
        throw PowException("The task does not open: no key fits")
    }

    internal class Puzzle(val n: BigInteger, val a: BigInteger, val t: BigInteger, val ck: BigInteger, val cm: ByteArray)

    internal fun unarchive(task: ByteArray): Puzzle {
        val buffer = ByteBuffer.wrap(task)
        fun next(): BigInteger {
            if (buffer.remaining() < 4) throw PowException("Truncated task")
            val length = buffer.int
            if (length < 0 || length > buffer.remaining()) throw PowException("Truncated task")
            val bytes = ByteArray(length).also { buffer.get(it) }
            return BigInteger(1, bytes)
        }
        val n = next()
        val a = next()
        val t = next()
        val ck = next()
        val cm = ByteArray(buffer.remaining()).also { buffer.get(it) }
        if (n.signum() == 0) throw PowException("Bad task: n = 0")
        if (t.bitLength() > 62) throw PowException("Bad task: t is out of reach")
        return Puzzle(n, a, t, ck, cm)
    }

    /** sfme of crypto-puzzle: `x = a mod n`, then `t` times `x = x² mod n`. */
    internal suspend fun squarings(a: BigInteger, t: BigInteger, n: BigInteger, progress: (Float) -> Unit): BigInteger {
        val total = t.toLong()
        val step = maxOf(1L, total / 100)
        var x = a.mod(n)
        var done = 0L
        progress(0f)
        while (done < total) {
            x = x.multiply(x).mod(n)
            done++
            if (done % CHECK_EVERY == 0L) currentCoroutineContext().ensureActive()
            if (done % step == 0L) progress(done.toFloat() / total)
        }
        progress(1f)
        return x
    }

    /** bigint-encoding's `decode`: big-endian, no leading zeros, zero as no bytes at all. */
    internal fun unsignedBytes(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        val first = bytes.indexOfFirst { it != 0.toByte() }
        return if (first < 0) ByteArray(0) else bytes.copyOfRange(first, bytes.size)
    }

    /** tiny-encryptor v1; null when the tag says [secret] is not the key. */
    internal fun decrypt(archive: ByteArray, secret: ByteArray): ByteArray? {
        if (archive.size < 53 + 16 || archive[0] != 1.toByte()) throw PowException("Unsupported encrypted archive")
        if (secret.isEmpty()) return null
        val salt = archive.copyOfRange(1, 33)
        val rounds = ByteBuffer.wrap(archive, 33, 4).int
        if (rounds < 1) throw PowException("Bad PBKDF2 rounds: $rounds")
        val iv = archive.copyOfRange(37, 53)
        val encrypted = archive.copyOfRange(53, archive.size)
        val key = pbkdf2Sha256(secret, salt, rounds)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return try {
            cipher.doFinal(encrypted)
        } catch (_: AEADBadTagException) {
            null
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 for one 32-byte block, over a *byte* password: `PBEKeySpec` takes only chars,
     * and `K` is bytes. HMAC pads a short key with zeros on the right, as WebCrypto does.
     */
    internal fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, rounds: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        mac.update(salt)
        var u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        repeat(rounds - 1) {
            u = mac.doFinal(u)
            for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        return result
    }
}
