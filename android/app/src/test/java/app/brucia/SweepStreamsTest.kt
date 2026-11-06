package app.brucia

import org.junit.Assert.assertEquals
import org.junit.Test

/** Bug Sweep starts only the streams that are off and, on leave, stops only those it started. */
class SweepStreamsTest {
    private val calls = mutableListOf<String>()
    private fun start(want: SweepBands, running: SweepBands) = SweepStreams.start(
        want, running, { calls += "wifi on" }, { calls += "ble on" }, { calls += "sub on" })
    private fun stop(started: SweepBands, running: SweepBands) = SweepStreams.stop(
        started, running, { calls += "wifi off" }, { calls += "ble off" }, { calls += "sub off" })

    private val all = SweepBands(wifi = true, ble = true, sub = true)

    @Test fun bleScanAlreadyRunningIsNeitherRestartedNorStopped() {
        val started = start(all, running = SweepBands(ble = true))
        assertEquals(SweepBands(wifi = true, ble = false, sub = true), started)
        stop(started, running = all)
        assertEquals(listOf("sub on", "wifi on", "sub off", "wifi off"), calls)
    }

    @Test fun everythingStartedHereIsStoppedOnLeave() {
        val started = start(all, running = SweepBands())
        assertEquals(all, started)
        stop(started, running = all)
        assertEquals(listOf("sub on", "ble on", "wifi on", "sub off", "ble off", "wifi off"), calls)
    }

    @Test fun unwantedBandsAreLeftAloneAndStoppedStreamsGetNoSecondOff() {
        val started = start(SweepBands(ble = true), running = SweepBands())
        assertEquals(SweepBands(ble = true), started)
        stop(started, running = SweepBands()) // already stopped (e.g. disconnect cleanup)
        assertEquals(listOf("ble on"), calls)
    }
}
