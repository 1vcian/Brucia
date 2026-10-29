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
    val ok = !isError && (data?.optBoolean("ok") ?: false)
    val out = if (isError) data?.optString("msg", "Device error") ?: "Device error"
              else data?.optString("out") ?: ""
    pending.remove(id)?.invoke(ok, out)
    pendingJson.remove(id)?.invoke(ok, data)
    return out
}
