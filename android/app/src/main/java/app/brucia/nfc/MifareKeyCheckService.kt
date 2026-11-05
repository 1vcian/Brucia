package app.brucia.nfc

import app.brucia.session.CommandRunner
import app.brucia.session.JsonCommandRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.IOException

data class MifareFound(val sector: Int, val type: String, val key: String)
data class MifareAttackState(
    val running: Boolean = false,
    val batch: Int = 0,
    val totalBatches: Int = 0,
    val found: List<MifareFound> = emptyList(),
    val message: String = "",
    val completed: Boolean = false,
    // Card UID read by the device at batch start (v1 firmware); "" when unknown/old firmware.
    val uid: String = "",
    // True only when every batch reported the v1 completeness fields AND each was fully tested.
    val exhaustive: Boolean = false,
    // Whether the firmware reported the v1 complete/partial contract at all.
    val completenessKnown: Boolean = false,
    // A batch was cut short (card lost), the card was swapped between batches, or the user stopped.
    val partial: Boolean = false,
)

/** Existing dictionary batches. [connect] must return a runner pinned to one connection.
 *
 * Stopping a running sequence: when the device advertises `features.mifare_keycheck_stop`
 * ([stopSupported]) and a [stopSender] is wired, [stop] sends a direct, unlogged stop frame that
 * the firmware polls between authenticate() calls, so the batch already running on the device ends
 * within one attempt and replies `complete:false, stop:"cancelled"` with the hits found so far. The
 * sequence then finalizes "Stopped (partial)" from that reply. The app waits a bounded time
 * ([STOP_WAIT_MS]) for it; if nothing arrives (e.g. a dead link) it force-finalizes partial and
 * drops any late reply. Without the CAPS flag (old firmware) [stop] falls back to the old behavior:
 * it only stops app-side sequencing; the in-flight device batch cannot be cancelled.
 *
 * v1 firmware ACKs carry the tag UID and an explicit complete/partial flag; when those fields are
 * absent (old firmware) the sequence runs but is labeled "completeness unknown".
 */
