package app.brucia.device

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.util.TimeZone

/**
 * Device clock read / sync (clock_get, clock_set <epoch>) on a single-owner [DeviceOperation].
 * An implausible epoch is rejected locally, as the firmware would.
 *
 * Bruce keeps local wall-clock time in the system clock (src/core/utils.cpp updateClockTimezone:
 * NTP epoch + timezone + DST, then settimeofday; the menu's manual set does the same), and the
 * firmware writes the clock_set value as is. So the phone sends its local wall-clock epoch
 * ([wallClockEpoch]); a plain UTC epoch left the device showing UTC. clock_get prints that wall
 * clock with a "Z" suffix it does not have, which [deviceTimeText] drops.
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

    /** Read the device clock (local wall-clock time, ISO form). */
    fun get() = op.start("clock_get", "Reading clock...") { ok, out ->
        if (ok) deviceTimeText(out).ifBlank { "(no time)" } else out.ifBlank { "Clock read failed." }
    }

    /** Set the device clock to [epochSeconds], a wall-clock epoch (see [wallClockEpoch]). */
    fun set(epochSeconds: Long) =
        op.start("clock_set $epochSeconds", "Syncing clock...", { if (epochSeconds < MIN_EPOCH) IMPLAUSIBLE_EPOCH else null }) { ok, out ->
            if (ok) deviceTimeText(out).ifBlank { "Clock synced." } else out.ifBlank { "Clock sync failed." }
        }

    /** Set the device clock to the phone's local time now. */
    fun syncFromPhone(nowMs: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()) =
        set(wallClockEpoch(nowMs, zone))

    fun stop() = op.stop()

    companion object {
        /** Firmware sanity bound (before 2001 is rejected). */
        const val MIN_EPOCH = 1_000_000_000L
        const val IMPLAUSIBLE_EPOCH = "implausible epoch"

        /** Seconds of [zone]'s local wall clock at [utcMs], counted as if it were UTC (Bruce's clock). */
        fun wallClockEpoch(utcMs: Long, zone: TimeZone): Long = Math.floorDiv(utcMs + zone.getOffset(utcMs), 1000L)

        /** A clock_get / clock_set echo ("2026-10-30T12:00:00Z") as the local time it is. */
        fun deviceTimeText(out: String): String =
            if (Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""").matches(out.trim())) out.trim().removeSuffix("Z").replace('T', ' ')
            else out
    }
}
