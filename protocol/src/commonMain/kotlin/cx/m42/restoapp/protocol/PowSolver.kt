package cx.m42.restoapp.protocol

/**
 * Solves the login CAPTCHA: a crypto-puzzle 5 time-lock task, the same the web login of adminizer
 * gives the browser (plan section 2). The answer is the message hidden in the task; the client never
 * reads it, the server checks its signature.
 *
 * Seconds of work on purpose: run it off the main thread, and cancel it with its coroutine.
 */
public fun interface PowSolver {
    /** [progress] goes from 0 to 1, a hundred times at most. */
    public suspend fun solve(task: ByteArray, progress: (Float) -> Unit): String
}

/** A task that is not a crypto-puzzle 5 archive, or one no key opens. Ask the server for another. */
public class PowException(message: String, cause: Throwable? = null) : Exception(message, cause)
