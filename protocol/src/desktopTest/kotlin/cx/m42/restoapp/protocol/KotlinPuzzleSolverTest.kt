package cx.m42.restoapp.protocol

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The plain-Kotlin solver of iOS, on the desktop: the same real tasks of crypto-puzzle 5.0.3 as
 * [CryptoPuzzleSolverTest] (keys with a leading zero byte too), and each piece of its arithmetic and
 * crypto against the JDK on random inputs.
 */
class KotlinPuzzleSolverTest {

    @Serializable
    private data class TestTask(val message: String, val duration: Int, val keyLength: Int, val jsSolves: Boolean, val task: List<Int>)

    @Serializable
    private data class TestData(val cryptoPuzzle: String, val tasks: List<TestTask>)

    private val data: TestData = Json.decodeFromString(
        TestData.serializer(),
        javaClass.getResource("/pow-testdata.json")!!.readText(),
    )

    private fun TestTask.bytes() = ByteArray(task.size) { task[it].toByte() }

    private val random = Random(42)

    private fun big(limbs: LongArray) = BigInteger(1, Limbs.toBytes(limbs))

    private fun limbs(value: BigInteger) = Limbs.fromBytes(value.toByteArray())

    @Test
    fun everyTaskOfTheRealCryptoPuzzleOpensToItsMessage(): Unit = runBlocking {
        assertTrue(data.tasks.any { it.keyLength < 32 })
        data.tasks.forEach { task ->
            assertEquals(task.message, KotlinPuzzleSolver.solve(task.bytes()) {})
        }
    }

    @Test
    fun theSquaringsAreTheJdksModPow(): Unit = runBlocking {
        repeat(20) {
            val bits = listOf(64, 200, 256, 512)[it % 4]
            val n = BigInteger(bits, java.util.Random(it.toLong())).setBit(bits - 1).setBit(0)
            val a = BigInteger(bits + 8, java.util.Random(it + 100L))
            val t = 1L + random.nextInt(2000)
            val expected = a.mod(n).modPow(BigInteger.TWO.pow(t.toInt()), n)
            assertEquals(expected, big(KotlinPuzzleSolver.squarings(limbs(a), t, limbs(n)) {}), "n=$n a=$a t=$t")
        }
        // An even modulus takes the slow path, with the same answer.
        val even = BigInteger.ONE.shiftLeft(130).add(BigInteger.valueOf(12))
        assertEquals(BigInteger.valueOf(7).modPow(BigInteger.TWO.pow(50), even), big(KotlinPuzzleSolver.squarings(limbs(BigInteger.valueOf(7)), 50, limbs(even)) {}))
    }

    @Test
    fun theLimbsAreBigIntegers() {
        repeat(200) {
            val x = BigInteger(random.nextInt(1, 300), java.util.Random(it.toLong()))
            val y = BigInteger(random.nextInt(1, 300), java.util.Random(it + 1000L))
            assertEquals(x, big(limbs(x)))
            assertEquals(x.compareTo(y).coerceIn(-1, 1), Limbs.compare(limbs(x), limbs(y)).coerceIn(-1, 1))
            if (x >= y) assertEquals(x - y, big(Limbs.subtract(limbs(x), limbs(y))))
            assertEquals(x * y, big(Limbs.multiply(limbs(x), limbs(y))))
            if (y.signum() > 0) assertEquals(x.mod(y), big(Limbs.mod(limbs(x), limbs(y))))
        }
        assertEquals(0, Limbs.toBytes(LongArray(3)).size)
    }

    @Test
    fun theCryptoIsTheJdks() {
        for (size in listOf(0, 1, 55, 56, 63, 64, 65, 200)) {
            val message = random.nextBytes(size)
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(message), PuzzleCrypto.sha256(message), "sha256 of $size bytes")
            for (keySize in listOf(1, 31, 32, 64, 100)) {
                val key = random.nextBytes(keySize)
                val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
                assertContentEquals(mac.doFinal(message), PuzzleCrypto.hmacSha256(key, message), "hmac, key $keySize, message $size")
            }
        }
        for (keySize in listOf(16, 24, 32)) {
            for (size in listOf(0, 1, 15, 16, 17, 60, 100)) {
                val key = random.nextBytes(keySize)
                val iv = random.nextBytes(if (size % 2 == 0) 16 else 12)
                val plain = random.nextBytes(size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                val encrypted = cipher.doFinal(plain)
                assertContentEquals(plain, PuzzleCrypto.aesGcmDecrypt(key, iv, encrypted), "AES-$keySize GCM, $size bytes")
                val tampered = encrypted.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
                assertNull(PuzzleCrypto.aesGcmDecrypt(key, iv, tampered))
                assertNull(PuzzleCrypto.aesGcmDecrypt(random.nextBytes(keySize), iv, encrypted))
            }
        }
    }

    @Test
    fun progressGoesFromZeroToOne(): Unit = runBlocking {
        val seen = mutableListOf<Float>()
        KotlinPuzzleSolver.solve(data.tasks.first { it.duration > 1 }.bytes()) { seen += it }
        assertEquals(0f, seen.first())
        assertEquals(1f, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(seen.size in 3..103)
    }

    @Test
    fun aBrokenTaskIsAPowExceptionNotACrash(): Unit = runBlocking {
        assertFailsWith<PowException> { KotlinPuzzleSolver.solve(byteArrayOf(0, 0, 0, 9, 1)) {} }
        assertFailsWith<PowException> { KotlinPuzzleSolver.solve(ByteArray(0)) {} }
        val tampered = data.tasks.first().bytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<PowException> { KotlinPuzzleSolver.solve(tampered) {} }
    }

    @Test
    fun cancellingStopsTheWork() {
        val n = limbs(BigInteger.ONE.shiftLeft(200).add(BigInteger.valueOf(297)))
        val job = Job()
        val started = System.nanoTime()
        job.cancel()
        assertFailsWith<CancellationException> {
            runBlocking(job) { KotlinPuzzleSolver.squarings(limbs(BigInteger.valueOf(3)), 10_000_000, n) {} }
        }
        assertTrue(System.nanoTime() - started < 1_000_000_000L)
    }
}
