package cx.m42.restoapp.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Tasks generated once by the real crypto-puzzle 5.0.3 of adminizer (`tools/pow-testdata.mjs`), and
 * what they hide. Two of them have a key that starts with a zero byte: the JS solver cannot open
 * those (`jsSolves: false` in the file), this one must.
 */
class CryptoPuzzleSolverTest {

    @Serializable
    private data class TestTask(val message: String, val duration: Int, val keyLength: Int, val jsSolves: Boolean, val task: List<Int>)

    @Serializable
    private data class TestData(val cryptoPuzzle: String, val tasks: List<TestTask>)

    private val data: TestData = Json.decodeFromString(
        TestData.serializer(),
        javaClass.getResource("/pow-testdata.json")!!.readText(),
    )

    private fun TestTask.bytes() = ByteArray(task.size) { task[it].toByte() }

    @Test
    fun theTasksOfTheRealCryptoPuzzleOpenToTheirMessages(): Unit = runBlocking {
        assertTrue(data.cryptoPuzzle.startsWith("5."))
        data.tasks.filter { it.keyLength == 32 }.also { assertTrue(it.size >= 4) }.forEach { task ->
            assertEquals(task.message, CryptoPuzzleSolver.solve(task.bytes()) {})
        }
    }

    @Test
    fun aKeyWithALeadingZeroByteOpensHereThoughNotInJs(): Unit = runBlocking {
        val short = data.tasks.filter { it.keyLength < 32 }
        assertTrue(short.isNotEmpty())
        short.forEach { task ->
            assertEquals(false, task.jsSolves)
            assertEquals(task.message, CryptoPuzzleSolver.solve(task.bytes()) {})
        }
    }

    @Test
    fun progressGoesFromZeroToOne(): Unit = runBlocking {
        val seen = mutableListOf<Float>()
        CryptoPuzzleSolver.solve(data.tasks.first { it.duration > 1 }.bytes()) { seen += it }
        assertEquals(0f, seen.first())
        assertEquals(1f, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(seen.size in 3..103)
    }

    @Test
    fun aBrokenTaskIsAPowExceptionNotACrash(): Unit = runBlocking {
        assertFailsWith<PowException> { CryptoPuzzleSolver.solve(byteArrayOf(0, 0, 0, 9, 1)) {} }
        val tampered = data.tasks.first().bytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertFailsWith<PowException> { CryptoPuzzleSolver.solve(tampered) {} }
    }

    @Test
    fun cancellingStopsTheWork() {
        // t of a 3-second task, a = 3, n = a 200-bit odd number: cancelled long before the end.
        val n = java.math.BigInteger.ONE.shiftLeft(200).add(java.math.BigInteger.valueOf(297))
        val job = Job()
        val started = System.nanoTime()
        job.cancel()
        assertFailsWith<CancellationException> {
            runBlocking(job) { CryptoPuzzleSolver.squarings(java.math.BigInteger.valueOf(3), java.math.BigInteger.valueOf(10_000_000), n) {} }
        }
        assertTrue(System.nanoTime() - started < 1_000_000_000L)
    }
}
