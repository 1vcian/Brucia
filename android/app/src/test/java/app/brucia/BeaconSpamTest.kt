package app.brucia

import app.brucia.session.BurstLoop
import app.brucia.session.BurstLoopRegistry
import app.brucia.wifi.BeaconSpam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Beacon spam shows the SSID count the device reports in each `wifi_beacon_spam` reply
 * (`beacon spam done (<secs>s, <n> ssids)`, src/companion/wifi.cpp) and falls back to the
 * requested count only while no reply carried one. Reuses [GateRunner] / [until] from
 * DeviceOperationTest.
 */
class BeaconSpamTest {
    @Test fun parsesTheDeviceCountFromTheFirmwareReply() {
        assertEquals(128, BeaconSpam.deviceCount("beacon spam done (8s, 128 ssids)"))
        assertEquals(1, BeaconSpam.deviceCount("beacon spam done (1s, 1 ssids)"))
        assertNull(BeaconSpam.deviceCount(""))
        assertNull(BeaconSpam.deviceCount(null))
        assertNull(BeaconSpam.deviceCount("ok"))
        assertNull(BeaconSpam.deviceCount("beacon spam failed (wifi init)"))
    }

    @Test fun runningTextUsesDeviceCountAndFallsBackToRequested() {
        assertEquals("Broadcasting 256 SSID(s)... (Stop to end)", BeaconSpam.runningText(256, null))
        assertEquals("Broadcasting 256 SSID(s)... (Stop to end)", BeaconSpam.runningText(256, "done"))
        assertEquals("Broadcasting 256 SSID(s) (device count)... (Stop to end)",
            BeaconSpam.runningText(256, "beacon spam done (8s, 256 ssids)"))
        assertEquals("Broadcasting 241 SSID(s) (device count; 256 requested)... (Stop to end)",
            BeaconSpam.runningText(256, "beacon spam done (8s, 241 ssids)"))
    }

    private fun CoroutineScope.loop(runner: GateRunner) = BurstLoop(this, runner, { true }, BurstLoopRegistry())

    @Test(timeout = 5_000) fun statusFollowsTheDeviceCountAndStopStillReplacesIt() = runVirtual {
        val runner = GateRunner()
        val loop = loop(runner)
        BeaconSpam.start(loop, listOf("a", "b", "c"), 8, 12_000)
        until { runner.calls.size == 1 }
        assertEquals("wifi_beacon_spam 8 a\nb\nc" to 12_000L, runner.calls[0])
        // No reply yet: the requested count is all we know.
        assertEquals("Broadcasting 3 SSID(s)... (Stop to end)", loop.status.value)
        // The device split one entry (e.g. a comma) and reports 4.
        runner.reply(0, true, "beacon spam done (8s, 4 ssids)")
        until { runner.calls.size == 2 }
        assertEquals("Broadcasting 4 SSID(s) (device count; 3 requested)... (Stop to end)", loop.status.value)
        // A reply without the count keeps the fallback wording for this burst.
        runner.reply(1, true, "")
        until { runner.calls.size == 3 }
        assertEquals("Broadcasting 3 SSID(s)... (Stop to end)", loop.status.value)
        runner.reply(2, true, "beacon spam done (8s, 3 ssids)")
        until { runner.calls.size == 4 }
        assertEquals("Broadcasting 3 SSID(s) (device count)... (Stop to end)", loop.status.value)
        loop.stop()
        assertFalse(loop.active.value)
        assertEquals("Stopped.", loop.status.value)
    }

    @Test(timeout = 5_000) fun rejectionStillWinsOverTheRunningText() = runVirtual {
        val runner = GateRunner()
        val loop = loop(runner)
        BeaconSpam.start(loop, listOf("x"), 8, 12_000)
        until { runner.calls.size == 1 }
        runner.reply(0, false, "beacon spam failed (wifi init)")
        until { !loop.active.value }
        assertEquals("beacon spam failed (wifi init)", loop.status.value)
    }

    @Test(timeout = 5_000) fun aSupersededRunCannotRewriteTheNewRunsText() = runVirtual {
        val runner = GateRunner().apply { sticky = true }
        val loop = loop(runner)
        BeaconSpam.start(loop, listOf("old"), 8, 12_000)
        until { runner.calls.size == 1 }
        BeaconSpam.start(loop, listOf("n1", "n2"), 8, 12_000) // supersedes the first run
        until { runner.calls.size == 2 }
        runner.reply(0, true, "beacon spam done (8s, 1 ssids)") // late reply of the old run
        delay(20)
        assertTrue(loop.active.value)
        assertEquals("Broadcasting 2 SSID(s)... (Stop to end)", loop.status.value)
        runner.reply(1, true, "beacon spam done (8s, 2 ssids)")
        until { loop.status.value == "Broadcasting 2 SSID(s) (device count)... (Stop to end)" }
        loop.stop()
        runner.gates.forEach { it.complete(true to "") }
    }
}
