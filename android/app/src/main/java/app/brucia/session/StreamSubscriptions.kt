package app.brucia.session

import app.brucia.protocol.StreamPolicy
import app.brucia.wardrive.DeviceCaps
import kotlinx.coroutines.*

/** Owns SUB requests, their ACK deadlines and conservative device cleanup obligations.
 * A missing ACK does not prove the radio stopped. Keep that stream eligible for Stop/disconnect
 * until an OFF ACK (or a successful exclusive replacement) resolves the uncertainty.
 */
class StreamSubscriptions(
    private val scope: CoroutineScope,
    private val session: () -> CommandSession?,
    private val caps: () -> DeviceCaps?,
    private val onState: (String, Boolean) -> Unit,
    private val onChanged: () -> Unit = {},
    private val onError: (String, String) -> Unit = { _, _ -> },
    private val onStart: (String, Boolean) -> Long = { _, _ -> 0L },
    private val onFinish: (Long, Boolean, String) -> Unit = { _, _, _ -> },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Long = 8000,
) {
    private class Request(val session: CommandSession, val id: Int, val stream: String, val on: Boolean) {
        var job: Job? = null
        var logId: Long? = null
    }
    private val lock = Any()
    private val pending = mutableMapOf<String, Request>()
    private val active = mutableSetOf<String>()
    private val cleanup = mutableSetOf<String>()
    fun activeStreams(): Set<String> = synchronized(lock) { active.toSet() }
    fun cleanupStreams(): Set<String> = synchronized(lock) { cleanup.toSet() }

    fun set(stream: String, on: Boolean, channel: Int? = null, bssid: String? = null): Boolean = synchronized(lock) {
        val s = session()
        val error = when {
            stream !in StreamPolicy.streams -> "Unknown stream"
            !scope.isActive || s == null || s.isClosed -> "Not connected"
            on && caps()?.allowsStream(stream) != true -> "No confirmed hardware for $stream"
            on && channel != null && (stream != "wifi.monitor" || channel !in 1..13) -> "Invalid monitor channel (1–13)"
            on && bssid != null && (stream != "wifi.monitor" || !Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}").matches(bssid)) -> "Invalid monitor BSSID"
            else -> null
        }
        if (error != null) { onError(stream, error); return false }
        s!!
        cancelPending(stream)
        if (on) StreamPolicy.exclusivePeers(stream).forEach { peer ->
            cancelPending(peer)
            update(peer, false)
        }
        val request = Request(s, s.nextId(), stream, on)
        pending[stream] = request
        update(stream, on)
        if (on) cleanup.add(stream)
        onChanged()
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            // Serialize the check and frame write with Stop/replacement. Transport writes are bounded
            // by their adapter; ACK waiting holds no lock, so Stop can supersede a pending start.
            synchronized(lock) {
                if (pending[stream] !== request) return@synchronized
                if (session() !== s || (on && caps()?.allowsStream(stream) != true)) {
                    finish(request, false, "Connection or capabilities changed")
                } else {
                    request.logId = onStart(stream, on)
                    s.expect(request.id) { ok, out -> synchronized(lock) { finish(request, ok, out) } }
                    s.subscribe(stream, on, channel, bssid, request.id)
                }
            }
            delay(timeoutMs)
            s.fail(request.id, "No stream acknowledgement from the device")
        }
        request.job = job
        job.invokeOnCompletion { cause ->
            s.release(request.id)
            synchronized(lock) {
                if (pending[stream] === request) finish(request, false, cause?.message ?: "Stream request cancelled")
            }
        }
        job.start()
        true
    }

    /** Includes timed-out/rejected starts and failed stops, not only optimistic UI state. */
    fun stopAll() = synchronized(lock) { cleanup.toList().forEach { set(it, false) } }

    /** Abandon local ownership and return streams needing best-effort OFF on the old transport. */
    fun detach(): Set<String> = synchronized(lock) {
        val remaining = cleanup.toSet()
        pending.keys.toList().forEach(::cancelPending)
        active.toList().forEach { update(it, false) }
        cleanup.clear()
        onChanged()
        remaining
    }

    private fun update(stream: String, on: Boolean) {
        if (on) active.add(stream) else active.remove(stream)
        onState(stream, on)
    }

    private fun cancelPending(stream: String) {
        val old = pending.remove(stream) ?: return
        old.session.release(old.id)
        old.job?.cancel()
        old.logId?.let { onFinish(it, false, "Superseded or disconnected") }
    }

    private fun finish(request: Request, ok: Boolean, out: String) {
        if (pending[request.stream] !== request) return
        pending.remove(request.stream)
        request.session.release(request.id)
        request.job?.cancel()
        if (ok) {
            if (request.on) cleanup.removeAll(StreamPolicy.exclusivePeers(request.stream))
            else cleanup.remove(request.stream)
        } else {
            update(request.stream, false)
            onError(request.stream, out)
        }
        request.logId?.let { onFinish(it, ok, out) }
        onChanged()
    }
}
