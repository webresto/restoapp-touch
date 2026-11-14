package cx.m42.restoapp.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * `solve` of crypto-puzzle 5.0.x in plain Kotlin, for the platforms without the JDK (iOS): the same
 * steps as [CryptoPuzzleSolver] (its comment has the archive), on big integers of its own and the
 * crypto of [PuzzleCrypto]. The JVMs keep the JDK's solver; this one is checked against it on the
 * desktop, on the same tasks.
 *
 * The squarings are Montgomery multiplications on 32-bit limbs: `n` is a couple of hundred bits, so
 * a squaring is a few dozen multiplications of longs and the loop has no allocations.
 */
public object KotlinPuzzleSolver : PowSolver {

    private const val KEY_BYTES = 32
    private const val CHECK_EVERY = 1 shl 16

    override suspend fun solve(task: ByteArray, progress: (Float) -> Unit): String = withContext(Dispatchers.Default) {
        val puzzle = unarchive(task)
        val b = squarings(puzzle.a, puzzle.t, puzzle.n, progress)
        if (Limbs.compare(puzzle.ck, b) < 0) throw PowException("The task does not open: negative key")
        val short = Limbs.toBytes(Limbs.subtract(puzzle.ck, b))
        val keys = if (short.size < KEY_BYTES) listOf(short, ByteArray(KEY_BYTES - short.size) + short) else listOf(short)
        for (key in keys) {
            decrypt(puzzle.cm, key)?.let { return@withContext it.decodeToString() }
        }
        throw PowException("The task does not open: no key fits")
    }

    /** The numbers as limbs, little-endian, 32 bits in each long. */
    internal class Puzzle(val n: LongArray, val a: LongArray, val t: Long, val ck: LongArray, val cm: ByteArray)

    internal fun unarchive(task: ByteArray): Puzzle {
        var at = 0
        fun next(): ByteArray {
            if (task.size - at < 4) throw PowException("Truncated task")
            val length = ((task[at].toInt() and 0xFF) shl 24) or ((task[at + 1].toInt() and 0xFF) shl 16) or
                ((task[at + 2].toInt() and 0xFF) shl 8) or (task[at + 3].toInt() and 0xFF)
            at += 4
            if (length < 0 || length > task.size - at) throw PowException("Truncated task")
            return task.copyOfRange(at, at + length).also { at += length }
        }
        val n = Limbs.fromBytes(next())
        val a = Limbs.fromBytes(next())
        val tBytes = Limbs.toBytes(Limbs.fromBytes(next()))
        val ck = Limbs.fromBytes(next())
        val cm = task.copyOfRange(at, task.size)
        if (n.isEmpty()) throw PowException("Bad task: n = 0")
        if (tBytes.size > 8 || (tBytes.size == 8 && tBytes[0].toInt() and 0xC0 != 0)) throw PowException("Bad task: t is out of reach")
        var t = 0L
        for (byte in tBytes) t = (t shl 8) or (byte.toLong() and 0xFF)
        return Puzzle(n, a, t, ck, cm)
    }

    /** sfme of crypto-puzzle: `x = a mod n`, then `t` times `x = x² mod n`. */
    internal suspend fun squarings(a: LongArray, t: Long, n: LongArray, progress: (Float) -> Unit): LongArray {
        val step = maxOf(1L, t / 100)
        progress(0f)
        val x = Limbs.mod(a, n)
        if (n[0] and 1L == 0L) {
            // Montgomery needs an odd modulus; an RSA modulus is one, so this is only for a strange task.
            var y = x
            for (done in 1..t) {
                y = Limbs.mod(Limbs.multiply(y, y), n)
                if (done % CHECK_EVERY == 0L) currentCoroutineContext().ensureActive()
                if (done % step == 0L) progress(done.toFloat() / t)
            }
            progress(1f)
            return y
        }
        val m = Montgomery(n)
        var cur = m.toForm(x)
        var other = LongArray(n.size)
        var done = 0L
        while (done < t) {
            m.multiply(cur, cur, other)
            val swap = cur
            cur = other
            other = swap
            done++
            if (done % CHECK_EVERY == 0L) currentCoroutineContext().ensureActive()
            if (done % step == 0L) progress(done.toFloat() / t)
        }
        progress(1f)
        return m.fromForm(cur)
    }

