package app.brucia.mitm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** A single intercepted HTTP(S) transaction, capped in size for the live log. */
data class MitmTxn(
    val id: Long,
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

    private val ids = AtomicLong(0)
    fun nextId(): Long = ids.incrementAndGet()

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

    @Synchronized
    fun add(t: MitmTxn) {
        val next = ArrayList<MitmTxn>(_txns.value.size + 1)
        next.add(t)                 // newest first
        next.addAll(_txns.value)
        if (next.size > CAP) next.subList(CAP, next.size).clear()
        _txns.value = next
    }

    fun clear() { _txns.value = emptyList() }

    fun setRunning(v: Boolean) { _running.value = v }
    fun setPort(v: Int) { _port.value = v }
    fun setLocalIp(v: String?) { _localIp.value = v }
    fun setError(v: String?) { _error.value = v }
}
