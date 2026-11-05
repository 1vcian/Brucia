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

    @Test fun configureBuildsRawArgsAndChainsSingleRefresh() = runVirtual {
        val s = svc()
        // Empty args -> bare command; drain its chained refresh.
        s.configure("")
        until { runner.calls.size == 1 }
        assertEquals("eth_config" to 18_000L, runner.calls[0])
        runner.reply(0, true, "")
        until { json.calls.size == 1 }
        json.reply(0, true, statusData(true, "1.1.1.1", "up"))
        until { !s.busy.value }
        assertEquals(EthStatus(true, "1.1.1.1", "up"), s.status.value)
        assertEquals("", s.message.value)

        // Raw args passthrough + exactly one chained refresh (no loop).
        s.configure(" dhcp ")
        assertEquals("Applying Ethernet config..." to true, s.message.value to s.busy.value)
        until { runner.calls.size == 2 }
        assertEquals("eth_config dhcp" to 18_000L, runner.calls[1])
        runner.reply(1, true, "applied")
        until { json.calls.size == 2 }
        assertEquals("Querying Ethernet...", s.message.value)
        assertEquals("eth_status", json.calls[1].first)
        json.reply(1, true, statusData(false, "", "no link"))
        until { !s.busy.value }
        assertEquals("", s.message.value)
        assertEquals(EthStatus(false, "", "no link"), s.status.value)
        assertEquals(2, json.calls.size)
    }

    @Test fun configureFailureShowsErrorAndSkipsRefresh() = runVirtual {
        val s = svc()
        s.configure("static 1.2.3.4")
        until { runner.calls.size == 1 }
        runner.reply(0, false, "bad")
        until { !s.busy.value }
        assertEquals("bad", s.message.value)
        delay(20)
        assertTrue(json.calls.isEmpty())
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
