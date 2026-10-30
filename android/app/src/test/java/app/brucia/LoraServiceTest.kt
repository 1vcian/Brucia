package app.brucia

import app.brucia.lora.LoraService
import app.brucia.session.JsonCommandRunner
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** JSON twin of GateRunner: waits on a per-call gate, surviving cancellation so ownership is testable. */
internal class JsonGate : JsonCommandRunner {
    val gates = CopyOnWriteArrayList<CompletableDeferred<Pair<Boolean, JSONObject?>>>()
    val calls = CopyOnWriteArrayList<Pair<String, Long>>()
    override suspend fun runJson(cmd: String, timeoutMs: Long): Pair<Boolean, JSONObject?> {
        val gate = CompletableDeferred<Pair<Boolean, JSONObject?>>().also { gates += it }
        calls += cmd to timeoutMs
        return withContext(NonCancellable) { gate.await() }
    }
    fun reply(i: Int, ok: Boolean, d: JSONObject?) { gates[i].complete(ok to d) }
}

class LoraServiceTest {
    private val runner = GateRunner().apply { sticky = true }
    private val json = JsonGate()
    private val packets = CopyOnWriteArrayList<JSONObject>()
    private fun CoroutineScope.svc(connected: Boolean = true) =
        LoraService(this, runner, json, { connected }) { packets += it }

    @Test fun notConnectedRejectsLocallyAndSendsNothing() = runBlocking {
        val s = svc(connected = false)
        s.configure("868"); s.send("hi"); s.receive(20)
        assertEquals("Connect the device over USB first.", s.status.value)
        assertFalse(s.receiving.value)
        delay(20)
        assertTrue(runner.calls.isEmpty() && json.calls.isEmpty())
    }

    @Test fun configureBuildsCommandDropsTrailingBlanksAndMapsResult() = runBlocking {
        val s = svc()
        s.configure("", "125")
        assertEquals("Enter a frequency in MHz.", s.status.value)
        assertTrue(runner.calls.isEmpty())

        s.configure(" 868 ", "125", "", "7")
        assertEquals("Applying LoRa config...", s.status.value)
        until { runner.calls.size == 1 }
        assertEquals("lora_config 868 125  7" to 10_000L, runner.calls[0])
        runner.reply(0, true, "")
        until { !s.busy.value }
        assertEquals("LoRa configured.", s.status.value)

        s.configure("868")
        until { runner.calls.size == 2 }
        assertEquals("lora_config 868", runner.cmds[1])
        runner.reply(1, false, "")
        until { !s.busy.value }
        assertEquals("No LoRa module on this device.", s.status.value)
    }

    @Test fun sendRejectsBlankAndMapsResult() = runBlocking {
        val s = svc()
        s.send("   ")
        assertEquals("Enter a message to send.", s.status.value)
        assertTrue(runner.calls.isEmpty())

        s.send(" hi ")
        assertEquals("Sending..." to true, s.status.value to s.busy.value)
        until { runner.calls.size == 1 }
        assertEquals("lora_send hi" to 650_000L, runner.calls[0])
        runner.reply(0, true, "ok")
        until { !s.busy.value }
        assertEquals("ok", s.status.value)
    }

    @Test fun receiveTogglesFlagDeliversPacketOnceAndTimesOutBySeconds() = runBlocking {
        val s = svc()
        s.receive(20)
        assertTrue(s.receiving.value)
        assertEquals("Listening for LoRa packets (20s)...", s.status.value)
        until { json.calls.size == 1 }
        assertEquals("lora_recv 20" to 30_000L, json.calls[0])
        json.reply(0, true, JSONObject().put("out", "").put("data", JSONObject().put("data", "AB")))
        until { !s.receiving.value }
        assertEquals("Receive complete.", s.status.value)
        assertEquals(1, packets.size)
        assertEquals("AB", packets[0].optString("data"))
    }

    @Test fun supersededReceiveNeverDeliversNorClearsNewerFlag() = runBlocking {
        val s = svc()
        s.receive(5)
        until { json.calls.size == 1 }
        s.receive(5) // supersede; first reply must be ignored
        until { json.calls.size == 2 }
        json.reply(0, true, JSONObject().put("data", JSONObject().put("data", "stale")))
        delay(20)
        assertTrue(s.receiving.value)        // newer flag untouched
        assertTrue(packets.isEmpty())        // stale packet dropped
        json.reply(1, true, JSONObject().put("data", JSONObject().put("data", "fresh")))
        until { !s.receiving.value }
        assertEquals(listOf("fresh"), packets.map { it.optString("data") })
    }

    @Test fun stopReceiveClearsFlagLocallyButStillDelivers() = runBlocking {
        val s = svc()
        s.receive(5)
        until { json.calls.size == 1 }
        s.stopReceive()
        assertFalse(s.receiving.value)
        assertEquals(1, json.calls.size)                    // wait not stopped
        assertEquals("Listening for LoRa packets (5s)...", s.status.value)
        json.reply(0, true, JSONObject().put("out", "received 1 packet(s)").put("data", JSONObject().put("data", "X")))
        until { s.status.value == "received 1 packet(s)" }
        assertEquals(listOf("X"), packets.map { it.optString("data") })  // parity: packet still delivered
    }

    @Test fun stopCancelsAndClearsFlagIgnoringLateReply() = runBlocking {
        val s = svc()
        s.receive(5)
        until { json.calls.size == 1 }
        s.stop()
        assertFalse(s.receiving.value)
        until { !s.busy.value }
        assertEquals("Stopped.", s.status.value)
        json.reply(0, true, JSONObject().put("data", JSONObject().put("data", "late")))
        delay(20)
        assertTrue(packets.isEmpty())
        assertFalse(s.receiving.value)
    }
}
