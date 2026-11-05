package app.brucia

import app.brucia.session.*
import app.brucia.ble.BleHidService
import app.brucia.ble.GattService
import app.brucia.audio.SpeakerService
import app.brucia.net.EthernetService
import app.brucia.wifi.EvilPortalService
import app.brucia.protocol.CompanionClient
import app.brucia.transport.Transport
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LifecycleRegressionTest {
    @Test(timeout = 5000) fun cancelledScopeClearsDeviceOperationBusyBeforeAndDuringRun() = runVirtual {
        for (before in listOf(true, false)) {
            val parent = Job()
            if (before) parent.cancel()
            var entered = false
            val op = DeviceOperation(CoroutineScope(coroutineContext + parent), CommandRunner { _, _ ->
                entered = true; awaitCancellation()
            }, { true })
            op.start("fixture", "running") { _, _ -> "finished" }
            if (!before) { until { entered }; parent.cancel() }
            parent.join()
            until { !op.busy.value }
            assertFalse(op.busy.value)
            assertEquals("Stopped.", op.status.value)
        }
    }

    @Test(timeout = 5000) fun obsoleteMapperCannotRunSideEffectsAfterStopOrRestart() = runVirtual {
        val replies = mutableListOf<Continuation<Pair<Boolean, String>>>()
        var effects = 0
        val op = DeviceOperation(this, CommandRunner { _, _ -> suspendCoroutine { replies += it } }, { true })
        op.start("old", "old") { _, _ -> effects++; "old" }
        until { replies.size == 1 }
        val oldJob = coroutineContext[Job]!!.children.single()
        op.stop()
        op.start("new", "new") { _, _ -> effects++; "new done" }
        until { replies.size == 2 }
        replies[0].resume(true to "old"); oldJob.join()
        assertEquals(0, effects)
        assertTrue(op.busy.value)
        replies[1].resume(true to "new"); until { !op.busy.value }
        assertEquals(1, effects)
        assertEquals("new done", op.status.value)
    }

    @Test(timeout = 5000) fun mapperExceptionReportsFailureAndClearsBusy() = runVirtual {
        val op = DeviceOperation(this, CommandRunner { _, _ -> true to "ok" }, { true })
        op.start("fixture", "running") { _, _ -> throw IllegalStateException("bad reply") }
        until { !op.busy.value }
        assertEquals("Operation failed: bad reply", op.status.value)
    }

    @Test(timeout = 5000) fun ethernetLateConfigCannotLaunchRefreshAfterCleanup() = runVirtual {
        var reply: Continuation<Pair<Boolean, String>>? = null
        var refreshes = 0
        val ethernet = EthernetService(this, CommandRunner { _, _ -> suspendCoroutine { reply = it } },
            JsonCommandRunner { _, _ -> refreshes++; true to JSONObject() }, { true })
        ethernet.configure("fixture"); until { reply != null }
        ethernet.stop()
        reply!!.resume(true to "configured")
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(0, refreshes)
        assertFalse(ethernet.busy.value)
        assertEquals("Stopped.", ethernet.message.value)
    }

    @Test(timeout = 5000) fun commandGroupCancelsQueuedWritesAndAllowsNewWork() = runVirtual {
        val calls = mutableListOf<String>(); val callbacks = mutableListOf<String>()
        val tasks = CommandTasks(this)
        val runner = CommandRunner { cmd, _ -> calls += cmd; true to cmd }
        tasks.run(runner, "old", 1) { _, out -> callbacks += out }
        tasks.cancel() // before launch gets a turn
        tasks.run(runner, "new", 1) { _, out -> callbacks += out }
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(listOf("new"), calls)
        assertEquals(listOf("new"), callbacks)
    }

    @Test(timeout = 5000) fun commandGroupIgnoresNonCancellableLateCallback() = runVirtual {
        var reply: Continuation<Pair<Boolean, String>>? = null
        var callbacks = 0
        val tasks = CommandTasks(this)
        tasks.run(CommandRunner { _, _ -> suspendCoroutine { reply = it } }, "fixture", 100) { _, _ -> callbacks++ }
        until { reply != null }; tasks.cancel()
        reply!!.resume(true to "late")
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(0, callbacks)
    }

    @Test(timeout = 5000) fun bleRefreshCannotRestoreOldStatusAfterStop() = runVirtual {
        var reply: Continuation<Pair<Boolean, String>>? = null
        val hid = BleHidService(this, CommandRunner { _, _ -> suspendCoroutine { reply = it } }, { true })
        hid.refresh(); until { reply != null }; hid.stop()
        reply!!.resume(true to "advertising")
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals("Stopped.", hid.status.value)
        assertFalse(hid.busy.value)
    }

    @Test(timeout = 5000) fun portalAbandonPreventsLateTemplateFromStartingOnReconnect() = runVirtual {
        var upload: Continuation<Pair<Boolean, String>>? = null
        var sends = 0
        val portal = EvilPortalService(this, CommandRunner { _, _ -> sends++; true to "ok" }, { true },
            { _, _ -> suspendCoroutine { upload = it } }, {})
        portal.start("fixture", byteArrayOf(1), "fixture.html")
        until { upload != null }; portal.abandon()
        upload!!.resume(true to "uploaded")
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(0, sends)
        assertFalse(portal.active.value)
        assertEquals("Disconnected", portal.status.value)
    }

    private class Wire : Transport {
        var writes = 0
        override var isOpen = true
        override fun write(bytes: ByteArray) { writes++ }
        override fun read(buf: ByteArray, timeoutMs: Int) = 0
        override fun close() { isOpen = false }
    }

    @Test(timeout = 5000) fun combinedDisconnectReleasesPendingAndResetsBackgroundState() = runVirtual {
        val wire = Wire(); val session = CommandSession(CompanionClient(wire), wire)
        val runner = CommandRunner { cmd, timeout -> session.await(cmd, timeout) }
        val hid = BleHidService(this, runner, { true })
        val gatt = GattService(this, runner, { true }, {})
        val speaker = SpeakerService(this, runner, { true })
        val portal = EvilPortalService(this, runner, { true }, { _, _ -> true to "ok" }, {})
        val cleanup = ServiceLifecycle(listOf(
            ServiceLifecycle.Entry("hid", hid::stop), ServiceLifecycle.Entry("gatt", gatt::stop),
            ServiceLifecycle.Entry("speaker", speaker::stop), ServiceLifecycle.Entry("portal", portal::stop, portal::abandon),
        ))
        hid.refresh(); gatt.connect("fixture"); gatt.read("fixture", "uuid")
        speaker.playSong("fixture", "fixture"); speaker.dtmf('1'); portal.start("fixture")
        until { wire.writes == 6 }
        assertTrue(session.hasPending)
        cleanup.disconnect()
        coroutineContext[Job]!!.children.toList().joinAll()
        assertFalse(session.hasPending)
        assertNull(gatt.mac.value); assertTrue(gatt.reads.value.isEmpty())
        assertEquals("", speaker.songPlaying.value); assertFalse(portal.active.value)
        assertEquals(6, wire.writes) // disconnect cleanup never writes another command
    }

    @Test fun cleanupContinuesAfterOneFailureAndUsesSeparateDisconnectHooks() {
        val calls = mutableListOf<String>(); val errors = mutableListOf<String>()
        val cleanup = ServiceLifecycle(listOf(
            ServiceLifecycle.Entry("bad", { throw IllegalStateException("failure") }, { calls += "drop bad" }),
            ServiceLifecycle.Entry("good", { calls += "stop good" }, { calls += "drop good" }),
        )) { name, _ -> errors += name }
        cleanup.stopAll(); cleanup.disconnect()
        assertEquals(listOf("bad"), errors)
        assertEquals(listOf("stop good", "drop bad", "drop good"), calls)
    }
}
