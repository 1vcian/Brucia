package app.brucia.ble

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * GATT exploration control (ble_gatt_connect / ble_gatt_read): the connect runs on one
 * single-owner [DeviceOperation] (a new [connect] or [stop] supersedes the in-flight connect),
 * while each [read] is sent alongside so reads never take over the connect wait or one another.
 * Discovered services arrive as EVENT ble.gatt into a ViewModel-owned tree; [onReset] lets that
 * tree be cleared exactly when this service clears its own state (at [connect] and [stop]). A read
 * reports into [reads] under a generation token bumped by [connect] and [stop], so a read that
 * returns after a reset cannot repopulate the cleared map.
 * Timeouts: gattOpen tries public then random with an 8 s connect timeout each (bluetooth.cpp:102,
 * two attempts), then discovers services with no overall firmware bound, so the wait stays at the
 * conservative [TIMEOUT_MS] (the ViewModel's original dispatch wait).
 */
class GattService(
    private val scope: CoroutineScope,
    private val runner: CommandRunner,
    private val isConnected: () -> Boolean,
    private val onReset: () -> Unit,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    val status: StateFlow<String> = op.status
    /** True only while a connect awaits its reply. */
    val connecting: StateFlow<Boolean> = op.busy
    private val _mac = MutableStateFlow<String?>(null)
    /** The MAC of the current exploration, null when idle. */
    val mac: StateFlow<String?> = _mac.asStateFlow()
    private val _reads = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Per-characteristic read results, keyed by UUID. */
    val reads: StateFlow<Map<String, String>> = _reads.asStateFlow()
    /** Read token, guarded by [readLock]: a read that returns after a reset is dropped. */
    private val readLock = Any()
    private var readGen = 0L

    /** Connect to [mac] and discover its services; clears reads and the EVENT tree first. */
    fun connect(mac: String) {
        if (!isConnected()) { op.show(DeviceOperation.NOT_CONNECTED); return }
        _mac.value = mac
        resetReads()
        onReset()
        op.start("ble_gatt_connect $mac", "Connecting to $mac...") { ok, out ->
            if (ok) out.ifBlank { "Connected - services below." }
            else out.ifBlank { "Connect failed." }
        }
    }

    /** Forget the current exploration: clear mac, reads, status and the EVENT tree (on screen leave). */
    fun stop() {
        _mac.value = null
        resetReads()
        onReset()
        op.show("")
    }

    /** Read one characteristic alongside the connect; the result lands in [reads] under [uuid]. */
    fun read(mac: String, uuid: String) {
        if (!isConnected()) { op.show(DeviceOperation.NOT_CONNECTED); return }
        val token = synchronized(readLock) {
            _reads.value = _reads.value + (uuid to "reading...")
            readGen
        }
        scope.launch {
            val (ok, out) = try {
                runner.run("ble_gatt_read $mac $uuid", TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false to (e.message ?: "")
            }
            synchronized(readLock) {
                if (token != readGen) return@launch
                val text = if (ok) out.ifBlank { "(empty)" } else "read failed: ${out.ifBlank { "error" }}"
                _reads.value = _reads.value + (uuid to text)
            }
        }
    }

    private fun resetReads() = synchronized(readLock) {
        readGen++
        _reads.value = emptyMap()
    }

    companion object {
        const val TIMEOUT_MS = 650_000L
    }
}
