package app.brucia

import app.brucia.net.NetSnapshot
import app.brucia.net.WifiLan
import app.brucia.net.WifiLanSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Network Scan / MITM work only on the active Wi-Fi network's IPv4 subnet, with its real prefix,
 * and never scan more than 254 hosts. Fed with plain network data (no Android).
 */
class WifiLanSelectorTest {

    private fun wifi(ip: String, prefix: Int, active: Boolean = true) =
        NetSnapshot(active = active, wifi = true, interfaceName = "wlan0", ipv4 = listOf(ip to prefix))
    private fun cellular(active: Boolean) =
        NetSnapshot(active = active, wifi = false, cellular = true, interfaceName = "rmnet0", ipv4 = listOf("10.64.12.7" to 30))
    // Android reports a VPN with its underlying transports, so it can carry the Wi-Fi flag too.
    private fun vpn(active: Boolean) =
        NetSnapshot(active = active, wifi = true, vpn = true, interfaceName = "tun0", ipv4 = listOf("10.8.0.2" to 24))

    private fun reason(nets: List<NetSnapshot>) = WifiLanSelector.select(nets).exceptionOrNull()?.message

    @Test fun activeWifiIsChosenOverInactiveCellular() {
        val lan = WifiLanSelector.select(listOf(cellular(active = false), wifi("192.168.1.57", 24))).getOrThrow()
        assertEquals(WifiLan("192.168.1.57", 24, "wlan0"), lan)
    }

    @Test fun activeCellularIsRefusedEvenWithWifiConnected() {
        assertEquals(WifiLanSelector.NOT_WIFI, reason(listOf(wifi("192.168.1.57", 24, active = false), cellular(active = true))))
    }

    @Test fun activeVpnIsRefused() {
        assertEquals(WifiLanSelector.VPN_ACTIVE, reason(listOf(vpn(active = true), wifi("192.168.1.57", 24, active = false))))
    }

    @Test fun noActiveNetworkIsRefused() {
        assertEquals(WifiLanSelector.NO_NETWORK, reason(emptyList()))
        assertEquals(WifiLanSelector.NO_NETWORK, reason(listOf(wifi("192.168.1.57", 24, active = false))))
    }

    @Test fun ipv6OnlyWifiIsRefused() {
        val nets = listOf(NetSnapshot(active = true, wifi = true, interfaceName = "wlan0", ipv4 = emptyList()))
        assertEquals(WifiLanSelector.NO_IPV4, reason(nets))
        assertEquals(WifiLanSelector.NO_IPV4, reason(listOf(wifi("127.0.0.1", 8))))
    }

    @Test fun slash24ScansItsHostRange() {
        val plan = WifiLanSelector.scanPlan(WifiLan("192.168.1.57", 24, "wlan0"))
        assertEquals(254, plan.hosts.size)
        assertEquals("192.168.1.1", plan.hosts.first())
        assertEquals("192.168.1.254", plan.hosts.last())
        assertEquals("192.168.1.0/24", plan.label)
        assertNull(plan.note)
    }

    @Test fun slash23IsBoundedToThePhonesSlash24AndSaysSo() {
        val plan = WifiLanSelector.scanPlan(WifiLan("192.168.3.20", 23, "wlan0"))
        assertEquals(254, plan.hosts.size)
        assertEquals("192.168.3.1", plan.hosts.first())
        assertEquals("192.168.3.0/24", plan.label)
        assertTrue(plan.note!!.contains("/23") && plan.note!!.contains("510"))
    }

    @Test fun slash16IsBoundedNotScannedSilently() {
        val plan = WifiLanSelector.scanPlan(WifiLan("10.20.30.40", 16, "wlan0"))
        assertEquals(WifiLanSelector.MAX_HOSTS, plan.hosts.size)
        assertEquals("10.20.30.0/24", plan.label)
        assertTrue(plan.note!!.contains("/16") && plan.note!!.contains("65,534"))
    }

    @Test fun slash30ScansOnlyItsTwoHosts() {
        val plan = WifiLanSelector.scanPlan(WifiLan("172.16.5.6", 30, "wlan0"))
        assertEquals(listOf("172.16.5.5", "172.16.5.6"), plan.hosts)
        assertEquals("172.16.5.4/30", plan.label)
        assertNull(plan.note)
    }

    @Test fun slash32HasNothingToScan() {
        val plan = WifiLanSelector.scanPlan(WifiLan("172.16.5.6", 32, "wlan0"))
        assertTrue(plan.hosts.isEmpty())
        assertTrue(plan.note!!.contains("/32"))
    }
}
