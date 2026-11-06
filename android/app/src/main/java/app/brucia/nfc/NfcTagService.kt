package app.brucia.nfc

import app.brucia.device.CliParse
import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Basic tag actions over the Bruce CLI (`rfid read`, `rfid info`, `rfid ndef`) on one
 * [DeviceOperation]: they share the [last] result line, so the latest request wins and a
 * superseded or stopped reply never overwrites it. Only a successful read that carries a UID
 * is handed to [onTag] (uid, type, full output) for persistence.
 */
class NfcTagService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
    private val onTag: (uid: String, type: String, out: String) -> Unit,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    private val _last = MutableStateFlow<String?>(null)
    /** Output of the last answered tag command (null until one answers). */
    val last: StateFlow<String?> = _last.asStateFlow()
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy

    fun read() = run(READ) { ok, out ->
        val uid = if (ok) CliParse.field(out, "UID") else null
        if (!uid.isNullOrEmpty()) onTag(uid, CliParse.field(out, "Type") ?: "", out)
    }

    fun info() = run(INFO)

    /** WRITES an NDEF URL record (the firmware default: the Brucia project URL) onto the tag; not a read. */
    fun ndef() = run(NDEF)

    /** Cancel the pending command; its late reply is ignored. */
    fun stop() = op.stop()

    private fun run(cmd: String, after: (Boolean, String) -> Unit = { _, _ -> }) =
        op.start(cmd, null) { ok, out ->
            _last.value = out
            after(ok, out)
            null
        }

    companion object {
        const val READ = "rfid read"
        const val INFO = "rfid info"
        const val NDEF = "rfid ndef"
        /**
         * Kept at the legacy 650 s: `rfid read` polls for 5 s but each driver read() and the
         * `rfid ndef` write have no firmware bound.
         */
        const val TIMEOUT_MS = 650_000L
    }
}
