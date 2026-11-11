package app.brucia.wardrive

import app.brucia.wifi.Handshake
import app.brucia.wifi.CapturedTarget
import app.brucia.wifi.Hc22000
import app.brucia.wifi.PmkidTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single imported Wi-Fi crack target: a handshake XOR a PMKID, plus the label shown for it.
 *
 * Before F38 the target lived in three fields (`loadedHandshake`, `loadedPmkid`, `_handshakeInfo`)
 * written non-atomically from four paths: `importPcap` (on a background thread, with no fence),
 * `importPmkid`, the `pullFile` completion and the capture `onLoaded` callback. A slow `importPcap`
 * could finish after a newer import and relabel it; an `importPcap` that found no handshake set
 * `loadedHandshake = null` but left a stale `loadedPmkid`, so Run/export kept using the old PMKID
 * while the label said "no handshake found in file". The three fields and the label could disagree.
 *
 * This store makes the target one immutable value behind a monotonic generation, with an explicit,
 * uniform policy:
 *
 *  - [current] is the single source of truth for Run and [export]; [info] is the label the screen
 *    shows. They are written together, so the label always matches what Run/export would act on.
 *  - Every target operation claims a generation with [begin]; only the holder of the current
 *    generation may [commit]. The latest operation to *begin* owns the slot, so an operation that
 *    completes late (a slow import, a late pull) is dropped instead of overwriting a newer target.
 *  - A failed or empty import commits a null target: Run/export then see nothing, so a stale target
 *    is never silently reused. (Policy: "no target" on failure, not "keep the previous target".)
 *  - General file pulls neither reserve a generation nor change the target label.
 *
 * All mutating methods and the reads of the target are synchronized on this instance; target
 * operations are infrequent user actions, so the single monitor is cheap and keeps the generation,
 * the target and the label consistent with each other.
 */
class WifiTargetStore {
    /** A handshake XOR a PMKID. A null [Target] means "no target is loaded". */
    data class Target(val handshake: Handshake? = null, val pmkid: PmkidTarget? = null)

    private var generation = 0L
    private var target: Target? = null
    private val _info = MutableStateFlow<String?>(null)

    /** The label for the current target (or the reason there is none). Null until the first import. */
    val info: StateFlow<String?> = _info.asStateFlow()

    /** Reserve the target slot for a new asynchronous operation; returns its generation token. */
    @Synchronized
    fun begin(): Long = ++generation

    /** True while [token] is still the latest reservation. */
    @Synchronized
    fun isCurrent(token: Long): Boolean = generation == token

    /**
     * Install [target] (null for a failed/empty import) and its [info] for the operation [token],
     * iff it is still the latest. Returns false if a newer operation has since claimed the slot, in
     * which case nothing is written.
     */
    @Synchronized
    fun commit(token: Long, target: Target?, info: String?): Boolean {
        if (generation != token) return false
        this.target = target
        _info.value = info
        return true
    }

    /**
     * Claim and commit atomically for a synchronous operation with no slow window (a pasted
     * target). Always wins the slot, so any import still in flight is dropped when it
     * tries to commit its older token.
     */
    @Synchronized
    fun set(target: Target?, info: String?) {
        ++generation
        this.target = target
        _info.value = info
    }

    /** Called by CaptureWorkflow at start, never at its delayed completion. */
    fun beginCapture(): (CapturedTarget) -> Boolean {
        val token = begin()
        return { captured ->
            commit(token, Target(captured.handshake, captured.pmkid),
                captured.handshake?.let { "${it.ssid} (WPA${it.keyVersion}) [pulled]" }
                    ?: "${captured.pmkid?.ssid} (PMKID) [pulled]")
        }
    }

    /** Empty/failed capture pulls clear the target; ordinary files are unrelated to selection. */
    fun beginPull(path: String): (Target?, String) -> Boolean {
        if (!isCapturePath(path)) return { _, _ -> false }
        val token = begin()
        return { target, message -> commit(token, target, message) }
    }

    companion object {
        fun isCapturePath(path: String): Boolean =
            path.endsWith(".pcap", true) || path.endsWith(".cap", true)
    }

    /** The loaded target, or null if none. */
    @Synchronized
    fun current(): Target? = target

    /** Export the loaded target to hashcat 22000, or null if nothing is loaded. */
    @Synchronized
    fun export(): String? {
        val t = target ?: return null
        t.pmkid?.let { return Hc22000.export(it) }
        t.handshake?.let { return Hc22000.export(it) }
        return null
    }
}
