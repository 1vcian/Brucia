package app.brucia.session

import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.protocol.awaitReply
import app.brucia.protocol.routeCommandReply
import app.brucia.transport.Transport
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Correlates CMD requests with their ACK/ERR replies for one [CompanionClient] link.
 * Registers before sending, releases on reply / timeout / cancellation / send failure,
 * and fails everything still pending exactly once on [close]. Local send failures
 * complete through the same ERR path as firmware errors, never as exceptions.
 * [preflight] may throw to reject a command before it is written (e.g. missing caps).
 * [onCallbackError] observes a callback that threw during [close]; the others still run.
 * Await timeouts and ACK/ERR replies with no pending request are counted in the client's
 * [app.brucia.protocol.LinkCounters] (Diagnostics).
 */
class CommandSession(
    private val client: CompanionClient,
    private val transport: Transport,
    private val onCallbackError: (Exception) -> Unit = {},
    private val preflight: (String) -> Unit = {},
) {
    private val pending = ConcurrentHashMap<Int, (Boolean, String) -> Unit>()
    private val pendingJson = ConcurrentHashMap<Int, (Boolean, JSONObject?) -> Unit>()
    private val closed = AtomicBoolean(false)

    /** True while any command is awaiting its reply (drives the ping watchdog). */
    val hasPending: Boolean get() = pending.isNotEmpty() || pendingJson.isNotEmpty()
    val isClosed: Boolean get() = closed.get()

    /** Next request id, unique across reconnects. */
    fun nextId(): Int = client.nextId()

    /** Send [cmd] and suspend until its reply; (false, msg) on timeout or local failure. */
    suspend fun await(cmd: String, timeoutMs: Long): Pair<Boolean, String> {
        val id = client.nextId()
        return awaitReply(pending, id, timeoutMs, NO_RESPONSE, client.counters::noteTimeout) { transmit(cmd, id) }
    }

    /** CMD variants with fields in d{} (e.g. speaker PCM sr/pcm), with identical reply ownership. */
    suspend fun awaitFields(cmd: String, fields: JSONObject, timeoutMs: Long): Pair<Boolean, String> {
        val body = JSONObject(fields.toString()).put("cmd", cmd)
        val id = client.nextId()
        return awaitReply(pending, id, timeoutMs, NO_RESPONSE, client.counters::noteTimeout) {
            write(id, "Unable to send command") {
                preflight(cmd)
                client.cmdFields(cmd, body, id)
            }
        }
    }

    /** Like [await] but yields the full reply data object ({"msg"} on failure). */
    suspend fun awaitJson(cmd: String, timeoutMs: Long): Pair<Boolean, JSONObject?> {
        val id = client.nextId()
        return awaitReply(pendingJson, id, timeoutMs, JSONObject().put("msg", NO_RESPONSE), client.counters::noteTimeout) {
            transmit(cmd, id)
        }
    }

    /** Register a non-suspending reply callback for [id]; pair with [transmit] and [release]. */
    fun expect(id: Int, callback: (Boolean, String) -> Unit) { pending[id] = callback }

    /** Like [expect] but receives the full reply data object. */
    fun expectJson(id: Int, callback: (Boolean, JSONObject?) -> Unit) { pendingJson[id] = callback }

    /** Drop the callback for [id] without invoking it. */
    fun release(id: Int) {
        pending.remove(id)
        pendingJson.remove(id)
    }

    /** Complete [id] as an ERR with [message] if it is still pending (e.g. a caller-side timeout). */
    fun fail(id: Int, message: String) {
        val reply = JSONObject().put("t", Kind.ERR).put("id", id).put("d", JSONObject().put("msg", message))
        routeCommandReply(reply, pending, pendingJson)
    }

    /** Write a SUB for [stream]; the device answers with an ACK/ERR for [id]. Failures complete [id]. */
    fun subscribe(stream: String, on: Boolean, channel: Int?, bssid: String?, id: Int) =
        write(id, "Unable to set stream") { client.sub(stream, on, channel, bssid, id) }

    /** Write one CMD for an [id] registered with [expect]; any local failure completes [id] as an ERR. */
    fun transmit(cmd: String, id: Int) = write(id, "Unable to send command") {
        preflight(cmd)
        client.cmd(cmd, id)
    }

    /** Resolve only the command named by an ACK/ERR; returns its output text. Ignored after [close]. */
    fun route(message: JSONObject): String {
        if (closed.get()) return ""
        val id = message.optInt("id", -1)
        if (!pending.containsKey(id) && !pendingJson.containsKey(id)) client.counters.noteUnmatchedReply()
        return routeCommandReply(message, pending, pendingJson)
    }

    /**
     * Fail every pending command now with [reason] but keep the session USABLE (unlike [close], which
     * also rejects all future sends). For a link that went dead while the transport stayed open - the
     * device left companion mode - so pending awaits return at once instead of waiting out their
     * timeout, and the same session can serve new commands once the device is back in companion.
     */
    fun failPending(reason: String) {
        if (closed.get()) return
        val failed = pending.keys.mapNotNull { pending.remove(it) }
        val failedJson = pendingJson.keys.mapNotNull { pendingJson.remove(it) }
        failed.forEach { cb -> try { cb(false, reason) } catch (e: Exception) { onCallbackError(e) } }
        failedJson.forEach { cb -> try { cb(false, JSONObject().put("msg", reason)) } catch (e: Exception) { onCallbackError(e) } }
    }

    /** Idempotent: reject new sends, then fail every pending command once with [reason]. */
    fun close(reason: String = "Disconnected") {
        if (!closed.compareAndSet(false, true)) return
        val failed = pending.keys.mapNotNull { pending.remove(it) }
        val failedJson = pendingJson.keys.mapNotNull { pendingJson.remove(it) }
        failed.forEach { cb -> try { cb(false, reason) } catch (e: Exception) { onCallbackError(e) } }
        failedJson.forEach { cb -> try { cb(false, JSONObject().put("msg", reason)) } catch (e: Exception) { onCallbackError(e) } }
    }

    /** Run one frame write; any local failure is converted into a synthesized ERR for [id]. */
    private fun write(id: Int, fallback: String, block: () -> Result<*>) {
        try {
            // Checked after registration: a racing close() either drains this id or is seen here.
            check(!closed.get() && transport.isOpen) { "USB connection is closed or changed" }
            val result = block()
            if (result.isFailure) {
                fail(id, result.exceptionOrNull()?.message ?: fallback)
                return
            }
            check(transport.isOpen) { "USB write failed" }
        } catch (e: Exception) {
            fail(id, e.message ?: fallback)
        }
    }

    private companion object {
        const val NO_RESPONSE = "No response from the device."
    }
}
