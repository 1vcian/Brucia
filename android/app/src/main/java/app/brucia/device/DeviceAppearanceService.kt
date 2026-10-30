package app.brucia.device

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Device UI theme and onboard LED control (theme_set, led_set, led_bright, led_effect,
 * config_save) on one single-owner [DeviceOperation]: the latest request wins. The device
 * applies changes live; [save] persists them. Success callbacks fire only for the request
 * that still owns the operation, so a superseded or stopped reply never reports a color.
 */
class DeviceAppearanceService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
    private val onThemeApplied: (rgb565: Int) -> Unit,
    private val onLedApplied: (rgb: Int) -> Unit,
) {
    private val op = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    /** Result text of the last request; unchanged while a request waits. */
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy
    /** Request token mirroring the operation's ownership, for the success callbacks. */
    private val request = AtomicLong()

    /** Set the device UI color from 0xRRGGBB (sent as RGB565). */
    fun setTheme(rgb: Int) {
        val rgb565 = toRgb565(rgb)
        send("theme_set %04x".format(rgb565), "Theme set.") { onThemeApplied(rgb565) }
    }

    /** Set the LED color (0xRRGGBB). */
    fun setLedColor(rgb: Int) {
        val c = rgb and 0xFFFFFF
        send("led_set %06x".format(c), "LED set.") { onLedApplied(c) }
    }

    /** Persist the current theme/LED config to the device flash. */
    fun save() {
        request.incrementAndGet()
        op.start("config_save", null) { ok, out -> if (ok) "Saved to device." else "Save failed: ${out.ifBlank { "error" }}" }
    }

    /** LED brightness, clamped to 0..100 %. */
    fun setLedBright(pct: Int) = send("led_bright ${pct.coerceIn(0, 100)}", "LED brightness set.")

    /** LED animation, clamped to 0..9 (0 = solid). */
    fun setLedEffect(effect: Int) = send("led_effect ${effect.coerceIn(0, 9)}", "LED effect set.")

    fun stop() {
        request.incrementAndGet()
        op.stop()
    }

    private fun send(cmd: String, okText: String, onOk: () -> Unit = {}) {
        val token = request.incrementAndGet()
        op.start(cmd, null) { ok, out ->
            if (ok && request.get() == token) onOk()
            out.ifBlank { if (ok) okText else "Failed." }
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000L

        /** 0xRRGGBB to RGB565, truncating like the device. */
        fun toRgb565(rgb: Int): Int {
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            return ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or (b shr 3)
        }
    }
}
