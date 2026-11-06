package app.brucia.protocol

import org.json.JSONObject

/** Complete only the command named by an ACK/ERR, even when just one command is pending. */
internal fun routeCommandReply(
    message: JSONObject,
    pending: MutableMap<Int, (Boolean, String) -> Unit>,
    pendingJson: MutableMap<Int, (Boolean, JSONObject?) -> Unit>,
): String {
    val kind = message.optInt("t")
    require(kind == Kind.ACK || kind == Kind.ERR)
    val id = message.optInt("id", -1)
    val data = message.optJSONObject("d")
    val isError = kind == Kind.ERR
    val ok = !isError && data?.opt("ok") == true
    val out = if (isError) data?.optString("msg", "Device error") ?: "Device error"
              else CliReplyLimit.mark(data?.optString("out") ?: "")
    // Detach both before invoking user code: a callback can enqueue another
    // request, which must not be consumed by this reply.
    val callback = pending.remove(id)
    val jsonCallback = pendingJson.remove(id)
    try { callback?.invoke(ok, out) }
    finally { jsonCallback?.invoke(ok, data) }
    return out
}

/**
 * The companion runs commands it does not handle itself through the Bruce CLI and captures their
 * output into one ACK of at most [BYTES] bytes (src/companion/commands.cpp
 * CapturingSerialDevice::CAP), silently dropping the rest. Native handlers send far less (script
 * output is cut at about 1.8 KB by the firmware itself), so an `out` at the cap was cut: [mark]
 * appends [MARK] on its own line so every screen that shows device output says so.
 */
object CliReplyLimit {
    const val BYTES = 3500
    const val MARK = "(output truncated by the device at 3500 bytes)"
    private const val SUFFIX = "\n" + MARK

    /**
     * Whether [out] reached the cap. A few bytes of slack above it: the cut can split a multi-byte
     * character, which then decodes to a 3-byte replacement character.
     */
    fun atLimit(out: String): Boolean = out.toByteArray(Charsets.UTF_8).size in (BYTES - 3)..(BYTES + 3)

    fun mark(out: String): String = if (atLimit(out)) out + SUFFIX else out

    /** [out] without a [mark] suffix, and whether it had one: parsers get the exact device text back. */
    fun unmark(out: String): Pair<String, Boolean> =
        if (out.endsWith(SUFFIX)) out.removeSuffix(SUFFIX) to true else out to false
}
