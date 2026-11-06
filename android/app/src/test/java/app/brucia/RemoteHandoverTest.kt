package app.brucia

import app.brucia.automation.Macro
import app.brucia.automation.MacroRunner
import app.brucia.automation.MacroStep
import app.brucia.device.ScriptService
import app.brucia.files.DeviceFileReader
import app.brucia.files.FileTransferService
import app.brucia.files.SessionFileLink
import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.remote.RemoteHandover
import app.brucia.session.CommandRunner
import app.brucia.session.CommandSession
import app.brucia.subghz.DeBruijnTx
import app.brucia.subghz.DeBruijnTxService
import app.brucia.transport.Transport
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Starting Remote: running macro, script and upload are ended through their own stop paths and
 * their stop frames are on the wire before `companion_exit` (the device stops answering framed
 * commands after it). One real CommandSession over a recording transport.
 */
class RemoteHandoverTest {
    private class Wire : Transport {
        val frames = mutableListOf<JSONObject>()
        var onWrite: (JSONObject) -> Unit = {}
        override var isOpen = true
        override fun write(bytes: ByteArray) {
            val frame = JSONObject(String(bytes, 4, bytes.size - 4, Charsets.UTF_8))
            frames += frame; onWrite(frame)
        }
        override fun read(buf: ByteArray, timeoutMs: Int) = 0
        override fun close() { isOpen = false }
        val cmds get() = frames.mapNotNull { it.optJSONObject("d")?.optString("cmd")?.takeIf { c -> c.isNotEmpty() } }
    }

    private class Fixture(scope: VirtualTestScope) {
        val wire = Wire()
        val client = CompanionClient(wire)
        val session = CommandSession(client, wire)
        val runner = CommandRunner { cmd, timeout -> session.await(cmd, timeout) }
        /** As the VM's directStop: written, never awaited. */
        val direct = CommandRunner { cmd, _ -> client.cmd(cmd); true to "" }
        val macros = MacroRunner(scope, { runner })
        val script = ScriptService(scope, runner, { true }, direct)
        val link = SessionFileLink(session, DeviceFileReader(), Mutex(), { true }, writeTimeoutMs = 60_000,
            transactionalUploads = true, commitTimeoutMs = 60_000)
        val transfers = FileTransferService(scope, { link }, scope.virtualUnconfined)
        val deBruijn = DeBruijnTxService(scope, runner, { true })
        var uploadResult: Result<Unit>? = null
        val handover = RemoteHandover(listOf(
            RemoteHandover.Step("macro", { macros.running.value }) { macros.stop("Macro stopped: Remote control took over the device.") },
            RemoteHandover.Step("script", { script.busy.value }) { script.stopForHandover(RemoteHandover.REASON) },
            RemoteHandover.Step("file transfer", { transfers.active }) { transfers.cancelAllAndJoin() },
            RemoteHandover.Step("De Bruijn sweep", { deBruijn.active.value }) {
                deBruijn.stop("De Bruijn sweep stopped: Remote control took over the device; no further bursts will be sent, but a burst already sent may still be transmitting.")
            },
        ))

        init {
            // The device acks the upload begin and its abort; script_run, the chunk and the macro
            // step stay unanswered (still running when Remote starts).
            wire.onWrite = { frame ->
                val cmd = frame.optJSONObject("d")?.optString("cmd").orEmpty()
                val words = cmd.split(' ')
                if (words[0] == "fs_upload_begin" || words[0] == "fs_upload_abort") {
                    val token = words[1]
                    session.route(JSONObject().put("v", 0).put("t", Kind.ACK).put("id", frame.getInt("id"))
                        .put("d", JSONObject().put("ok", true).put("token", token).put("size", 0)))
                }
            }
        }
    }

