package app.brucia.session

import org.json.JSONObject

/**
 * Like [CommandRunner] but yields the reply's full data object, for commands whose ACK carries
 * structured fields (e.g. eth_status {link, ip}). Local failures arrive as (false, {"msg"}).
 */
fun interface JsonCommandRunner {
    suspend fun runJson(cmd: String, timeoutMs: Long): Pair<Boolean, JSONObject?>
}
