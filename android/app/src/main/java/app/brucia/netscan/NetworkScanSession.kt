package app.brucia.netscan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Network Scan page shows: hosts found so far (sorted by last octet) and progress. */
data class NetworkScanState(
    val hosts: List<ScanHost> = emptyList(),
    val scanning: Boolean = false,
    val progress: Int = 0,
    val total: Int = 254,
)

/** Runs one subnet scan: (hosts, onProgress(done, total), onHost) -> returns when finished. */
typealias SubnetScanner = suspend (hosts: List<String>, onProgress: (Int, Int) -> Unit, onHost: (ScanHost) -> Unit) -> Unit

/**
 * Network Scan state and its running job, owned outside the composition (see
 * [NetworkScanViewModel]) so a rotation, resize or fold neither loses the results nor restarts or
 * duplicates the scan. Callbacks arrive from IO threads; state updates are atomic and tagged with
 * the run they belong to, so a stopped run reporting late never touches a newer one.
 */
class NetworkScanSession(
    private val scope: CoroutineScope,
    private val scanner: SubnetScanner = { hosts, onProgress, onHost ->
        NetworkScanner.scan(hosts = hosts, onProgress = onProgress, onHost = onHost)
    },
) {
    private val _state = MutableStateFlow(NetworkScanState())
    val state: StateFlow<NetworkScanState> = _state.asStateFlow()

    private val lock = Any()
    private var job: Job? = null
    private var run = 0

    val isRunning: Boolean get() = synchronized(lock) { job?.isActive == true }

    /** Starts a scan of [hosts] (see WifiLanSelector.scanPlan); false (and no change) while one is running. */
    fun start(hosts: List<String>): Boolean = synchronized(lock) {
        if (job?.isActive == true) return false
        val id = ++run
        _state.value = NetworkScanState(scanning = true, total = hosts.size)
        job = scope.launch {
            try {
                scanner(
                    hosts,
                    { done, total -> updateIf(id) { it.copy(progress = done, total = total) } },
                    { host -> updateIf(id) { s -> s.copy(hosts = (s.hosts + host).sortedBy { ipKey(it.ip) }) } },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what was found; a failing scan must not take the app down from viewModelScope.
                app.brucia.BruciaLog.w("NetScan", "scan failed", e)
            } finally {
                updateIf(id) { it.copy(scanning = false) }
            }
        }
        true
    }

    /** Stops the running scan; hosts found so far stay listed. */
    fun stop() = synchronized(lock) {
        job?.cancel()
        run++ // late callbacks of the cancelled run are ignored from here on
        _state.update { it.copy(scanning = false) }
    }

    /** Stops any scan and forgets the results (the user left the tool). */
    fun reset() = synchronized(lock) {
        stop()
        _state.value = NetworkScanState()
    }

    private inline fun updateIf(id: Int, crossinline change: (NetworkScanState) -> NetworkScanState) {
        synchronized(lock) { if (id == run) _state.update(change) }
    }

    companion object {
        /** Sort key of a host: its last octet. */
        fun ipKey(ip: String): Int = ip.substringAfterLast('.').toIntOrNull() ?: 0
    }
}

/** Activity-scoped owner of the [NetworkScanSession]: survives configuration changes. */
class NetworkScanViewModel : ViewModel() {
    val session = NetworkScanSession(viewModelScope)
}
