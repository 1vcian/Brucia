package app.brucia

import app.brucia.session.CommandRunner
import app.brucia.wifi.EvilPortalService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class EvilPortalServiceTest {
    private val runner = GateRunner().apply { sticky = true }
    @Volatile private var connected = true
    private val onStartCount = AtomicInteger(0)
    /** Template push gates: each start-with-template suspends on one until the test completes it. */
    private val pushGates = CopyOnWriteArrayList<CompletableDeferred<Pair<Boolean, String>>>()
    private val pushPaths = CopyOnWriteArrayList<String>()

    private fun CoroutineScope.svc() = EvilPortalService(
        this, runner, { connected },
        pushTemplate = { path, _ ->
            pushPaths += path
            CompletableDeferred<Pair<Boolean, String>>().also { pushGates += it }.await()
        },
        onStart = { onStartCount.incrementAndGet() },
    )
    private fun EvilPortalService.state() = status.value to active.value

    @Test(timeout = 5_000) fun notConnectedRejectsWithoutStartingOrClearingCreds() = runVirtual {
        connected = false
        val svc = svc()
        svc.start("MyAp")
        assertEquals("Connect the device over USB first." to false, svc.state())
        assertEquals(0, onStartCount.get())
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }

    @Test(timeout = 5_000) fun startWithoutTemplateSendsFourFieldsAndMapsResult() = runVirtual {
        val svc = svc()
        svc.start("MyAp", channel = 20, deauth = true)
        assertEquals("Starting..." to false, svc.state())
        assertEquals(1, onStartCount.get())
        until { runner.calls.size == 1 }
        assertEquals("evil_portal_start MyAp\t\t14\t1" to 650_000L, runner.calls[0])
        assertTrue(svc.busy.value)
        runner.reply(0, true, "AP up")
        until { !svc.busy.value }
        assertEquals("AP up" to true, svc.state())
    }

    @Test(timeout = 5_000) fun blankSsidDefaultsAndFailedStartLeavesInactive() = runVirtual {
        val svc = svc()
        svc.start("   ")
        until { runner.calls.size == 1 }
        assertEquals("evil_portal_start Free Wifi\t\t1\t0", runner.cmds[0])
        runner.reply(0, false, "setup failed")
        until { !svc.busy.value }
        assertEquals("setup failed" to false, svc.state())
    }

    @Test(timeout = 5_000) fun templatePushSuccessPushesThenStarts() = runVirtual {
        val svc = svc()
        svc.start("Portal", "<html>".toByteArray(), "login.html", channel = 6)
        assertEquals("Starting..." to false, svc.state())
        assertEquals(1, onStartCount.get())
        until { pushGates.size == 1 }
        assertEquals("/login.html", pushPaths[0])
        assertTrue(runner.calls.isEmpty())
        pushGates[0].complete(true to "ok")
        until { runner.calls.size == 1 }
        assertEquals("evil_portal_start Portal\t/login.html\t6\t0", runner.cmds[0])
        runner.reply(0, true, "AP up")
        until { !svc.busy.value }
        assertEquals("AP up" to true, svc.state())
    }

    @Test(timeout = 5_000) fun templatePushFailureStartsNothing() = runVirtual {
        val svc = svc()
        svc.start("Portal", "<html>".toByteArray(), "login.html")
        until { pushGates.size == 1 }
        pushGates[0].complete(false to "no space")
        until { svc.status.value == "Template push failed: no space" }
        assertFalse(svc.active.value)
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }

    @Test(timeout = 5_000) fun stopSupersedesPendingStartCommand() = runVirtual {
        val svc = svc()
        svc.start("MyAp")
        until { runner.calls.size == 1 }
        svc.stop()
        until { runner.calls.size == 2 }
        assertEquals(listOf("evil_portal_start MyAp\t\t1\t0", "evil_portal_stop"), runner.cmds)
        runner.reply(1, true, "portal stopped")
        until { !svc.busy.value }
        assertEquals("portal stopped" to false, svc.state())
        // The superseded start's late reply is ignored.
        runner.reply(0, true, "AP up")
        delay(20)
        assertEquals("portal stopped" to false, svc.state())
    }

    @Test(timeout = 5_000) fun stopWinsWhileTemplateIsPushing() = runVirtual {
        val svc = svc()
        svc.start("Portal", "<html>".toByteArray(), "login.html")
        until { pushGates.size == 1 }
        svc.stop()
        until { runner.calls.size == 1 }
        // Only the stop reached the device; the cancelled push never launches the portal.
        pushGates[0].complete(true to "ok")
        runner.reply(0, false, "")
        until { !svc.busy.value }
        assertEquals(listOf("evil_portal_stop"), runner.cmds)
        assertEquals("Stopped." to false, svc.state())
    }
    @Test(timeout = 5_000) fun stopWhileDisconnectedClearsActive() = runVirtual {
        val runner = GateRunner().apply { auto = true to "AP up" }
        var linked = true
        val svc = EvilPortalService(this, runner, { linked }, { _, _ -> true to "" }, {})
        svc.start("Lab")
        until { svc.active.value }
        linked = false
        svc.stop()
        assertFalse(svc.active.value)
        assertEquals(1, runner.calls.size)
    }
}
