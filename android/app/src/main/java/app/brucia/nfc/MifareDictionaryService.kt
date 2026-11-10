package app.brucia.nfc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MifareDictionaryState(val keys: List<String> = emptyList(), val busy: Boolean = false, val message: String = "")

/**
 * One import at a time. Clearing/cancelling fences late IO; failed loads keep previous keys.
 *
 * [load]'s reader receives a `cancelled` predicate that turns true as soon as the load is
 * no longer the current one (Cancel, Clear, ViewModel teardown). Pass it to
 * [MifareDictionary.download] / [MifareDictionary.read]: they close the connection or
 * stream so a blocked read returns. A new load starts reading only after the previous
 * reader has returned, so Cancel/retry never leaves two readers running.
 */
class MifareDictionaryService(private val scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val state = MutableStateFlow(MifareDictionaryState())
    val status = state.asStateFlow()
    @Volatile private var generation = 0L
    private var job: Job? = null

    @Synchronized fun load(label: String, read: (cancelled: () -> Boolean) -> List<String>) {
        if (state.value.busy) return
        val owner = ++generation
        val previous = job
        state.value = state.value.copy(busy = true, message = "Loading $label...")
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            val cancelled = { owner != generation || !self.isActive }
            try {
                // A cancelled reader is already being closed; wait for it to return first.
                previous?.join()
                val keys = withContext(io) { read(cancelled) }
                ensureActive()
                complete(owner, keys, label)
            } catch (_: CancellationException) {
                fail(owner, "Dictionary load cancelled")
            } catch (e: Exception) {
                fail(owner, e.message ?: "Dictionary load failed")
            }
        }
        job = next
        next.start()
    }

    @Synchronized private fun complete(owner: Long, keys: List<String>, label: String) {
        if (owner != generation) return
        val merged = (state.value.keys + keys).distinct()
        if (merged.isEmpty() || merged.size > MifareDictionary.MAX_KEYS) {
            fail(owner, "Dictionary is empty or exceeds ${MifareDictionary.MAX_KEYS} keys")
            return
        }
        val added = merged.size - state.value.keys.size
        state.value = MifareDictionaryState(merged, message = "$label: $added new keys; ${merged.size} total")
    }

    @Synchronized private fun fail(owner: Long, message: String) {
        if (owner == generation) state.value = state.value.copy(busy = false, message = message)
    }

    @Synchronized fun cancel() {
        generation++
        // Kept (not nulled) so the next load can join it; cancelling a finished job is a no-op.
        job?.cancel()
        state.value = state.value.copy(busy = false, message = "Dictionary load cancelled")
    }

    @Synchronized fun clear() {
        cancel()
        state.value = MifareDictionaryState(message = "Extra keys cleared; bundled keys are kept")
    }
}
