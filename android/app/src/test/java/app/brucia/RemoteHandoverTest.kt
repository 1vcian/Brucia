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
        var uploadResult: Result<Unit>? = null
        val handover = RemoteHandover(listOf(
            RemoteHandover.Step("macro", { macros.running.value }) { macros.stop("Macro stopped: Remote control took over the device.") },
            RemoteHandover.Step("script", { script.busy.value }) { script.stopForHandover(RemoteHandover.REASON) },
            RemoteHandover.Step("file transfer", { transfers.active }) { transfers.cancelAllAndJoin() },
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
