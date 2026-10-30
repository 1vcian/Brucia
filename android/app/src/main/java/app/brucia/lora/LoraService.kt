package app.brucia.lora

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import app.brucia.session.JsonCommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * LoRa radio commands (lora_config / lora_send / lora_recv) on one single-owner [DeviceOperation],
 * lifted from WardriveViewModel. [receive] runs via [JsonCommandRunner] because its ACK may carry a
 * packet; each received packet data object is handed to [onPacket]. A receive token guards the
 * side-effects the status text cannot: only the request that still owns the receive may toggle
 * [receiving] or call [onPacket], so a superseded reply never clears a newer flag nor re-delivers.
 */
class LoraService(
    scope: CoroutineScope,
    runner: CommandRunner,
    private val json: JsonCommandRunner,
    private val isConnected: () -> Boolean,
    private val onPacket: (JSONObject) -> Unit,
) {
    private val op = DeviceOperation(scope, runner, isConnected, CONFIG_TIMEOUT_MS)
    /** Progress or result text (VM loraStatus). */
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy
    private val _receiving = MutableStateFlow(false)
    /** True only while a receive window is open on the UI (cleared by [stopReceive] or the ACK). */
    val receiving: StateFlow<Boolean> = _receiving.asStateFlow()

    private val lock = Any()
    private var recvToken = 0L

    private fun loraErrMessage(out: String) = out.ifBlank { "No LoRa module on this device." }

    /**
     * Apply the LoRa radio configuration (lora_config <freqMHz> [bw] [sf] [cr]); only trailing blank
     * optional args are dropped so bw/sf/cr keep their positions.
     */
    fun configure(freqMHz: String, bw: String = "", sf: String = "", cr: String = "") {
        val freq = freqMHz.trim()
        val tail = listOf(bw, sf, cr).map { it.trim() }.dropLastWhile { it.isEmpty() }
        val cmd = ("lora_config " + (listOf(freq) + tail).joinToString(" ")).trim()
        op.start(cmd, "Applying LoRa config...", { if (freq.isEmpty()) "Enter a frequency in MHz." else null }, CONFIG_TIMEOUT_MS) { ok, out ->
            if (ok) out.ifBlank { "LoRa configured." } else loraErrMessage(out)
        }
    }

    /** Transmit one LoRa message (lora_send <text>). */
    fun send(text: String) {
        val msg = text.trim()
        op.start("lora_send $msg", "Sending...", { if (msg.isEmpty()) "Enter a message to send." else null }, SEND_TIMEOUT_MS) { ok, out ->
            if (ok) out.ifBlank { "Sent." } else loraErrMessage(out)
        }
    }

    /**
     * Listen for LoRa packets for [seconds] (lora_recv <secs>). [receiving] is true while waiting
     * and false at the end (owning request only); on ok a packet in the ACK data goes to [onPacket]
     * exactly once (owning request only).
     */
    fun receive(seconds: Int = 20) = synchronized(lock) {
        val t = ++recvToken
        // Mirror the VM: only arm the indicator once the connection check has passed.
        if (isConnected()) _receiving.value = true
        op.startJson(json, "lora_recv $seconds", "Listening for LoRa packets (${seconds}s)...", timeoutMs = recvTimeoutFor(seconds)) { ok, d ->
            synchronized(lock) {
                if (t == recvToken) {
                    _receiving.value = false
                    if (ok) d?.optJSONObject("data")?.let(onPacket)
                }
            }
            if (ok) d?.optString("out").orEmpty().ifBlank { "Receive complete." }
            else loraErrMessage(d?.optString("out").orEmpty())
        }
    }

    /**
     * Clear the receive indicator locally without stopping the wait (VM stopLoraRecv parity): the
     * device keeps its listen window, so the in-flight receive still delivers its final packet and
     * status. Stopping it would drop a packet the VM shows and set "Stopped.", which the VM never does.
     */
    fun stopReceive() = synchronized(lock) { _receiving.value = false }

    /** Hard stop: cancel any in-flight command and clear the receive indicator. */
    fun stop() = synchronized(lock) {
        ++recvToken
        _receiving.value = false
        op.stop()
    }

    companion object {
        /** lora_config re-initializes the radio synchronously (no timed loop). */
        const val CONFIG_TIMEOUT_MS = 10_000L
        /** lora_send waits for the airtime of up to 250 bytes, which at a user-set narrow BW/SF12 can be minutes. */
        const val SEND_TIMEOUT_MS = 650_000L

        /** Firmware runLoraRecv listens secs*1000 (clamped to 120s max); margin covers the final ACK. */
        const val RECV_MARGIN_MS = 10_000L
        fun recvTimeoutFor(secs: Int): Long = secs.coerceAtLeast(0) * 1000L + RECV_MARGIN_MS
    }
}
