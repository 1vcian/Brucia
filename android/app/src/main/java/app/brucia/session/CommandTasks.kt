package app.brucia.session

import kotlinx.coroutines.*

/** Parallel one-shot waits owned by a feature. Cancelling drops pending replies and callbacks.
 * Calls/callbacks use the supplied scope's dispatcher (Main for UI adapters).
 */
class CommandTasks(private val scope: CoroutineScope) {
    private val jobs = mutableSetOf<Job>()
    private var generation = 0L

    @Synchronized fun run(
        runner: CommandRunner,
        command: String,
        timeoutMs: Long,
        onReply: (Boolean, String) -> Unit = { _, _ -> },
    ) {
        val owner = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val (ok, out) = try {
                runner.run(command, timeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false to (e.message ?: "Command failed.")
            }
            currentCoroutineContext().ensureActive()
            if (synchronized(this@CommandTasks) { owner != generation }) return@launch
            onReply(ok, out)
        }
        jobs += job
        job.invokeOnCompletion { synchronized(this) { jobs -= job } }
        job.start()
    }

    @Synchronized fun cancel() {
        generation++
        val pending = jobs.toList()
        jobs.clear()
        pending.forEach { it.cancel() }
    }
}
