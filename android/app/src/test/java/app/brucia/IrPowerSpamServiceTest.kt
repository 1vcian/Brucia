package app.brucia

import app.brucia.ir.IrPowerSpamService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** IR power spam: all power tables, repeating on the device; Stop sends the polled stop. */
class IrPowerSpamServiceTest {
    private val runner = GateRunner() // non-sticky: a cancellable await frees on stop()
    @Volatile private var connected = true

    private fun IrPowerSpamService.state() = status.value to busy.value

    @Test fun runSendsClampedSecsWithRunningTextAndTimeout() = runVirtual {
        val s = IrPowerSpamService(this, runner, { connected })
        s.run(5)
        assertEquals("Blasting power codes (5s, Stop to end)..." to true, s.state())
        until { runner.calls.size == 1 }
        assertEquals("ir_power_spam 5" to IrPowerSpamService.timeoutMsFor(5), runner.calls[0])
        runner.reply(0, true, "IR power spam sent 240 codes")
        until { !s.busy.value }
        assertEquals("IR power spam sent 240 codes", s.status.value)
        // Clamp: 0 -> default, over max -> max (auto replies so each run completes at once).
        runner.auto = true to "done"
        s.run(0); until { runner.calls.size == 2 && !s.busy.value }
        assertEquals("ir_power_spam ${IrPowerSpamService.DEFAULT_SECS}", runner.calls[1].first)
        s.run(9999); until { runner.calls.size == 3 && !s.busy.value }
        assertEquals("ir_power_spam ${IrPowerSpamService.MAX_SECS}", runner.calls[2].first)
    }

    @Test fun callWhileBusyIsIgnored() = runVirtual {
        val s = IrPowerSpamService(this, runner, { connected })
        s.run(30); until { runner.calls.size == 1 }
        s.run(30) // ignored while running
        settle()
        assertEquals(1, runner.calls.size)
        s.stop()
    }

    @Test fun requestStopSendsPolledStopAndKeepsWaitingForTheReply() = runVirtual {
        val stopsSent = CopyOnWriteArrayList<String>()
        val s = IrPowerSpamService(this, runner, { connected }, stopRunner = { cmd, _ -> stopsSent += cmd; true to "" })
        s.requestStop() // idle: nothing sent
        settle()
        assertTrue(stopsSent.isEmpty())
        s.run(120); until { runner.calls.size == 1 }
        s.requestStop()
        until { stopsSent.isNotEmpty() }
        assertEquals(listOf(IrPowerSpamService.STOP_CMD), stopsSent)
        assertTrue(s.busy.value) // still owned until the device answers
        runner.reply(0, true, "IR power spam sent 60 codes (stopped)")
        until { !s.busy.value }
        assertEquals("IR power spam sent 60 codes (stopped)", s.status.value)
        connected = false
        s.run(30) // rejected while disconnected
        s.requestStop()
        settle()
        assertEquals(1, stopsSent.size)
    }

    @Test fun notConnectedRejectsWithoutSending() = runVirtual {
        connected = false
        val s = IrPowerSpamService(this, runner, { connected })
        s.run(30)
        assertFalse(s.busy.value)
        settle()
        assertTrue(runner.calls.isEmpty())
    }
}
