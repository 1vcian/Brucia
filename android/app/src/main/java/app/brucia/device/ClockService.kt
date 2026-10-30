package app.brucia.device

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Device clock read / sync (clock_get, clock_set <epoch>) on a single-owner [DeviceOperation].
 * An implausible epoch is rejected locally, as the firmware would.
 */
class ClockService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
    timeoutMs: Long = DeviceOperation.DEFAULT_TIMEOUT_MS,
) {
    private val op = DeviceOperation(scope, runner, isConnected, timeoutMs)
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy

    /** Read the device clock (ISO time). */
    fun get() = op.start("clock_get", "Reading clock...") { ok, out ->
        if (ok) out.ifBlank { "(no time)" } else out.ifBlank { "Clock read failed." }
    }

    /** Set the device clock from a Unix epoch in seconds. */
    fun set(epochSeconds: Long) =
        op.start("clock_set $epochSeconds", "Syncing clock...", { if (epochSeconds < MIN_EPOCH) IMPLAUSIBLE_EPOCH else null }) { ok, out ->
            if (ok) out.ifBlank { "Clock synced." } else out.ifBlank { "Clock sync failed." }
        }

    fun stop() = op.stop()

    companion object {
        /** Firmware sanity bound (before 2001 is rejected). */
        const val MIN_EPOCH = 1_000_000_000L
        const val IMPLAUSIBLE_EPOCH = "implausible epoch"
    }
}
