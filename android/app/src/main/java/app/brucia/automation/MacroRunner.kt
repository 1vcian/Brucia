package app.brucia.automation

import app.brucia.session.CommandRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sequential execution of existing macros; storage remains with the caller.
 * [connect] returns a runner bound to the original connection, never a replacement link.
 * Stop cancels sequencing, not a command already executing on the firmware.
 */
class MacroRunner(
    private val scope: CoroutineScope,
    private val connect: () -> CommandRunner?,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val statusState = MutableStateFlow("")
    val status: StateFlow<String> = statusState.asStateFlow()
    private val runningState = MutableStateFlow(false)
    val running: StateFlow<Boolean> = runningState.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    /** A second start while running is ignored, including a rapid double tap. */
    @Synchronized fun start(macro: Macro) {
        if (runningState.value) return
        val owner = ++generation
        val steps = macro.steps.map { it.copy(delayMs = it.delayMs.coerceIn(0, 60_000)) }
        if (steps.isEmpty() || steps.any { it.cmd.isBlank() }) {
            statusState.value = "Add at least one step with a nonblank command."
            return
        }
        val runner = connect()
        if (runner == null) {
            statusState.value = "Connect the device over USB first."
            return
        }
        runningState.value = true
        statusState.value = "Starting macro \"${macro.name}\"..."
        job = scope.launch {
            var stepLabel = "Macro \"${macro.name}\""
            try {
                for ((index, step) in steps.withIndex()) {
                    checkOwner(owner)
                    stepLabel = "Step ${index + 1}/${steps.size}: ${step.cmd}"
                    publish(owner, stepLabel, true)
                    val (ok, out) = runner.run(step.cmd, COMMAND_TIMEOUT_MS)
                    checkOwner(owner)
                    if (!ok) {
                        publish(owner, "$stepLabel -> failed: ${out.ifBlank { "Command failed." }}", false)
                        return@launch
                    }
                    publish(owner, "$stepLabel -> ${out.ifBlank { "ok" }}", true)
                    if (step.delayMs > 0) pause(step.delayMs.toLong())
                    checkOwner(owner)
                }
                publish(owner, "Macro \"${macro.name}\" finished.", false)
            } catch (_: TimeoutCancellationException) {
                publish(owner, "$stepLabel -> failed: Command timed out.", false)
            } catch (e: CancellationException) {
                publish(owner, "Macro \"${macro.name}\" cancelled.", false)
                throw e
            } catch (e: Exception) {
                publish(owner, "$stepLabel -> failed: ${e.message ?: "Command failed."}", false)
            }
        }.also { runningJob ->
            // A scope cancelled before launch never enters try/catch.
            runningJob.invokeOnCompletion {
                if (it is CancellationException) publish(owner, "Macro \"${macro.name}\" cancelled.", false)
            }
        }
    }

    @Synchronized fun stop(message: String = "Macro stopped; a command already sent may still be running.") {
        generation++
        job?.cancel()
        job = null
        if (runningState.value) {
            statusState.value = message
            runningState.value = false
        }
    }

    private suspend fun checkOwner(owner: Long) {
        currentCoroutineContext().ensureActive()
        synchronized(this) { if (owner != generation) throw CancellationException() }
    }

    @Synchronized private fun publish(owner: Long, message: String, running: Boolean) {
        if (owner != generation) return
        statusState.value = message
        runningState.value = running
    }

    companion object {
        // Raw macros can contain long commands. Match generic command dispatch's wait,
        // rather than the 12 s default used for short radio bursts. ERR/timeout is terminal.
        const val COMMAND_TIMEOUT_MS = 650_000L
    }
}
