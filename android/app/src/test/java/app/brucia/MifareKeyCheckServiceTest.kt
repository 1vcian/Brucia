package app.brucia

import app.brucia.nfc.MifareKeyCheckService
import app.brucia.nfc.MifareAttackState
import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.session.CommandRunner
import app.brucia.session.CommandSession
import app.brucia.session.JsonCommandRunner
import app.brucia.transport.Transport
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MifareKeyCheckServiceTest {
    private val key = "FFFFFFFFFFFF"
    private fun keys(count: Int) = (1..count).map { "%012X".format(it) }
    private fun hit(key: String = this.key, sector: Int = 0, type: String = "A") =
        JSONObject().put("sector", sector).put("type", type).put("key", key)
    private fun reply(vararg hits: JSONObject) = JSONObject().put("ok", true).put("found", JSONArray(hits.toList()))
    // v1 firmware reply: carries the tag UID and the explicit complete/partial contract.
    private fun replyV1(uid: String = "DEADBEEF", complete: Boolean = true, vararg hits: JSONObject) =
        JSONObject().put("ok", true).put("v", 1).put("uid", uid).put("complete", complete)
            .put("sectors", 16).put("tested_sectors", if (complete) 16 else 3)
            .put("keys", 48).put("found", JSONArray(hits.toList()))
    private suspend fun until(condition: () -> Boolean) = withTimeout(2000) { while (!condition()) delay(1) }
    private suspend fun finished(service: MifareKeyCheckService) = until { !service.status.value.running }

    @Test(timeout = 5000) fun batchesSnapshotAndNormalizeKeysWithExistingTimeout() = runVirtual {
        val original = (keys(49) + "ffffffffffff" + " FFFFFFFFFFFF ").toMutableList()
        val sent = mutableListOf<String>()
        val service = MifareKeyCheckService(this, { JsonCommandRunner { cmd, timeout ->
            assertEquals(650_000L, timeout)
            sent += cmd
            true to if (sent.size == 1) reply(hit(keys(1)[0])) else reply(hit())
        } })
        service.start(original)
        original.clear()
        finished(service)
        assertEquals(listOf(48, 2), sent.map { it.substringAfter(' ').split(',').size })
        assertTrue(sent.all { it.startsWith("mifare_keycheck ") })
        assertTrue(service.status.value.completed)
        assertEquals(2, service.status.value.batch)
        assertEquals(1, service.status.value.found.size) // same sector/type, updated key
        assertEquals(key, service.status.value.found.single().key)
    }

    @Test(timeout = 5000) fun failureRetainsPriorResultsAndStopsRemainingBatches() = runVirtual {
        var calls = 0
        val service = MifareKeyCheckService(this, { JsonCommandRunner { _, _ ->
            calls++
            if (calls == 1) true to reply(hit(keys(1)[0])) else false to JSONObject().put("out", "tag removed")
        } })
        service.start(keys(100)); finished(service)
        assertEquals(2, calls)
        assertEquals(1, service.status.value.found.size)
        assertEquals("tag removed", service.status.value.message)
        assertFalse(service.status.value.completed)
    }

    @Test(timeout = 5000) fun rejectsMalformedRepliesWithoutPartialCommitOrNextBatch() = runVirtual {
        val invalid = listOf<JSONObject?>(null, JSONObject(), JSONObject().put("found", "bad"),
            reply(hit(sector = -1)), reply(hit(sector = 40)), reply(hit(type = "C")),
            reply(hit("NOTHEX")), reply(hit(keys(1)[0])), reply(hit().put("sector", 0.5)),
            reply(hit(), hit(type = "bad")))
        for (response in invalid) {
            var calls = 0
            val service = MifareKeyCheckService(this, { JsonCommandRunner { _, _ -> calls++; true to response } })
            service.start(listOf(key)); finished(service)
            assertEquals(1, calls)
            assertFalse(service.status.value.completed)
            assertTrue(service.status.value.found.isEmpty())
            assertTrue(service.status.value.message.startsWith("Invalid"))
        }
    }

    @Test(timeout = 5000) fun validationAndMissingConnectionSendNothing() = runVirtual {
        var connections = 0
        val service = MifareKeyCheckService(this, { connections++; null })
        service.start(emptyList()); assertEquals("No keys in dictionary.", service.status.value.message)
        service.start(listOf("FFFFFFFFFFFF\ncmd")); assertEquals(0, connections)
        service.start(listOf(key)); assertEquals(1, connections)
        assertFalse(service.status.value.running)
        assertTrue(service.status.value.message.startsWith("Connect"))
    }

    @Test(timeout = 5000) fun stopResetAndSupersedeIgnoreNonCancellableOldReply() = runVirtual {
        for (action in listOf("stop", "reset", "restart")) {
            var continuation: Continuation<Pair<Boolean, JSONObject?>>? = null
            var calls = 0
            val service = MifareKeyCheckService(this, { JsonCommandRunner { _, _ ->
                calls++
                if (calls == 1) suspendCoroutine { continuation = it } else true to reply()
            } })
            service.start(keys(49)); until { continuation != null }
            when (action) {
                "stop" -> service.stop()
                "reset" -> service.reset()
                else -> { service.start(listOf(key)); finished(service) }
            }
            val expected = service.status.value
            continuation!!.resume(true to reply(hit(keys(1)[0])))
            coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals(expected, service.status.value)
            assertEquals(if (action == "restart") 2 else 1, calls)
            if (action == "reset") assertEquals(MifareAttackState(), expected)
        }
    }

    @Test(timeout = 5000) fun cancelledParentNeverLeavesRunningState() = runVirtual {
        val parent = Job().apply { cancel() }
        var calls = 0
        val service = MifareKeyCheckService(CoroutineScope(coroutineContext + parent), {
            JsonCommandRunner { _, _ -> calls++; true to reply() }
        })
        service.start(listOf(key)); finished(service)
        assertEquals(0, calls)
        assertFalse(service.status.value.completed)
    }

    @Test(timeout = 5000) fun exceptionIsTerminalAndEmptySuccessIsCompleted() = runVirtual {
        var fail = true
        val service = MifareKeyCheckService(this, { JsonCommandRunner { _, _ ->
            if (fail) throw java.io.IOException("read failed") else true to reply()
        } })
        service.start(keys(49)); finished(service)
        assertEquals("read failed", service.status.value.message)
        assertFalse(service.status.value.completed)
        fail = false
        service.start(listOf(key)); finished(service)
        assertTrue(service.status.value.completed)
        assertFalse(service.status.value.completenessKnown)
        assertFalse(service.status.value.exhaustive)
        assertEquals("No keys reported (completeness unknown; update firmware).", service.status.value.message)
    }

    @Test(timeout = 5000) fun v1CompleteReplyMarksExhaustiveWithUid() = runVirtual {
        val service = MifareKeyCheckService(this, { JsonCommandRunner { _, _ ->
            true to replyV1(uid = "04AABBCC", complete = true, hit())
        } })
        service.start(listOf(key)); finished(service)
        val s = service.status.value
        assertTrue(s.completed); assertTrue(s.exhaustive); assertTrue(s.completenessKnown)
        assertFalse(s.partial)
        assertEquals("04AABBCC", s.uid)
        assertEquals(1, s.found.size)
        assertEquals("Done - 1 key(s); all sectors tested.", s.message)
    }

    @Test(timeout = 5000) fun v1PartialBatchStopsSequenceAndKeepsHits() = runVirtual {
        var calls = 0
        val service = MifareKeyCheckService(this, { JsonCommandRunner { _, _ ->
            calls++; true to replyV1(uid = "04AABBCC", complete = false, hit(keys(1)[0]))
        } })
        service.start(keys(100)); finished(service) // two batches; the first is cut short
        val s = service.status.value
        assertEquals(1, calls) // partial batch stops the sequence
        assertFalse(s.completed); assertTrue(s.partial); assertTrue(s.completenessKnown)
        assertFalse(s.exhaustive)
        assertEquals(1, s.found.size) // keys tested before the tag was lost are kept
        assertTrue(s.message.startsWith("Partial scan"))
    }

    @Test(timeout = 5000) fun v1UidChangeBetweenBatchesStopsBeforeMixingCards() = runVirtual {
        var calls = 0
        val service = MifareKeyCheckService(this, { JsonCommandRunner { _, _ ->
            calls++
            if (calls == 1) true to replyV1(uid = "AAAAAAAA", complete = true, hit(keys(1)[0]))
            else true to replyV1(uid = "BBBBBBBB", complete = true, hit())
        } })
        service.start(keys(100)); finished(service) // two batches, different cards
        val s = service.status.value
        assertEquals(2, calls)
        assertFalse(s.completed); assertTrue(s.partial)
        assertEquals("AAAAAAAA", s.uid)
        assertEquals(1, s.found.size) // only the first card's key committed
        assertTrue(s.message.startsWith("Card changed between batches"))
    }

    @Test(timeout = 5000) fun oldFirmwareReplyRunsButIsLabeledUnknown() = runVirtual {
        val service = MifareKeyCheckService(this, { JsonCommandRunner { _, _ -> true to reply(hit()) } })
        service.start(listOf(key)); finished(service)
        val s = service.status.value
        assertTrue(s.completed); assertFalse(s.completenessKnown); assertFalse(s.exhaustive)
        assertEquals("", s.uid)
        assertEquals(1, s.found.size)
        assertEquals("Done - 1 key(s) reported (completeness unknown).", s.message)
    }

    // v1 firmware reply for a batch the device stopped on a polled stop frame.
    private fun replyCancelled(uid: String = "04AABBCC", vararg hits: JSONObject) =
        JSONObject().put("ok", true).put("v", 1).put("uid", uid).put("complete", false)
            .put("stop", "cancelled").put("sectors", 16).put("tested_sectors", 2)
            .put("keys", 48).put("found", JSONArray(hits.toList()))

    // Each stop test runs the service on a dedicated, cancellable scope on the same event loop: its
    // background coroutines (stop-frame send, bounded-wait watchdog, a batch left suspended) are not
    // children of the test body, so cancelling the scope lets the test return without waiting on them.
    @Test(timeout = 5000) fun stopWithCapsSendsFrameAndFinalizesPartialFromCancelledReply() = runVirtual {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            var continuation: Continuation<Pair<Boolean, JSONObject?>>? = null
            val stopCmds = mutableListOf<String>()
            val sender = CommandRunner { cmd, _ -> stopCmds += cmd; true to "" }
            val service = MifareKeyCheckService(scope,
                { JsonCommandRunner { _, _ -> suspendCoroutine { continuation = it } } },
                stopSupported = { true }, stopSender = sender)
            service.start(keys(49)); until { continuation != null }
            service.stop()
            until { stopCmds.isNotEmpty() } // direct stop frame sent
            assertEquals(listOf("mifare_keycheck_stop"), stopCmds)
            assertTrue(service.status.value.running) // still waiting for the cancelled reply
            assertTrue(service.status.value.message.startsWith("Stopping"))
            // The in-flight batch returns its own cancelled ACK with the hits found so far.
            continuation!!.resume(true to replyCancelled("04AABBCC", hit(keys(1)[0])))
            finished(service)
            val s = service.status.value
            assertFalse(s.completed); assertTrue(s.partial); assertFalse(s.exhaustive)
            assertEquals(1, s.found.size) // keys matched before the stop are kept
            assertTrue(s.message.startsWith("Stopped (partial)"))
        } finally { scope.cancel() }
    }

    @Test(timeout = 5000) fun stopWithCapsBoundedWaitForcesPartialWhenNoReply() = runVirtual {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            var entered = false
            val stopCmds = mutableListOf<String>()
            val sender = CommandRunner { cmd, _ -> stopCmds += cmd; true to "" }
            val service = MifareKeyCheckService(scope,
                { JsonCommandRunner { _, _ -> entered = true; suspendCoroutine<Pair<Boolean, JSONObject?>> { } } },
                stopSupported = { true }, stopSender = sender, stopWaitMs = 30)
            service.start(keys(49)); until { entered }
            service.stop()
            finished(service) // no reply ever arrives -> forced partial after the bounded wait
            val s = service.status.value
            assertEquals(listOf("mifare_keycheck_stop"), stopCmds)
            assertFalse(s.completed); assertTrue(s.partial)
            assertTrue(s.message.startsWith("Stopped (partial)"))
        } finally { scope.cancel() }
    }

    @Test(timeout = 5000) fun stopWithoutCapsOnlyStopsSequencingAndSendsNoFrame() = runVirtual {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            var continuation: Continuation<Pair<Boolean, JSONObject?>>? = null
            val stopCmds = mutableListOf<String>()
            val sender = CommandRunner { cmd, _ -> stopCmds += cmd; true to "" }
            val service = MifareKeyCheckService(scope,
                { JsonCommandRunner { _, _ -> suspendCoroutine { continuation = it } } },
                stopSupported = { false }, stopSender = sender)
            service.start(keys(49)); until { continuation != null }
            service.stop()
            finished(service)
            assertTrue(stopCmds.isEmpty()) // old firmware: no in-flight stop, so no frame
            assertTrue(service.status.value.message.startsWith("Batches stopped"))
            // A late ACK after the hard stop is ignored (epoch superseded).
            val expected = service.status.value
            continuation!!.resume(true to reply(hit(keys(1)[0])))
            until { service.status.value == expected }
            assertEquals(expected, service.status.value)
        } finally { scope.cancel() }
    }

    private class Wire : Transport {
        val frames = mutableListOf<JSONObject>()
        override var isOpen = true
        override fun write(bytes: ByteArray) { frames += JSONObject(String(bytes, 4, bytes.size - 4, Charsets.UTF_8)) }
        override fun read(buf: ByteArray, timeoutMs: Int) = 0
        override fun close() { isOpen = false }
    }
    private fun ack(frame: JSONObject, data: JSONObject) = JSONObject().put("v", 0).put("t", Kind.ACK)
        .put("id", frame.getInt("id")).put("d", data)

    @Test(timeout = 5000) fun realSessionTimeoutReleasesPendingAndStopsSequence() = runVirtual {
        val wire = Wire(); val session = CommandSession(CompanionClient(wire), wire)
        val service = MifareKeyCheckService(this, { JsonCommandRunner { cmd, _ -> session.awaitJson(cmd, 30) } })
        service.start(keys(49)); finished(service)
        assertEquals(1, wire.frames.size)
        assertFalse(session.hasPending)
        assertFalse(service.status.value.completed)
        assertEquals("No response from the device.", service.status.value.message)
    }

    @Test(timeout = 5000) fun realSessionStopReleasesPendingAndIgnoresLateAck() = runVirtual {
        val wire = Wire(); val session = CommandSession(CompanionClient(wire), wire)
        val service = MifareKeyCheckService(this, { JsonCommandRunner { cmd, timeout -> session.awaitJson(cmd, timeout) } })
        service.start(keys(49)); until { wire.frames.isNotEmpty() }
        service.stop(); until { !session.hasPending }
        val expected = service.status.value
        session.route(ack(wire.frames.single(), reply(hit(keys(1)[0]))))
        coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(expected, service.status.value)
        assertEquals(1, wire.frames.size)
    }

    @Test(timeout = 5000) fun closedSessionCannotContinueOnReconnectedLink() = runVirtual {
        val oldWire = Wire(); val oldSession = CommandSession(CompanionClient(oldWire), oldWire)
        val newWire = Wire(); val newSession = CommandSession(CompanionClient(newWire), newWire)
        var current = oldSession
        val service = MifareKeyCheckService(this, {
            val captured = current
            JsonCommandRunner { cmd, timeout -> captured.awaitJson(cmd, timeout) }
        })
        service.start(keys(49)); until { oldWire.frames.isNotEmpty() }
        oldSession.close("Disconnected"); current = newSession
        finished(service)
        assertEquals("Disconnected", service.status.value.message)
        assertTrue(newWire.frames.isEmpty())
        service.start(listOf(key)); until { newWire.frames.isNotEmpty() }
        oldSession.route(ack(oldWire.frames.single(), reply(hit(keys(1)[0]))))
        newSession.route(ack(newWire.frames.single(), reply(hit())))
        finished(service)
        assertTrue(service.status.value.completed)
        assertEquals(key, service.status.value.found.single().key)
        assertFalse(oldSession.hasPending)
        assertFalse(newSession.hasPending)
    }

    @Test(timeout = 5000) fun firmwareMissingModuleRejectionStopsAtFirstBatch() = runVirtual {
        val wire = Wire(); val session = CommandSession(CompanionClient(wire), wire)
        val service = MifareKeyCheckService(this, { JsonCommandRunner { cmd, timeout -> session.awaitJson(cmd, timeout) } })
        service.start(keys(49)); until { wire.frames.isNotEmpty() }
        session.route(ack(wire.frames.single(), JSONObject().put("ok", false).put("out", "no RFID module (PN532) found")))
        finished(service)
        assertEquals(1, wire.frames.size)
        assertFalse(service.status.value.completed)
        assertEquals("no RFID module (PN532) found", service.status.value.message)
    }
}
