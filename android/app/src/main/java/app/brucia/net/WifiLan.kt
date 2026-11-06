package app.brucia.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * One network as the platform reports it, as plain data so the selection is JVM-testable.
 * [ipv4] holds (address, prefix length) pairs from its LinkProperties.
 */
data class NetSnapshot(
    val active: Boolean,
    val wifi: Boolean,
    val vpn: Boolean = false,
    val cellular: Boolean = false,
    val interfaceName: String? = null,
    val ipv4: List<Pair<String, Int>> = emptyList(),
)

/** The phone's address on the active Wi-Fi network, with its real prefix length. */
data class WifiLan(val ip: String, val prefix: Int, val interfaceName: String?)

/** Hosts a LAN scan will probe, the subnet label shown, and why it was bounded (if it was). */
data class ScanPlan(val hosts: List<String>, val label: String, val note: String?)

/**
 * Picks the LAN that Network Scan and the MITM proxy work on: only the ACTIVE network, only when
 * it is Wi-Fi and not a VPN, only its IPv4 address. Anything else is refused with a reason the
 * screen shows, instead of silently scanning a mobile-data or VPN subnet.
 */
object WifiLanSelector {
    /** Largest host count scanned; a bigger subnet is bounded to the phone's own /24. */
    const val MAX_HOSTS = 254

    const val NO_NETWORK = "No active network. Connect the phone to a Wi-Fi network."
    const val VPN_ACTIVE = "A VPN is the active network, so LAN traffic may go through the VPN. " +
        "Disconnect the VPN to work on the Wi-Fi LAN."
    const val NOT_WIFI = "The active network is not Wi-Fi (for example mobile data). " +
        "Connect the phone to the Wi-Fi network you want to use."
    const val NO_IPV4 = "The Wi-Fi network has no IPv4 address (IPv6-only); this tool needs IPv4."

    /** The Wi-Fi LAN, or the refusal reason. */
    fun select(networks: List<NetSnapshot>): Result<WifiLan> {
        val active = networks.firstOrNull { it.active } ?: return refuse(NO_NETWORK)
        if (active.vpn) return refuse(VPN_ACTIVE)
        if (!active.wifi) return refuse(NOT_WIFI)
        val (ip, prefix) = active.ipv4.firstOrNull { (a, p) -> usable(a) && p in 0..32 } ?: return refuse(NO_IPV4)
        return Result.success(WifiLan(ip, prefix, active.interfaceName))
    }

    /**
     * The hosts to scan for [lan]: every host address of its subnet when that has at most
     * [MAX_HOSTS] hosts (/24 to /31; a /32 has none), otherwise only the phone's own /24, with a
     * [ScanPlan.note] saying so.
     */
    fun scanPlan(lan: WifiLan): ScanPlan {
        val ip = toInt(lan.ip)
        if (lan.prefix < 24) {
            val base = ip and 0xFFFFFF00.toInt()
            val total = (1L shl (32 - lan.prefix)) - 2
            return ScanPlan(
                hosts = (1..254).map { toText(base + it) },
                label = "${toText(base)}/24",
                note = String.format(java.util.Locale.US, "This Wi-Fi is a /%d (%,d hosts); only this phone's /24 (254 hosts) is scanned.", lan.prefix, total),
            )
        }
        val mask = if (lan.prefix == 32) -1 else (-1 shl (32 - lan.prefix))
        val network = ip and mask
        val size = 1L shl (32 - lan.prefix)
        val hosts = when (lan.prefix) {
            32 -> emptyList()
            31 -> listOf(toText(network), toText(network + 1)) // RFC 3021 point-to-point
            else -> (1 until size - 1).map { toText(network + it.toInt()) }
        }
        return ScanPlan(hosts, "${toText(network)}/${lan.prefix}",
            if (hosts.isEmpty()) "This Wi-Fi is a /32: there are no other hosts to scan." else null)
    }

    private fun refuse(reason: String): Result<WifiLan> = Result.failure(IllegalStateException(reason))

    private fun usable(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4 || parts.any { p -> p.toIntOrNull()?.let { it in 0..255 } != true }) return false
        val first = parts[0].toInt()
        return first != 127 && first != 0
    }

    private fun toInt(ip: String): Int = ip.split('.').fold(0) { acc, p -> (acc shl 8) or p.toInt() }

    private fun toText(v: Int): String = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

    /** Platform snapshot of the active network (ACCESS_NETWORK_STATE). Empty when unavailable. */
    fun snapshot(context: Context): List<NetSnapshot> = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val link = network?.let { cm.getLinkProperties(it) }
        if (network == null || caps == null) emptyList() else listOf(
            NetSnapshot(
                active = true,
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                interfaceName = link?.interfaceName,
                ipv4 = link?.linkAddresses.orEmpty()
                    .filter { it.address is Inet4Address }
                    .mapNotNull { la -> la.address.hostAddress?.let { it to la.prefixLength } },
            ),
        )
    } catch (e: Exception) {
        app.brucia.BruciaLog.w("WifiLan", "network snapshot failed", e)
        emptyList()
    }

    /** The active Wi-Fi LAN of this phone, or the refusal reason. */
    fun current(context: Context): Result<WifiLan> = select(snapshot(context))
}
