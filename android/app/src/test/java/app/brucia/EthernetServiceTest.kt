package app.brucia

import app.brucia.net.EthernetService
import app.brucia.wardrive.EthStatus
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EthernetServiceTest {
    private val runner = GateRunner().apply { sticky = true }
    private val json = JsonGate() // shared fake declared in LoraServiceTest

    private fun CoroutineScope.svc(connected: Boolean = true) =
        EthernetService(this, runner, json, { connected })

    private fun statusData(link: Boolean, ip: String, out: String) =
        JSONObject().put("link", link).put("ip", ip).put("out", out)

    @Test fun notConnectedRejectsLocallyAndSendsNothing() = runVirtual {
        val s = svc(connected = false)
        s.refresh(); s.configure("dhcp"); s.dhcpStarve(); s.macFlood()
        assertEquals("Connect the device over USB first.", s.message.value)
        assertNull(s.status.value)
        delay(20)
        assertTrue(runner.calls.isEmpty() && json.calls.isEmpty())
    }

    @Test fun refreshMapsStatusOnOkAndErrorOnFail() = runVirtual {
        val s = svc()
        s.refresh()
        assertEquals("Querying Ethernet..." to true, s.message.value to s.busy.value)
        until { json.calls.size == 1 }
        assertEquals("eth_status" to 16_000L, json.calls[0])
        json.reply(0, true, statusData(true, "10.0.0.5", "link up"))
        until { !s.busy.value }
        assertEquals("", s.message.value)
        assertEquals(EthStatus(true, "10.0.0.5", "link up"), s.status.value)

        s.refresh()
        until { json.calls.size == 2 }
        json.reply(1, false, JSONObject())
        until { !s.busy.value }
        assertEquals("No Ethernet on this device.", s.message.value)
    }

    /** The shape src/companion/network.cpp ethSendStatus really sends: link state under d.data. */
    private fun firmwareStatus(ok: Boolean, link: Boolean, ip: String, out: String) = JSONObject()
        .put("ok", ok).put("out", out)
        .put("data", JSONObject().put("link", link).put("up", ok).put("ip", ip).put("gw", "0.0.0.0")
            .put("mask", "0.0.0.0").put("dns", "0.0.0.0").put("mac", "02:00:00:00:00:01"))

    @Test fun refreshReadsTheFirmwareNestedDataObject() = runVirtual {
        val s = svc()
        s.refresh()
        until { json.calls.size == 1 }
        json.reply(0, true, firmwareStatus(true, true, "192.168.1.20", "link up, ip 192.168.1.20"))
        until { !s.busy.value }
        assertEquals("", s.message.value)
        assertEquals(EthStatus(true, "192.168.1.20", "link up, ip 192.168.1.20"), s.status.value)

        // Cable in, no DHCP lease: ok=false but the link state is still reported, not a stale one.
        s.refresh()
        until { json.calls.size == 2 }
        json.reply(1, false, firmwareStatus(false, true, "0.0.0.0", "no link / no DHCP lease"))
        until { !s.busy.value }
        assertEquals("no link / no DHCP lease", s.message.value)
        assertEquals(EthStatus(true, "", "no link / no DHCP lease"), s.status.value)
    }

    @Test fun errMessageIsShownForRefresh() = runVirtual {
        val s = svc()
        s.refresh()
        until { json.calls.size == 1 }
        json.reply(0, false, JSONObject().put("code", 400).put("msg", "W5500 busy"))
        until { !s.busy.value }
        assertEquals("W5500 busy", s.message.value)
        assertNull(s.status.value)
    }

    @Test fun configureMapsItsOwnAckWithoutAFollowUpRefresh() = runVirtual {
        val s = svc()
        // Blank means DHCP: the firmware only handles "eth_config <dhcp|static ...>"; a bare
        // "eth_config" fell through to the Bruce CLI and showed "No Ethernet on this device.".
        s.configure("")
        assertEquals("Applying Ethernet config..." to true, s.message.value to s.busy.value)
        until { json.calls.size == 1 }
        assertEquals("eth_config dhcp" to 18_000L, json.calls[0])
        json.reply(0, true, firmwareStatus(true, true, "10.0.0.7", "link up, ip 10.0.0.7"))
        until { !s.busy.value }
        assertEquals("link up, ip 10.0.0.7", s.message.value)
        assertEquals(EthStatus(true, "10.0.0.7", "link up, ip 10.0.0.7"), s.status.value)

        // A static config reports the static address from its own ACK. A chained eth_status would
        // bring the W5500 up afresh on DHCP and hide it.
        s.configure(" static 192.168.5.9 192.168.5.1 255.255.255.0 ")
        until { json.calls.size == 2 }
        assertEquals("eth_config static 192.168.5.9 192.168.5.1 255.255.255.0", json.calls[1].first)
        json.reply(1, true, firmwareStatus(true, true, "192.168.5.9", "link up, ip 192.168.5.9"))
        until { !s.busy.value }
        assertEquals(EthStatus(true, "192.168.5.9", "link up, ip 192.168.5.9"), s.status.value)
        delay(20)
        assertEquals(2, json.calls.size)
        assertTrue(runner.calls.isEmpty())
    }

    @Test fun configureFailureShowsTheDeviceText() = runVirtual {
        val s = svc()
        s.configure("static 1.2.3.4")
        until { json.calls.size == 1 }
        json.reply(0, false, JSONObject().put("code", 400).put("msg", "usage: eth_config static <ip> <gw> <mask> [dns]"))
        until { !s.busy.value }
        assertEquals("usage: eth_config static <ip> <gw> <mask> [dns]", s.message.value)
        assertNull(s.status.value)
        delay(20)
        assertEquals(1, json.calls.size)
    }

    @Test fun dhcpStarveAndMacFloodTextsCommandsAndTimeouts() = runVirtual {
        val s = svc()
        s.dhcpStarve()
        assertEquals("DHCP starvation running (10s)...", s.message.value)
        until { runner.calls.size == 1 }
        assertEquals("eth_dhcp_starve 10" to 24_000L, runner.calls[0])
        runner.reply(0, true, "")
        until { !s.busy.value }
        assertEquals("DHCP starvation done.", s.message.value)

        s.macFlood(5)
        assertEquals("MAC flooding running (5s)...", s.message.value)
        until { runner.calls.size == 2 }
        assertEquals("eth_mac_flood 5" to 19_000L, runner.calls[1])
        runner.reply(1, false, "")
        until { !s.busy.value }
        assertEquals("No Ethernet on this device.", s.message.value)
    }

    @Test fun stopCancelsAndStaleReplyNeverMapsStatus() = runVirtual {
        val s = svc()
        s.refresh()
        until { json.calls.size == 1 }
        s.stop()
        until { !s.busy.value }
        assertEquals("Stopped.", s.message.value)
        json.reply(0, true, statusData(true, "10.0.0.9", "up"))
        delay(20)
        assertNull(s.status.value)
    }
}
