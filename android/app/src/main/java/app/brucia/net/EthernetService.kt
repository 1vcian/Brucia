package app.brucia.net

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import app.brucia.session.JsonCommandRunner
import app.brucia.wardrive.EthStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ethernet (W5500) commands on one single-owner [DeviceOperation], lifted from WardriveViewModel:
 * status/config plus the two authorized-use LAN stress tests (DHCP starvation, MAC flooding).
 * [refresh] reads structured link state via [JsonCommandRunner]; a token guards the [status] mapping
 * so only the owning request publishes it, and [configure] chains a single [refresh] on success.
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

    /** Query Ethernet link + IP (eth_status); ok maps [status] and clears [message], fail shows the error. */
    fun refresh() = synchronized(lock) {
        val t = ++token
        op.startJson(json, "eth_status", "Querying Ethernet...", timeoutMs = STATUS_TIMEOUT_MS) { ok, d ->
            if (!ok) return@startJson ethErrMessage(d?.optString("out").orEmpty())
            synchronized(lock) {
                if (t == token) _status.value = EthStatus(
                    link = d?.optBoolean("link", false) ?: false,
                    ip = d?.optString("ip").orEmpty(),
                    detail = d?.optString("out").orEmpty(),
                )
            }
            ""
        }
    }

    /**
     * Apply Ethernet configuration (eth_config <...>); [args] is the raw argument string. On success
     * a single [refresh] follows (it supersedes this command, so its "Querying Ethernet..." replaces
     * the config text and no loop forms); on failure the error is shown and no refresh runs.
     */
    fun configure(args: String) = synchronized(lock) {
        ++token
        val a = args.trim()
        val cmd = if (a.isEmpty()) "eth_config" else "eth_config $a"
        op.start(cmd, "Applying Ethernet config...", timeoutMs = CONFIG_TIMEOUT_MS) { ok, out ->
            if (!ok) return@start ethErrMessage(out)
            val text = out.ifBlank { "Ethernet configured." }
            refresh()
            text
        }
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