    @Test(timeout = 5_000) fun runningOperationsAreStoppedBeforeCompanionExit() = runVirtual {
        val f = Fixture(this)
        f.macros.start(Macro("m", listOf(MacroStep("ir tx_from_file /long.ir", 0))))
        assertTrue(f.script.run("while (true) { delay(100); }"))
        f.transfers.submit("upload:/a.txt", work = { write("/a.txt", ByteArray(10)) }) { f.uploadResult = it }
        until { f.wire.cmds.any { it.startsWith("fs_upload_chunk") } && f.wire.cmds.any { it.startsWith("script_run") } }
        assertTrue(f.macros.running.value && f.script.busy.value && f.transfers.active)

        val stopped = f.handover.stopRunning()
        f.client.cmd("companion_exit")

        assertEquals(listOf("macro", "script", "file transfer"), stopped)
        val cmds = f.wire.cmds
        val exit = cmds.indexOf("companion_exit")
        assertEquals(cmds.size - 1, exit)
        assertTrue(cmds.toString(), cmds.indexOf("script_stop") in 0 until exit)
        assertTrue(cmds.toString(), cmds.indexOfFirst { it.startsWith("fs_upload_abort ") } in 0 until exit)
        assertEquals(1, cmds.count { it == "script_stop" })
        assertFalse(f.macros.running.value || f.script.busy.value || f.transfers.active)
        assertEquals(RemoteHandover.REASON, f.script.output.value)
        assertTrue(f.macros.status.value.contains("Remote control took over"))
        assertNull(f.uploadResult) // cancelled: never reported as saved
    }

    @Test(timeout = 5_000) fun inFlightDeBruijnSweepIsStoppedBeforeCompanionExitAndSendsNoMoreBursts() = runVirtual {
        val f = Fixture(this)
        // A real multi-burst sweep; the device never ACKs subghz_tx_raw, so the first burst stays in
        // flight (dispatched, awaiting its ACK) exactly when Remote takes over.
        val plan = DeBruijnTx.plan(4, 2000, 433_920_000L, 1, 380) as DeBruijnTx.Plan.Ready
        assertTrue("fixture needs a multi-burst sweep", plan.commands.size >= 2)
        f.deBruijn.start(plan, "433.92 MHz")
        until { f.wire.cmds.any { it.startsWith("subghz_tx_raw") } }
        assertTrue(f.deBruijn.active.value)
        val burstsBefore = f.wire.cmds.count { it.startsWith("subghz_tx_raw") }

        val stopped = f.handover.stopRunning()
        f.client.cmd("companion_exit")

        // The hand-over owns the De Bruijn sweep now, not only burst loops/macros/scripts.
        assertEquals(listOf("De Bruijn sweep"), stopped)
        assertFalse(f.deBruijn.active.value)
        // No further burst went out after the hand-over, and companion_exit is last on the wire.
        val cmds = f.wire.cmds
        assertEquals(burstsBefore, cmds.count { it.startsWith("subghz_tx_raw") })
        assertEquals(cmds.size - 1, cmds.indexOf("companion_exit"))
        // Honest status: later bursts cancelled, the in-flight one is not claimed stopped on-device.
        assertTrue(f.deBruijn.status.value, f.deBruijn.status.value.contains("may still be transmitting"))
    }

    @Test(timeout = 5_000) fun nothingRunningSendsNothing() = runVirtual {
        val f = Fixture(this)
        assertEquals(emptyList<String>(), f.handover.stopRunning())
        assertTrue(f.wire.frames.isEmpty())
    }

    @Test(timeout = 5_000) fun aStopAlreadySentIsNotRepeated() = runVirtual {
        val f = Fixture(this)
        assertTrue(f.script.run("x"))
        until { f.wire.cmds.isNotEmpty() }
        assertTrue(f.script.stop())
        until { f.wire.cmds.contains("script_stop") }
        f.handover.stopRunning()
        assertEquals(1, f.wire.cmds.count { it == "script_stop" })
        assertFalse(f.script.busy.value)
    }
}