    /** tiny-encryptor v1; null when the tag says [secret] is not the key. */
    internal fun decrypt(archive: ByteArray, secret: ByteArray): ByteArray? {
        if (archive.size < 53 + 16 || archive[0] != 1.toByte()) throw PowException("Unsupported encrypted archive")
        if (secret.isEmpty()) return null
        val salt = archive.copyOfRange(1, 33)
        val rounds = ((archive[33].toInt() and 0xFF) shl 24) or ((archive[34].toInt() and 0xFF) shl 16) or
            ((archive[35].toInt() and 0xFF) shl 8) or (archive[36].toInt() and 0xFF)
        if (rounds < 1) throw PowException("Bad PBKDF2 rounds: $rounds")
        val iv = archive.copyOfRange(37, 53)
        val encrypted = archive.copyOfRange(53, archive.size)
        val key = PuzzleCrypto.pbkdf2Sha256(secret, salt, rounds)
        return PuzzleCrypto.aesGcmDecrypt(key, iv, encrypted)
    }
}

/** Unsigned big integers as limbs: little-endian, 32 bits in each long, no zero limbs on top. */
internal object Limbs {
    private const val MASK = 0xFFFFFFFFL

    fun fromBytes(bytes: ByteArray): LongArray {
        val limbs = LongArray((bytes.size + 3) / 4)
        for (i in bytes.indices) {
            val bit = (bytes.size - 1 - i) * 8
            limbs[bit / 32] = limbs[bit / 32] or ((bytes[i].toLong() and 0xFF) shl (bit % 32))
        }
        return trim(limbs)
    }

    /** Big-endian without leading zeros, zero as no bytes at all (bigint-encoding's `decode`). */
    fun toBytes(limbs: LongArray): ByteArray {
        val full = ByteArray(limbs.size * 4)
        for (i in limbs.indices) {
            for (k in 0 until 4) full[full.size - 1 - (i * 4 + k)] = (limbs[i] ushr (8 * k)).toByte()
        }
        val first = full.indexOfFirst { it != 0.toByte() }
        return if (first < 0) ByteArray(0) else full.copyOfRange(first, full.size)
    }

    fun trim(limbs: LongArray): LongArray {
        var size = limbs.size
        while (size > 0 && limbs[size - 1] == 0L) size--
        return if (size == limbs.size) limbs else limbs.copyOf(size)
    }

    fun compare(a: LongArray, b: LongArray): Int {
        val x = trim(a)
        val y = trim(b)
        if (x.size != y.size) return x.size.compareTo(y.size)
        for (i in x.indices.reversed()) if (x[i] != y[i]) return x[i].compareTo(y[i])
        return 0
    }

    /** a − b, for a ≥ b. */
    fun subtract(a: LongArray, b: LongArray): LongArray {
        val out = LongArray(a.size)
        var borrow = 0L
        for (i in a.indices) {
            val d = a[i] - (if (i < b.size) b[i] else 0L) - borrow
            out[i] = d and MASK
            borrow = if (d < 0) 1L else 0L
        }
        return trim(out)
    }

    fun multiply(a: LongArray, b: LongArray): LongArray {
        val out = LongArray(a.size + b.size)
        for (i in a.indices) {
            var carry = 0uL
            val ai = a[i].toULong()
            for (j in b.indices) {
                val s = out[i + j].toULong() + ai * b[j].toULong() + carry
                out[i + j] = (s and 0xFFFFFFFFuL).toLong()
                carry = s shr 32
            }
            out[i + b.size] = carry.toLong()
        }
        return trim(out)
    }

