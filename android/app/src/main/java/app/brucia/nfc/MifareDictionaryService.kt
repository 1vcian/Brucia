package app.brucia.nfc

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MifareDictionaryState(val keys: List<String> = emptyList(), val busy: Boolean = false, val message: String = "")

/** One import at a time. Clearing/cancelling fences late IO; failed loads keep previous keys. */
class MifareDictionaryService(private val scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val state = MutableStateFlow(MifareDictionaryState())
    val status = state.asStateFlow()
    private var generation = 0L
    private var job: Job? = null

    @Synchronized fun load(label: String, read: () -> List<String>) {
        if (state.value.busy) return
        val owner = ++generation
        state.value = state.value.copy(busy = true, message = "Loading $label...")
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val keys = withContext(io) { read() }
                ensureActive()
                complete(owner, keys, label)
            } catch (_: CancellationException) {
                fail(owner, "Dictionary load cancelled")
            } catch (e: Exception) {
                fail(owner, e.message ?: "Dictionary load failed")
            }
        }.also { it.start() }
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
        job?.cancel()
        job = null
        state.value = state.value.copy(busy = false, message = "Dictionary load cancelled")
    }

    @Synchronized fun clear() {
        cancel()
        state.value = MifareDictionaryState(message = "Extra keys cleared; bundled keys are kept")
    }
}
