package app.brucia.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the PCAP dissect page shows. */
data class PcapDissectState(
    val fileName: String = "",
    val loading: Boolean = false,
    val summary: PcapDissector.Summary? = null,
    val rows: List<PcapDissector.Row> = emptyList(),
    val error: String = "",
)

/**
 * PCAP dissect state and its parse job, owned outside the composition (see [PcapDissectViewModel])
 * so a rotation or resize neither loses the decoded packet list nor cancels a parse in progress.
 */
class PcapDissectSession(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(PcapDissectState())
    val state: StateFlow<PcapDissectState> = _state.asStateFlow()
    private var job: Job? = null

    /**
     * Reads (blocking, on the IO dispatcher) and dissects one capture; false while another is still
     * being parsed. [read] must not hold an Activity: it runs past configuration changes.
     */
    fun open(fileName: String, read: () -> ByteArray): Boolean {
        if (job?.isActive == true) return false
        _state.value = PcapDissectState(fileName = fileName, loading = true)
        // Lazy, so [job] names this run before its body can reach the finally below.
        val run = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            var next = _state.value
            try {
                val bytes = withContext(ioDispatcher) { read() }
                val result = withContext(cpuDispatcher) { PcapDissector.dissect(bytes) { ensureActive() } }
                next = next.copy(
                    summary = result?.summary,
                    rows = result?.rows ?: emptyList(),
                    error = if (result == null) "Not a classic pcap, or no frames found." else "",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                next = next.copy(error = "Failed to read: ${e.message}")
            } finally {
                // A blocking read cannot be interrupted: after reset() (or a newer open) this
                // run's end must not bring its file name back over the newer state.
                if (job === self) _state.value = next.copy(loading = false)
            }
        }
        job = run
        run.start()
        return true
    }

    /** Cancels a parse and forgets the capture (the user left the tool). */
    fun reset() {
        job?.cancel()
        job = null
        _state.value = PcapDissectState()
    }
}

/** Activity-scoped owner of the [PcapDissectSession]: survives configuration changes. */
class PcapDissectViewModel : ViewModel() {
    val session = PcapDissectSession(viewModelScope)
}