    /** a mod n, a bit at a time: for the few reductions outside the loop. */
    fun mod(a: LongArray, n: LongArray): LongArray {
        val modulus = trim(n)
        val r = LongArray(modulus.size + 1)
        for (bit in a.size * 32 - 1 downTo 0) {
            shiftLeftOne(r)
            r[0] = r[0] or ((a[bit / 32] ushr (bit % 32)) and 1L)
            if (compare(r, modulus) >= 0) subtractInPlace(r, modulus)
        }
        return trim(r)
    }

    fun shiftLeftOne(x: LongArray) {
        var carry = 0L
        for (i in x.indices) {
            val v = (x[i] shl 1) or carry
            carry = (v ushr 32) and 1L
            x[i] = v and MASK
        }
    }

    fun subtractInPlace(x: LongArray, y: LongArray) {
        var borrow = 0L
        for (i in x.indices) {
            val d = x[i] - (if (i < y.size) y[i] else 0L) - borrow
            x[i] = d and MASK
            borrow = if (d < 0) 1L else 0L
        }
    }
}

/** Montgomery arithmetic modulo an odd [n], R = 2^(32·limbs). */
internal class Montgomery(n: LongArray) {
    private val n = Limbs.trim(n)
    private val s = this.n.size
    private val n0inv: ULong
    private val r2: LongArray
    private val scratch = LongArray(s + 2)

    init {
        // −n⁻¹ mod 2^32 by Newton's iteration: each step doubles the correct bits.
        val n0 = this.n[0].toULong()
        var inv = 1uL
        repeat(5) { inv = (inv * (2uL - n0 * inv)) and 0xFFFFFFFFuL }
        n0inv = (0uL - inv) and 0xFFFFFFFFuL
        // R² mod n by doubling 1 2·32·s times.
        val x = LongArray(s + 1).also { it[0] = 1L }
        repeat(64 * s) {
            Limbs.shiftLeftOne(x)
            if (Limbs.compare(x, this.n) >= 0) Limbs.subtractInPlace(x, this.n)
        }
        r2 = x.copyOf(s)
    }

    /** x·R mod n, for x < n. */
    fun toForm(x: LongArray): LongArray = LongArray(s).also { multiply(x.copyOf(s), r2, it) }

    fun fromForm(x: LongArray): LongArray = LongArray(s).also { out -> multiply(x, LongArray(s).also { it[0] = 1L }, out) }.let(Limbs::trim)

    /** out = a·b·R⁻¹ mod n (CIOS); [out] may be neither [a] nor [b]. */
    fun multiply(a: LongArray, b: LongArray, out: LongArray) {
        val t = scratch
        t.fill(0L)
        for (i in 0 until s) {
            val bi = b[i].toULong()
            var c = 0uL
            for (j in 0 until s) {
                val v = t[j].toULong() + a[j].toULong() * bi + c
                t[j] = (v and 0xFFFFFFFFuL).toLong()
                c = v shr 32
            }
            var v = t[s].toULong() + c
            t[s] = (v and 0xFFFFFFFFuL).toLong()
            t[s + 1] = (v shr 32).toLong()

            val m = (t[0].toULong() * n0inv) and 0xFFFFFFFFuL
            v = t[0].toULong() + m * n[0].toULong()
            c = v shr 32
            for (j in 1 until s) {
                v = t[j].toULong() + m * n[j].toULong() + c
                t[j - 1] = (v and 0xFFFFFFFFuL).toLong()
                c = v shr 32
            }
            v = t[s].toULong() + c
            t[s - 1] = (v and 0xFFFFFFFFuL).toLong()
            t[s] = t[s + 1] + (v shr 32).toLong()
        }
        // t < 2n: one subtraction at most.
        var geq = t[s] != 0L
        if (!geq) {
            geq = true
            for (j in s - 1 downTo 0) {
                if (t[j] != n[j]) {
                    geq = t[j] > n[j]
                    break
                }
            }
        }
        if (geq) {
            var borrow = 0L
            for (j in 0 until s) {
                val d = t[j] - n[j] - borrow
                out[j] = d and 0xFFFFFFFFL
                borrow = if (d < 0) 1L else 0L
            }
        } else {
            for (j in 0 until s) out[j] = t[j]
        }
    }
}
