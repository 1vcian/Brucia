package app.brucia.wifi

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Device-side Evil Portal control (evil_portal_start / evil_portal_stop) on one single-owner
 * [DeviceOperation]: the device command is the single owner, so [stop] always supersedes a pending
 * [start] command. A template push is a host-side pre-step over a separate seam ([pushTemplate])
 * the single command cannot encompass, so it carries an extra start token: [stop] (or a newer
 * [start]) bumps the token and cancels the push, and the push launches the portal command only
 * while it still owns that token - so Stop while starting wins whether the start is pushing or
 * waiting. Both commands are quick (the firmware ACKs after setup()+beginAP() while the portal
 * serves in the background), so no blocking bound exists and the wait stays at [TIMEOUT_MS].
 */
class EvilPortalService(
    private val scope: CoroutineScope,
    runner: CommandRunner,
    private val isConnected: () -> Boolean,
    private val pushTemplate: suspend (path: String, bytes: ByteArray) -> Pair<Boolean, String>,
    private val onStart: () -> Unit,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy
    private val _active = MutableStateFlow(false)
    /** True once the device ACKs a start; cleared by a failed start or by [stop]. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Start token, guarded by [lock]: a superseded push never launches a stale portal command. */
    private val lock = Any()
    private var startGen = 0L
    private var pushJob: Job? = null

    /**
     * Start the portal with AP [ssid] (default "Free Wifi"), [channel] coerced to 1..14. With
     * [templateBytes] and a non-blank [templateName] the HTML is pushed to "/$templateName" first
     * and used as the captive page; a failed push shows "Template push failed: <msg>" and starts
     * nothing. [onStart] fires once per connected start (the ViewModel clears its creds there).
     */
    fun start(
        ssid: String,
        templateBytes: ByteArray? = null,
        templateName: String = "",
        channel: Int = 1,
        deauth: Boolean = false,
    ) {
        if (!isConnected()) { op.show(DeviceOperation.NOT_CONNECTED); return }
        synchronized(lock) {
            val token = ++startGen
            cancelPush()
            op.show("Starting...")
            onStart()
            val name = ssid.ifBlank { "Free Wifi" }
            val ch = channel.coerceIn(1, 14)
            if (templateBytes != null && templateName.isNotBlank()) {
                val path = "/$templateName"
                pushJob = scope.launch {
                    val (ok, msg) = try {
                        pushTemplate(path, templateBytes)
                    } catch (e: CancellationException) {
                        synchronized(lock) {
                            if (token == startGen) op.show("Template upload cancelled.")
                        }
                        throw e
                    } catch (e: Exception) {
                        false to (e.message ?: "Upload failed")
                    }
                    synchronized(lock) {
                        if (token != startGen) return@launch
                        if (ok) startCmd(token, name, path, ch, deauth)
                        else op.show("Template push failed: $msg")
                    }
                }
            } else startCmd(token, name, "", ch, deauth)
        }
    }

    /**
     * Stop the portal: supersede any pending start (command or push) and report the device ACK.
     * Only an ACK clears [active]; a rejected or unanswered stop keeps it, since the portal may
     * still be serving. Without a link nothing can be sent, but [active] still clears (stop-all
     * runs on teardown and the link loss already ended local ownership).
     */
    fun stop() {
        synchronized(lock) {
            val token = ++startGen
            cancelPush()
            if (!isConnected()) {
                _active.value = false
                op.show(DeviceOperation.NOT_CONNECTED)
                return
            }
            op.start("evil_portal_stop", null) { ok, out ->
                if (!ok) return@start "$STOP_FAILED${out.ifBlank { "no reply" }}"
                setActive(token, false)
                out.ifBlank { "Stopped." }
            }
        }
    }

    /** Drop local ownership on link loss; do not send a command to a replacement device. */
    fun abandon() = synchronized(lock) {
        ++startGen
        cancelPush()
        _active.value = false
        op.show("Disconnected")
    }

    /** Send evil_portal_start with the 4 tab-separated fields; the ACK sets [active] and [status]. */
    private fun startCmd(token: Long, name: String, tpath: String, ch: Int, deauth: Boolean) {
        val arg = "$name\t$tpath\t$ch\t${if (deauth) 1 else 0}"
        op.start("evil_portal_start $arg", null) { ok, out ->
            setActive(token, ok)
            out
        }
    }

    /** Apply [value] to [active] only while [token] still owns it (a superseded reply can outlive cancellation). */
    private fun setActive(token: Long, value: Boolean) = synchronized(lock) {
        if (token == startGen) _active.value = value
    }

    private fun cancelPush() {
        pushJob?.cancel()
        pushJob = null
    }

    companion object {
        const val TIMEOUT_MS = 650_000L
        /** Prefix of a failed stop: [active] stays set until a later stop is acknowledged. */
        const val STOP_FAILED = "Stop failed, portal may still be running: "
    }
}
