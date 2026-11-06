package app.brucia.net

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import app.brucia.session.JsonCommandRunner
import app.brucia.wardrive.EthStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Ethernet (W5500) commands on one single-owner [DeviceOperation], lifted from WardriveViewModel:
 * status/config plus the two authorized-use LAN stress tests (DHCP starvation, MAC flooding).
 * [refresh] and [configure] read structured link state via [JsonCommandRunner]; a token guards the
 * [status] mapping so only the owning request publishes it.
 */
class EthernetService(
    scope: CoroutineScope,
    runner: CommandRunner,
    private val json: JsonCommandRunner,
    isConnected: () -> Boolean,
) {
    private val op = DeviceOperation(scope, runner, isConnected, STATUS_TIMEOUT_MS)
    /** Progress or result text (VM ethMessage). */
    val message: StateFlow<String> = op.status
    val busy: StateFlow<Boolean> = op.busy
    private val _status = MutableStateFlow<EthStatus?>(null)
    val status: StateFlow<EthStatus?> = _status.asStateFlow()

    private val lock = Any()
    private var token = 0L

    private fun ethErrMessage(out: String) = out.ifBlank { "No Ethernet on this device." }

    /**
     * Link state from an eth_status / eth_config ACK. The firmware nests it in `d.data`
     * (src/companion/network.cpp ethSendStatus: {ok, out, data:{link, up, ip, gw, mask, dns, mac}});
     * top-level fields are accepted as a fallback. Null when the reply carries no link state
     * (an ERR such as a usage error, or "W5500 not found").
     */
    private fun statusOf(d: JSONObject?): EthStatus? {
        val reply = d ?: return null
        val r = reply.optJSONObject("data") ?: reply.takeIf { it.has("link") } ?: return null
        return EthStatus(
            link = r.optBoolean("link", false),
            ip = r.optString("ip").takeUnless { it == "0.0.0.0" }.orEmpty(),
            detail = reply.optString("out"),
        )
    }

    /** Device text of a reply: `out` (ACK) or `msg` (ERR / local failure). */
    private fun textOf(d: JSONObject?): String = d?.optString("out")?.ifBlank { null } ?: d?.optString("msg").orEmpty()

    /**
     * Query Ethernet link + IP (eth_status). Any reply that carries link state maps [status] (a
     * link without a DHCP lease answers ok=false but still reports it); ok clears [message],
     * otherwise the device text is shown.
     */
    fun refresh() = synchronized(lock) {
        val t = ++token
        op.startJson(json, "eth_status", "Querying Ethernet...", timeoutMs = STATUS_TIMEOUT_MS) { ok, d -> publish(t, ok, d, "") }
    }

    /**
     * Apply Ethernet configuration (eth_config <dhcp | static <ip> <gw> <mask> [dns]>); [args] is the
     * raw argument string, blank meaning DHCP. The firmware brings the W5500 up with these settings,
     * reports the resulting link in the same ACK and stops the interface again, so that ACK (not a
     * follow-up eth_status, which would bring it up afresh on DHCP) is what [status] shows.
     */
    fun configure(args: String) = synchronized(lock) {
        val t = ++token
        val a = args.trim().ifEmpty { "dhcp" }
        op.startJson(json, "eth_config $a", "Applying Ethernet config...", timeoutMs = CONFIG_TIMEOUT_MS) { ok, d ->
            publish(t, ok, d, textOf(d).ifBlank { "Ethernet configured." })
        }
    }

    private fun publish(t: Long, ok: Boolean, d: JSONObject?, okText: String): String {
        val parsed = statusOf(d)
        synchronized(lock) { if (t == token && parsed != null) _status.value = parsed }
        return if (ok) okText else ethErrMessage(textOf(d))
    }

    /** DHCP-starvation flood over Ethernet: eth_dhcp_starve [secs]. Authorized use only. */
    fun dhcpStarve(secs: Int = 10) = synchronized(lock) {
        ++token
        op.start("eth_dhcp_starve $secs", "DHCP starvation running (${secs}s)...", timeoutMs = floodTimeoutFor(secs)) { ok, out ->
            if (ok) out.ifBlank { "DHCP starvation done." } else ethErrMessage(out)
        }
    }

    /** MAC-flooding over Ethernet: eth_mac_flood [secs]. Authorized use only. */
    fun macFlood(secs: Int = 10) = synchronized(lock) {
        ++token
        op.start("eth_mac_flood $secs", "MAC flooding running (${secs}s)...", timeoutMs = floodTimeoutFor(secs)) { ok, out ->
            if (ok) out.ifBlank { "MAC flooding done." } else ethErrMessage(out)
        }
    }

    /** Cancel any in-flight command; a late reply never maps [status]. */
    fun stop() = synchronized(lock) { ++token; op.stop() }

    companion object {
        /** eth_status brings the W5500 up with a 6 s wait; +10 s margin for SPI init and the ACK. */
        const val STATUS_TIMEOUT_MS = 16_000L
        /** eth_config: 2 s bring-up + a 6 s link re-wait; +10 s margin. */
        const val CONFIG_TIMEOUT_MS = 18_000L
        /** starve/flood: 4 s bring-up before the secs-long run; +10 s margin. */
        const val FLOOD_MARGIN_MS = 14_000L
        fun floodTimeoutFor(secs: Int): Long = secs.coerceAtLeast(0) * 1000L + FLOOD_MARGIN_MS
    }
}
