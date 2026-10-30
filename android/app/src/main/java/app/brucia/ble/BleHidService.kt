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
 * BLE HID presenter / keyboard (ble_hid_begin / _end / _status / _key / _type). The advertise
 * lifecycle ([begin], [end]) runs on one single-owner [DeviceOperation] so [end] supersedes a
 * pending [begin] and never leaves a stale "Starting..." text. Key presses, typing and status
 * refreshes are sent alongside (SpeakerService idiom) so they never strand the begin wait.
 * [status] is owned here because it starts "off" and the alongside sends update it directly; a
 * generation token keeps a superseded lifecycle reply from overwriting a newer one.
 */
class BleHidService(
    private val scope: CoroutineScope,
    private val runner: CommandRunner,
    private val isConnected: () -> Boolean,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    private val _status = MutableStateFlow("off")
    val status: StateFlow<String> = _status.asStateFlow()
    val busy: StateFlow<Boolean> = op.busy

    /** Guards lifecycle replies: a superseded begin/end never overwrites a newer one. */
    private val lifeLock = Any()
    private var life = 0L

    /** Advertise as a BLE keyboard; not connected shows the connect text, like the ViewModel. */
    fun begin() {
        if (!isConnected()) { _status.value = NOT_CONNECTED; return }
        val gen = synchronized(lifeLock) { ++life }
        _status.value = "Starting..."
        op.start("ble_hid_begin", null) { ok, out ->
            set(gen, if (ok) out.ifBlank { "advertising" } else out.ifBlank { "failed" })
            null
        }
    }

    /** Stop advertising; supersedes a pending [begin] so the reply is "off", never "Starting...". */
    fun end() {
        if (!isConnected()) return
        val gen = synchronized(lifeLock) { ++life }
        op.start("ble_hid_end", null) { _, _ -> set(gen, "off"); null }
    }

    /** Query advertise state (alongside): ok sets [status], failure keeps it. */
    fun refresh() {
        if (!isConnected()) return
        sendAlongside("ble_hid_status") { ok, out -> if (ok) _status.value = out }
    }

    /** Press a presenter key (alongside): only a failure touches [status]. */
    fun key(name: String) {
        if (!isConnected()) return
        sendAlongside("ble_hid_key $name") { ok, out -> if (!ok) _status.value = out }
    }

    /** Type text (alongside); empty text is ignored. Only a failure touches [status]. */
    fun type(text: String) {
        if (!isConnected() || text.isEmpty()) return
        sendAlongside("ble_hid_type $text") { ok, out -> if (!ok) _status.value = out }
    }

    /** Stop waiting for the current lifecycle reply; a late reply is then ignored. */
    fun stop() {
        synchronized(lifeLock) { ++life }
        op.stop()
    }

    /** Apply [text] only while [gen] is still the latest lifecycle command. */
    private fun set(gen: Long, text: String) = synchronized(lifeLock) {
        if (life == gen) _status.value = text
    }

    /** Fire-and-forget command off the operation, reporting its reply to [onReply]. */
    private fun sendAlongside(cmd: String, onReply: (Boolean, String) -> Unit) {
        scope.launch {
            val (ok, out) = try {
                runner.run(cmd, TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false to (e.message ?: "")
            }
            onReply(ok, out)
        }
    }

    companion object {
        // bluetooth.cpp ble_hid_* handlers ACK immediately (advertise, status, key press, type,
        // end all return at once, no blocking wait), so the default bounded wait is enough.
        const val TIMEOUT_MS = 10_000L
        const val NOT_CONNECTED = "Connect the device over USB first."
    }
}