class MifareKeyCheckService(
    private val scope: CoroutineScope,
    private val connect: () -> JsonCommandRunner?,
    private val stopSupported: () -> Boolean = { false },
    private val stopSender: CommandRunner? = null,
    // Bounded wait for the firmware's `cancelled` ACK after a stop frame; overridable for tests.
    private val stopWaitMs: Long = STOP_WAIT_MS,
) {
    private val state = MutableStateFlow(MifareAttackState())
    val status: StateFlow<MifareAttackState> = state.asStateFlow()
    private var epoch = 0L
    private var job: Job? = null
    // Cooperative-stop flag for the running batch loop; reset on every start/reset/hard-stop.
    @Volatile private var stopRequested = false

    @Synchronized fun reset() {
        epoch++
        stopRequested = false
        job?.cancel()
        state.value = MifareAttackState()
    }

    /**
     * User Stop / Stop all. When the device supports an in-flight stop, send the stop frame and wait
     * a bounded time for the firmware's `cancelled` reply, finalizing "Stopped (partial)"; otherwise
     * fall back to stopping app-side sequencing only (the current device batch may still run).
     */
    @Synchronized fun stop() {
        if (!state.value.running) return
        val sender = stopSender
        if (stopSupported() && sender != null) {
            if (stopRequested) return // already stopping; keep waiting for the cancelled reply
            stopRequested = true
            val owner = epoch
            state.value = state.value.copy(
                message = "Stopping... (waiting for the device to finish the current batch)"
            )
            // Direct, unlogged stop frame: the firmware polls it mid-batch and never ACKs it, so the
            // final reply still arrives as the in-flight batch's own `cancelled` ACK.
            scope.launch {
                try { sender.run(STOP_CMD, STOP_FRAME_TIMEOUT_MS) } catch (_: Exception) {}
            }
            // Bound the wait: if the cancelled ACK never lands, force a partial stop and drop it.
            scope.launch {
                delay(stopWaitMs)
                forceStopPartial(owner)
            }
        } else {
            hardStop("Batches stopped; the current device batch may still be running.")
        }
    }

    /** Disconnect / teardown: abandon the sequence immediately, send no frame (the link is gone),
     *  and drop any late reply. */
    @Synchronized fun stop(message: String) = hardStop(message)

    @Synchronized private fun hardStop(message: String) {
        epoch++
        stopRequested = false
        job?.cancel()
        if (state.value.running) state.value = state.value.copy(running = false, message = message)
    }

    /** Bounded-wait fallback: no cancelled reply arrived, so finalize partial with hits so far. */
    @Synchronized private fun forceStopPartial(owner: Long) {
        if (epoch != owner || !state.value.running) return
        epoch++ // supersede: any late batch reply is now dropped by checkOwner/update
        stopRequested = false
        job?.cancel()
        state.value = state.value.copy(
            running = false, completed = false, partial = true,
            message = "Stopped (partial) - ${state.value.found.size} key(s) found so far."
        )
    }

    @Synchronized fun start(keys: List<String>) {
        epoch++
        stopRequested = false
        val owner = epoch
        job?.cancel()
        val dictionary = keys.map { it.trim().uppercase() }.distinct()
        if (dictionary.isEmpty() || dictionary.any { !KEY.matches(it) }) {
            state.value = MifareAttackState(message = if (dictionary.isEmpty()) "No keys in dictionary." else "Keys must contain exactly 12 hex digits.")
            return
        }
        val runner = connect()
        if (runner == null) {
            state.value = MifareAttackState(message = "Connect the device over USB first.")
            return
        }
        val batches = dictionary.chunked(48)
        state.value = MifareAttackState(running = true, totalBatches = batches.size, message = "Starting (${dictionary.size} keys)...")
        job = scope.launch {
            try {
                val found = linkedMapOf<Pair<Int, String>, MifareFound>()
                var sequenceUid: String? = null
                var anyUnknown = false
                var allComplete = true
                // Finalize the sequence as a user-stopped partial, committing the hits so far.
                fun finalizeStopped() {
                    val uidNow = sequenceUid
                    update(owner) {
                        it.copy(running = false, completed = false, partial = true, exhaustive = false,
                            uid = uidNow ?: it.uid, found = found.values.toList(),
                            message = "Stopped (partial) - ${found.size} key(s) found so far.")
                    }
                    stopRequested = false
                }
                for ((index, batch) in batches.withIndex()) {
                    checkOwner(owner)
                    // Stop requested between batches: the device is idle, so finalize now.
                    if (stopRequested) { finalizeStopped(); return@launch }
                    update(owner) { it.copy(batch = index + 1, message = "Testing batch ${index + 1}/${batches.size}...") }
                    val (ok, reply) = runner.runJson("mifare_keycheck ${batch.joinToString(",")}", BATCH_TIMEOUT_MS)
                    checkOwner(owner)
                    if (!ok) throw IOException(reply?.optString("out")?.takeIf { it.isNotBlank() }
                        ?: reply?.optString("msg")?.takeIf { it.isNotBlank() } ?: "Key check failed.")
                    val meta = parseMeta(reply)
                    // Reject a swapped card before committing a different card's keys.
                    if (meta.completenessKnown && meta.uid.isNotEmpty()) {
                        if (sequenceUid == null) sequenceUid = meta.uid
                        else if (sequenceUid != meta.uid) {
                            val stopUid = sequenceUid
                            update(owner) {
                                it.copy(running = false, completed = false, partial = true,
                                    completenessKnown = true, exhaustive = false, uid = stopUid,
                                    message = "Card changed between batches (UID $stopUid -> ${meta.uid}); stopped. ${found.size} key(s) found so far.")
                            }
                            return@launch
                        }
                    }
                    val hits = parse(reply, batch)
                    // Validate the whole reply before committing any of its results.
                    for (hit in hits) found[hit.sector to hit.type] = hit
                    if (!meta.completenessKnown) anyUnknown = true
                    if (!meta.complete) allComplete = false
                    val knownSoFar = meta.completenessKnown
                    val uidSoFar = sequenceUid
                    update(owner) { it.copy(found = found.values.toList(), uid = uidSoFar ?: it.uid,
                        completenessKnown = it.completenessKnown || knownSoFar) }
                    // The firmware cancelled this batch on our stop frame (or we asked to stop): its
                    // remaining sectors/keys were never tested. Commit the hits above, then stop.
                    if (meta.cancelled || stopRequested) { finalizeStopped(); return@launch }
                    // A batch cut short (card lost) means its remaining sectors/keys were never
                    // tested; stop and report the sequence as partial rather than continuing.
                    if (meta.completenessKnown && !meta.complete) {
                        val uidNow = sequenceUid
                        update(owner) {
                            it.copy(running = false, completed = false, partial = true,
                                completenessKnown = true, exhaustive = false, uid = uidNow ?: "",
                                message = "Partial scan - ${found.size} key(s) found; card lost before all sectors were tested.")
                        }
                        return@launch
                    }
                }
                val known = !anyUnknown
                val exhaustive = known && allComplete
                val count = found.size
                val msg = when {
                    !known -> if (count == 0) "No keys reported (completeness unknown; update firmware)."
                              else "Done - $count key(s) reported (completeness unknown)."
                    count == 0 -> "No keys found; all sectors tested."
                    else -> "Done - $count key(s); all sectors tested."
                }
                val uidFinal = sequenceUid
                update(owner) { it.copy(running = false, completed = true, exhaustive = exhaustive,
                    completenessKnown = known, partial = false, uid = uidFinal ?: "", message = msg) }
            } catch (_: TimeoutCancellationException) {
                fail(owner, "Key check timed out; remaining batches were not sent.")
            } catch (e: CancellationException) {
                fail(owner, "Key check cancelled.")
                throw e
            } catch (e: Exception) {
                fail(owner, e.message ?: "Key check failed.")
            }
        }.also { running ->
            running.invokeOnCompletion { if (it is CancellationException) fail(owner, "Key check cancelled.") }
        }
    }

    private suspend fun checkOwner(owner: Long) {
        currentCoroutineContext().ensureActive()
        synchronized(this) { if (epoch != owner) throw CancellationException() }
    }

    @Synchronized private fun update(owner: Long, transform: (MifareAttackState) -> MifareAttackState) {
        if (epoch == owner) state.value = transform(state.value)
    }

    private fun fail(owner: Long, message: String) = update(owner) { it.copy(running = false, completed = false, message = message) }

    private data class BatchMeta(
        val uid: String,
        val completenessKnown: Boolean,
        val complete: Boolean,
        val cancelled: Boolean,
    )

    /** Extract the v1 completeness fields. Absent `complete` key = old firmware (unknown). */
    private fun parseMeta(reply: JSONObject?): BatchMeta {
        if (reply == null || !reply.has("complete")) return BatchMeta("", false, false, false)
        val rawUid = reply.optString("uid", "").trim().uppercase()
        val uid = if (UID.matches(rawUid)) rawUid else ""
        val cancelled = reply.optString("stop", "") == "cancelled"
        return BatchMeta(uid, true, reply.optBoolean("complete", false), cancelled)
    }

    private fun parse(reply: JSONObject?, batch: List<String>): List<MifareFound> {
        val array = reply?.optJSONArray("found") ?: throw IOException("Invalid key-check reply: missing found array.")
        if (array.length() > 80) throw IOException("Invalid key-check reply: too many sector keys.")
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index) ?: throw IOException("Invalid key-check result.")
            val rawSector = item.opt("sector")
            val sector = (rawSector as? Number)?.toInt() ?: -1
            val type = item.optString("type")
            val key = item.optString("key").uppercase()
            if (sector !in 0..39 || (rawSector as Number).toDouble() != sector.toDouble() ||
                type !in listOf("A", "B") || !KEY.matches(key) || key !in batch)
                throw IOException("Invalid key-check result.")
            MifareFound(sector, type, key)
        }
    }

    companion object {
        // The driver has no bounded whole-batch WALL-CLOCK runtime: it performs at most
        // sectors*2*keys (<= 40*2*48 per batch) authenticate() operations, but per-auth time on
        // hardware (especially an I2C PN532 that re-selects after every failed auth) is not
        // characterized, so a tight derived timeout risks false timeouts on legitimately slow
        // hardware. The in-flight Stop now returns promptly via STOP_WAIT_MS regardless, so 650 s
        // is kept only as a conservative safety ceiling for a batch left to run to completion.
        const val BATCH_TIMEOUT_MS = 650_000L
        /** Stop frame polled by the firmware; matches companionPollStop's "*_stop" rule. */
        const val STOP_CMD = "mifare_keycheck_stop"
        /** The stop frame is polled, not acked, so this only bounds the fire-and-forget send. */
        const val STOP_FRAME_TIMEOUT_MS = 10_000L
        /** Bounded wait for the firmware's `cancelled` ACK after a stop; then force a partial stop. */
        const val STOP_WAIT_MS = 15_000L
        private val KEY = Regex("[0-9A-F]{12}")
        private val UID = Regex("[0-9A-F]{1,20}")
    }
}
