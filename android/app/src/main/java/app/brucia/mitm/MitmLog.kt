package app.brucia.mitm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A single intercepted HTTP(S) transaction, capped in size for the live log. [id] is assigned by
 * [MitmLog.add] when the entry is added (the value given here is ignored), so every entry in the
 * log has its own id: the request list uses it as the row key.
 */
data class MitmTxn(
    val id: Long = 0,
    val timeMs: Long,
    val method: String,
    val scheme: String,          // "http" or "https"
    val host: String,
    val path: String,
    val status: Int,             // -1 until a response is seen
    val reqBytes: Int,
    val respBytes: Int,
    val durationMs: Long,
    val decrypted: Boolean,      // true = TLS was terminated and content logged
    val note: String,            // e.g. error, or "CONNECT tunnel (not decrypted)"
    val reqHeaders: String,
    val respHeaders: String,
    val reqPreview: String,
    val respPreview: String,
) {
    val url: String get() = "$scheme://$host$path"
}

/**
 * Shared, in-memory, capped log of intercepted transactions plus proxy run state.
 * Observed by the Compose UI; written by [MitmProxy].
 */
object MitmLog {

    private const val CAP = 300

    private var lastId = 0L

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _port = MutableStateFlow(8080)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _localIp = MutableStateFlow<String?>(null)
    val localIp: StateFlow<String?> = _localIp.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _txns = MutableStateFlow<List<MitmTxn>>(emptyList())
    val txns: StateFlow<List<MitmTxn>> = _txns.asStateFlow()

    /**
     * Adds [t] at the top with a fresh id and returns the stored entry. The id is taken only once
     * the entry is in the log, so ids are unique and consecutive; they keep counting after [clear].
     */
    @Synchronized
    fun add(t: MitmTxn): MitmTxn {
        val entry = t.copy(id = lastId + 1)
        val next = ArrayList<MitmTxn>(_txns.value.size + 1)
        next.add(entry)             // newest first
        next.addAll(_txns.value)
        if (next.size > CAP) next.subList(CAP, next.size).clear()
        _txns.value = next
        lastId = entry.id
        return entry
    }

    @Synchronized
    fun clear() { _txns.value = emptyList() }

    fun setRunning(v: Boolean) { _running.value = v }
    fun setPort(v: Int) { _port.value = v }
    fun setLocalIp(v: String?) { _localIp.value = v }
    fun setError(v: String?) { _error.value = v }
}

/**
 * The log line of one proxied request: at most one entry. The exchange calls [log] once the
 * response has been relayed; when [run]'s block throws, the failure entry is logged only if
 * nothing was logged yet, so a failure after the entry was added (closing the upstream socket,
 * for example) does not add a second line for the same request.
 */
internal class RequestLog {
    var logged = false
        private set

    fun log(txn: MitmTxn) {
        if (logged) return
        MitmLog.add(txn)
        logged = true
    }

    inline fun <T> run(failed: (Exception) -> MitmTxn, onFailure: T, block: (RequestLog) -> T): T =
        try {
            block(this)
        } catch (e: Exception) {
            if (!logged) log(failed(e))
            onFailure
        }
}
