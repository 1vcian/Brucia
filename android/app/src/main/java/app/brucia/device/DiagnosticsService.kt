package app.brucia.device

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Device panel diagnostics (`info`, `free`, `uptime`, `i2c`) on a single-owner [DeviceOperation]:
 * [output] keeps the previous text while waiting and then shows the device output, success or
 * not; a newer request supersedes a pending one. Other commands are rejected locally because the
 * timeout is derived from these handlers only.
 */
class DiagnosticsService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    val output: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy

    fun run(cmd: String) = op.start(cmd, null, { if (cmd in COMMANDS) null else "Unsupported diagnostic: $cmd" }) { _, out -> out }

    /** Cancel the pending request; its late reply is ignored. */
    fun stop() = op.stop()

    companion object {
        val COMMANDS = setOf("info", "free", "uptime", "i2c")
        /**
         * info/free/uptime print fixed fields and return; i2c probes 126 addresses, each bounded by
         * the Arduino Wire timeout (50 ms default), so about 6.3 s worst case.
         */
        const val TIMEOUT_MS = 20_000L
    }
}
