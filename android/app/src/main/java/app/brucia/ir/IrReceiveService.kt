package app.brucia.ir

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * IR receive (`ir_rx`, ~10 s firmware window) for two independent flows, each on its own
 * [DeviceOperation] so neither supersedes the other: a one-shot receive held in [rxResult]
 * for review, and a capture handed to [onKeyCaptured] for the custom remote. Only the
 * request that still owns its flow may set [rxResult] or call [onKeyCaptured].
 * A third independent line, [learn], runs the legacy CLI `ir rx` (same ~10 s loop_headless
 * window, .ir file output) and hands every parsed button to [onLearned].
 */
class IrReceiveService(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
    private val onLearned: (List<IrButton>) -> Unit = {},
    private val onKeyCaptured: (IrButton) -> Unit,
) {
    private val lock = Any()
    private var rxToken = 0L
    private var captureToken = 0L
    private val rx = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    private val capture = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    private val learning = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)

    val rxStatus: StateFlow<String> = rx.status
    val rxBusy: StateFlow<Boolean> = rx.busy
    private val _rxResult = MutableStateFlow<IrButton?>(null)
    /** The last decoded receive, held until [markSaved] or the next [receiveOnce]. */
    val rxResult: StateFlow<IrButton?> = _rxResult.asStateFlow()
    val captureStatus: StateFlow<String> = capture.status
    val captureBusy: StateFlow<Boolean> = capture.busy
    val learnBusy: StateFlow<Boolean> = learning.busy

    /** Arm the receiver once; clears [rxResult] and supersedes a pending receive. */
    fun receiveOnce() = synchronized(lock) {
        val token = ++rxToken
        _rxResult.value = null
        rx.start(CMD, RX_RUNNING) { ok, out ->
            if (!ok) return@start out.ifBlank { "Receive failed." }
            val parsed = (IrParser.parse(out).firstOrNull() ?: IrParser.parseRxSummary(out))
            synchronized(lock) { if (token == rxToken) _rxResult.value = parsed }
            when {
                parsed != null -> "Received: ${parsed.name} (${if (parsed.raw) "raw" else parsed.protocol})"
                out.isNotBlank() -> out
                else -> "No signal decoded."
            }
        }
    }

    /**
     * The VM persisted [button]: drop the held result and show "Saved: <name>" (works offline).
     * Supersedes a pending receive, whose late reply is then ignored.
     */
    fun markSaved(button: IrButton) = synchronized(lock) {
        ++rxToken
        _rxResult.value = null
        rx.show("Saved: ${button.name}")
    }

    /** Show a local note on the capture line (e.g. "Sent: <key>"), superseding a pending capture. */
    fun noteCapture(text: String) = synchronized(lock) {
        ++captureToken
        capture.show(text)
    }

    /** Capture one signal and hand it, named [label] (or its parsed name), to [onKeyCaptured]. */
    fun captureKey(label: String) = synchronized(lock) {
        val token = ++captureToken
        capture.start(CMD, CAPTURE_RUNNING) { ok, out ->
            if (!ok) return@start out.ifBlank { "Capture failed." }
            val parsed = (IrParser.parse(out).firstOrNull() ?: IrParser.parseRxSummary(out)) ?: return@start "No signal decoded - try again."
            val named = parsed.copy(name = label.trim().ifBlank { parsed.name })
            synchronized(lock) {
                if (token != captureToken) return@start null
                onKeyCaptured(named)
            }
            "Saved: ${named.name}"
        }
    }

    /**
     * Learn a remote via the CLI `ir rx`: the owning reply's parsed buttons (success or not, as
     * the legacy callback did; an error output parses to nothing) go to [onLearned] when any.
     */
    fun learn() = learning.start(LEARN_CMD, null) { _, out ->
        val parsed = IrParser.parse(out)
        if (parsed.isNotEmpty()) onLearned(parsed)
        null
    }

    /** Cancel a pending learn; its late reply never reaches [onLearned]. */
    fun stopLearn() = learning.stop()

    /** Cancel a pending receive; its late reply never sets [rxResult]. */
    fun stopReceive() = synchronized(lock) { ++rxToken; rx.stop() }

    /** Cancel a pending capture; its late reply never reaches [onKeyCaptured]. */
    fun stopCapture() = synchronized(lock) { ++captureToken; capture.stop() }

    companion object {
        const val CMD = "ir_rx"
        /** Legacy CLI receive; IrRead::loop_headless(10), the same window as [CMD]. */
        const val LEARN_CMD = "ir rx"
        /** Firmware listens ~10 s (loop_headless(10)); margin for the ACK. */
        const val TIMEOUT_MS = 20_000L
        const val RX_RUNNING = "Receiving - point a remote at the device and press a button (10s)..."
        const val CAPTURE_RUNNING = "Point a remote at the device and press the button (10s)..."
    }
}
