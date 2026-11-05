package app.brucia

import app.brucia.ble.GattService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class GattServiceTest {
    private val runner = GateRunner()
    @Volatile private var connected = true
    private val resetCount = AtomicInteger(0)
    private val notConnected = "Connect the device over USB first."
    private fun CoroutineScope.svc() =
        GattService(this, runner, { connected }, onReset = { resetCount.incrementAndGet() })

    /** Connect and finish its ACK, returning the final status. */
    private suspend fun GattService.connectDone(mac: String, ok: Boolean, out: String): String {
        val n = runner.calls.size
        connect(mac)
        until { runner.calls.size == n + 1 }
        runner.reply(n, ok, out)
        until { !connecting.value }
        return status.value
    }

    @Test(timeout = 5_000) fun connectSetsStateSendsCommandAndMapsResult() = runVirtual {
        val svc = svc()
        svc.connect("AA:BB:CC:DD:EE:FF")
        assertEquals("Connecting to AA:BB:CC:DD:EE:FF...", svc.status.value)
        assertEquals("AA:BB:CC:DD:EE:FF", svc.mac.value)
        assertTrue(svc.connecting.value)
        assertEquals(1, resetCount.get())
        until { runner.calls.size == 1 }
        assertEquals("ble_gatt_connect AA:BB:CC:DD:EE:FF" to 650_000L, runner.calls[0])
        runner.reply(0, true, "")
        until { !svc.connecting.value }
        assertEquals("Connected - services below.", svc.status.value)
    }

    @Test(timeout = 5_000) fun connectFailureTexts() = runVirtual {
        val svc = svc()
        assertEquals("Connect failed.", svc.connectDone("M1", false, ""))
        assertEquals("device busy", svc.connectDone("M2", false, "device busy"))
        assertEquals("discovered 2 services", svc.connectDone("M3", true, "discovered 2 services"))
        assertEquals(3, resetCount.get())
    }

    @Test(timeout = 5_000) fun readsAreIndependentAndOrderInsensitive() = runVirtual {
        val svc = svc()
        svc.connectDone("M", true, "disc")
        svc.read("M", "u1")
        svc.read("M", "u2")
        until { svc.reads.value["u1"] == "reading..." && svc.reads.value["u2"] == "reading..." }
        until { runner.calls.size == 3 }
        assertEquals("ble_gatt_read M u1" to 650_000L, runner.calls[1])
        assertEquals("ble_gatt_read M u2" to 650_000L, runner.calls[2])
        runner.reply(2, true, "BB")          // second read returns first
        runner.reply(1, false, "nope")
        until { svc.reads.value["u1"] != "reading..." && svc.reads.value["u2"] != "reading..." }
        assertEquals("read failed: nope", svc.reads.value["u1"])
        assertEquals("BB", svc.reads.value["u2"])
    }

    @Test(timeout = 5_000) fun readEmptyAndErrorFallbacks() = runVirtual {
        val svc = svc()
        svc.connectDone("M", true, "disc")
        svc.read("M", "u1")
        svc.read("M", "u2")
        until { runner.calls.size == 3 }
        runner.reply(1, true, "")
        runner.reply(2, false, "")
        until { svc.reads.value["u1"] == "(empty)" && svc.reads.value["u2"] == "read failed: error" }
    }

    @Test(timeout = 5_000) fun stopClearsEverythingAndSuppressesStaleRead() = runVirtual {
        val svc = svc()
        svc.connectDone("M", true, "disc")
        svc.read("M", "u1")
        until { svc.reads.value["u1"] == "reading..." && runner.calls.size == 2 }
        val before = resetCount.get()
        svc.stop()
        assertNull(svc.mac.value)
        assertEquals("", svc.status.value)
        assertFalse(svc.connecting.value)
        assertTrue(svc.reads.value.isEmpty())
        assertEquals(before + 1, resetCount.get())
        runner.reply(1, true, "late value")   // returns after the reset: must be dropped
        delay(20)
        assertTrue(svc.reads.value.isEmpty())
    }

    @Test(timeout = 5_000) fun reconnectSuppressesPreviousRead() = runVirtual {
        val svc = svc()
        svc.connectDone("A", true, "disc")
        svc.read("A", "u1")
        until { svc.reads.value["u1"] == "reading..." && runner.calls.size == 2 }
        svc.connect("B")                       // clears reads, bumps the read token
        assertTrue(svc.reads.value.isEmpty())
        until { runner.calls.size == 3 }
        runner.reply(1, true, "stale")         // old read, now a different generation
        delay(20)
        assertFalse(svc.reads.value.containsKey("u1"))
        runner.reply(2, true, "disc2")
        until { !svc.connecting.value }
        assertEquals("disc2", svc.status.value)
    }

    @Test(timeout = 5_000) fun notConnectedRejectsConnectAndRead() = runVirtual {
        connected = false
        val svc = svc()
        svc.connect("M")
        assertEquals(notConnected, svc.status.value)
        assertNull(svc.mac.value)
        assertEquals(0, resetCount.get())
        svc.read("M", "u1")
        assertEquals(notConnected, svc.status.value)
        assertTrue(svc.reads.value.isEmpty())
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }
}
