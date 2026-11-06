package app.brucia

import app.brucia.ir.TvBGoneService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TvBGoneServiceTest {
    private val runner = GateRunner().apply { sticky = true }

    private fun TvBGoneService.state() = status.value to busy.value

    @Test fun commandTimeoutAndTexts() = runVirtual {
        val s = TvBGoneService(this, runner) { true }
        val cases = listOf(
            Triple("na", true to "TV-B-Gone done (sent 120 codes)", "TV-B-Gone done (sent 120 codes)"),
            Triple("eu", true to "", "TV-B-Gone (eu) done."),
            Triple("na", false to "no IR", "no IR"),
            Triple("eu", false to "", "TV-B-Gone (eu) failed."),
            // Firmware batch 18: nothing sent is ok:false with the count, shown as is.
            Triple("na", false to "TV-B-Gone sent 0 codes", "TV-B-Gone sent 0 codes"),
        )
        cases.forEachIndexed { i, (region, reply, text) ->
            s.run(region)
            assertEquals("TV-B-Gone ($region) running..." to true, s.state())
            until { runner.calls.size == i + 1 }
            runner.reply(i, reply.first, reply.second)
            until { !s.busy.value }
            assertEquals(text, s.status.value)
        }
        assertEquals(
            listOf("na", "eu", "na", "eu", "na").map { "ir_tvbgone $it" to 75_000L },
            runner.calls.toList(),
        )
    }

    @Test fun defaultRegionIsNa() = runVirtual {
        runner.auto = true to ""
        val s = TvBGoneService(this, runner) { true }
        s.run()
        until { !s.busy.value }
        assertEquals("TV-B-Gone (na) done.", s.status.value)
        assertEquals(listOf("ir_tvbgone na"), runner.cmds)
    }

    @Test fun stopIgnoresLateAck() = runVirtual {
        val s = TvBGoneService(this, runner) { true }
        s.run("eu")
        until { runner.calls.size == 1 }
        s.stop()
        assertEquals("Stopped." to false, s.state())
        runner.reply(0, true, "TV-B-Gone done (5 codes)")
        delay(20)
        assertEquals("Stopped." to false, s.state())
    }

    @Test fun notConnectedSendsNothing() = runVirtual {
        val s = TvBGoneService(this, runner) { false }
        s.run("na")
        assertEquals("Connect the device over USB first." to false, s.state())
        delay(20)
        assertTrue(runner.calls.isEmpty())
    }
}
