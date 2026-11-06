package app.brucia.lora

import app.brucia.session.CommandRunner
import app.brucia.session.CommandTasks
import app.brucia.session.DeviceOperation
import app.brucia.session.JsonCommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
    private val scope: CoroutineScope,
    runner: CommandRunner,
    private val json: JsonCommandRunner,
    private val isConnected: () -> Boolean,
    /** Carries the fire-and-forget `stop` frame (firmware polls it mid-receive and never ACKs it). */
    private val stopRunner: CommandRunner = runner,
    private val onPacket: (JSONObject) -> Unit,
) {
    private val op = DeviceOperation(scope, runner, isConnected, CONFIG_TIMEOUT_MS)
    private val stops = CommandTasks(scope)
    /** Progress or result text (VM loraStatus). */
    val status: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy
    private val _receiving = MutableStateFlow(false)
    /** True only while a receive window is open on the UI (cleared by [stopReceive] or the ACK). */
    val receiving: StateFlow<Boolean> = _receiving.asStateFlow()

    private val lock = Any()
    private var recvToken = 0L
    /** True from a receive-stop until its stop frame is written; blocks a new receive (F32). */
    private var stopInFlight = false

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            synchronized(lock) { ++recvToken; _receiving.value = false }
        }
    }

    private fun loraErrMessage(out: String) = out.ifBlank { "No LoRa module on this device." }

    /**
     * Apply the LoRa radio configuration (lora_config <freqMHz> [bw] [sf] [cr]); only trailing blank
     * optional args are dropped so bw/sf/cr keep their positions.
     */
    fun configure(freqMHz: String, bw: String = "", sf: String = "", cr: String = "") = synchronized(lock) {
        ++recvToken
        _receiving.value = false
        val freq = freqMHz.trim()
        val tail = listOf(bw, sf, cr).map { it.trim() }.dropLastWhile { it.isEmpty() }
        val cmd = ("lora_config " + (listOf(freq) + tail).joinToString(" ")).trim()
        op.start(cmd, "Applying LoRa config...", { if (freq.isEmpty()) "Enter a frequency in MHz." else null }, CONFIG_TIMEOUT_MS) { ok, out ->
            if (ok) out.ifBlank { "LoRa configured." } else loraErrMessage(out)
        }
    }

    /** Transmit one LoRa message (lora_send <text>). */
    fun send(text: String) = synchronized(lock) {
        ++recvToken
        _receiving.value = false
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
        if (stopInFlight) return@synchronized // a prior receive-stop is still unwritten (F32)
        val t = ++recvToken
        _receiving.value = isConnected() && scope.isActive && seconds in 1..120
        op.startJson(json, "lora_recv $seconds", "Listening for LoRa packets (${seconds}s)...",
            precheck = { if (seconds !in 1..120) "Duration must be 1–120 seconds." else null },
            timeoutMs = recvTimeoutFor(seconds)) { ok, d ->
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
     * End a receive: send the generic `stop` the firmware polls during lora_recv (so the device
     * leaves the listen window at once and answers PINGs again, instead of staying blocked up to
     * 120 s and tripping the app's link watchdog), then cancel the local wait. Older firmware
     * without the poll still ends at its bounded window.
     */
    fun stopReceive() {
        sendReceiveStop()
        stop()
    }

    /**
     * Global Stop's first phase: send the polled `stop` but keep waiting, so [receiving] clears
     * only when the device's lora_recv reply proves the listen window ended (then other
     * services' stops cannot be taken for this one). No receive, no link: nothing is sent.
     */
    fun requestStopReceive() = sendReceiveStop()

    /** Send the polled receive-stop once, fencing a new receive until the frame is written (F32). */
    private fun sendReceiveStop() {
        synchronized(lock) {
            if (!_receiving.value || !isConnected() || stopInFlight) return
            stopInFlight = true
        }
        stops.run(stopRunner, STOP_CMD, STOP_TIMEOUT_MS) { _, _ -> synchronized(lock) { stopInFlight = false } }
    }

    /** Hard stop: cancel any in-flight command and clear the receive indicator. */
    fun stop() = synchronized(lock) {
        ++recvToken
        _receiving.value = false
        op.stop()
        // stopInFlight is left to clear when the sent stop's write completes, so a user
        // stopReceive() does not cancel the stop it just sent. Link teardown uses [abandon].
    }

    /**
     * Link teardown (disconnect): like [stop], but also cancel a receive-stop not yet written and
     * lift its fence. The stop runner resolves the link when its coroutine runs, so a queued stop
     * left alive could otherwise be written to a replacement device's session (F32).
     */
    fun abandon() {
        synchronized(lock) { stopInFlight = false }
        stops.cancel()
        stop()
    }

    companion object {
        /** lora_config re-initializes the radio synchronously (no timed loop). */
        const val CONFIG_TIMEOUT_MS = 10_000L
        /** lora_send waits for the airtime of up to 250 bytes, which at a user-set narrow BW/SF12 can be minutes. */
        const val SEND_TIMEOUT_MS = 650_000L

        /** Firmware runLoraRecv listens secs*1000 (clamped to 120s max); margin covers the final ACK. */
        const val RECV_MARGIN_MS = 10_000L
        /** Generic stop frame; matches companionPollStop's "stop" rule and is always permitted. */
        const val STOP_CMD = "stop"
        /** Bounds only the fire-and-forget launch: the firmware polls the stop and never ACKs it. */
        const val STOP_TIMEOUT_MS = 10_000L
        fun recvTimeoutFor(secs: Int): Long = secs.coerceIn(1, 120).toLong() * 1000L + RECV_MARGIN_MS
    }
}
