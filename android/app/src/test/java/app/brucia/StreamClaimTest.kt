package app.brucia

import app.brucia.session.StreamClaim
import org.junit.Assert.*
import org.junit.Test

/**
 * F28/F29: a screen sharing the wifi.ap scan (Channels, RF Fingerprint, RF Timeline, Evil Portal
 * targets) stops on leave only a scan it started, and starts one again on a connect/reconnect.
 */
class StreamClaimTest {
    /** Stand-in for the shared stream: on/off plus every ON/OFF request sent. */
    private class Stream(var on: Boolean = false, var accept: Boolean = true) {
        val sent = mutableListOf<Boolean>()
        fun set(v: Boolean): Boolean {
            sent += v
            if (!accept) return false
            on = v
            return true
        }
    }

    @Test fun scanAlreadyRunningIsNeverStoppedOnLeave() {
        val wifi = Stream(on = true) // Wardrive started it
        val claim = StreamClaim()
        claim.acquire(wifi.on) { wifi.set(true) }
        claim.release(wifi.on) { wifi.set(false) }
        assertTrue(wifi.on)
        assertEquals(emptyList<Boolean>(), wifi.sent)
    }

    @Test fun scanStartedHereIsStoppedOnceOnLeave() {
        val wifi = Stream()
        val claim = StreamClaim()
        claim.acquire(wifi.on) { wifi.set(true) }
        claim.acquire(wifi.on) { wifi.set(true) } // rotation re-binds: no second ON
        assertTrue(claim.owned)
        claim.release(wifi.on) { wifi.set(false) }
        claim.release(wifi.on) { wifi.set(false) } // no second OFF
        assertEquals(listOf(true, false), wifi.sent)
        assertFalse(claim.owned)
    }

    @Test fun stoppedElsewhereGetsNoExtraOff() {
        val wifi = Stream()
        val claim = StreamClaim()
        claim.acquire(wifi.on) { wifi.set(true) }
        wifi.on = false // Stop all / disconnect cleanup
        claim.release(wifi.on) { wifi.set(false) }
        assertEquals(listOf(true), wifi.sent)
    }

    @Test fun reconnectRestartsAndOwnsTheScan() {
        val wifi = Stream(on = true) // owned by Wardrive on entry
        val claim = StreamClaim()
        claim.acquire(wifi.on) { wifi.set(true) }
        assertFalse(claim.owned)
        wifi.on = false // disconnect ends every stream
        claim.acquire(wifi.on) { wifi.set(true) } // reconnect while this screen is open
        assertTrue(claim.owned && wifi.on)
        claim.release(wifi.on) { wifi.set(false) }
        assertEquals(listOf(true, false), wifi.sent)
    }

    @Test fun rejectedStartIsNotOwned() {
        val wifi = Stream(accept = false) // not connected / no confirmed hardware
        val claim = StreamClaim()
        claim.acquire(wifi.on) { wifi.set(true) }
        assertFalse(claim.owned)
        wifi.accept = true
        wifi.on = true // another owner starts it later
        claim.release(wifi.on) { wifi.set(false) }
        assertTrue(wifi.on)
    }
}
