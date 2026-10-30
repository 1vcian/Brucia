package app.brucia.session

/**
 * Sends one device command and suspends until its reply: the narrow seam features use
 * instead of the ViewModel. Implementations return (false, msg) for local failures
 * (not connected, closed session, timeout) and propagate cancellation.
 */
fun interface CommandRunner {
    suspend fun run(cmd: String, timeoutMs: Long): Pair<Boolean, String>
}
