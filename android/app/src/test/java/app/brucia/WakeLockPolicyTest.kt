package app.brucia

import app.brucia.service.WakeLockPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeLockPolicyTest {

    @Test fun acquiresOnFirstReleasesOnLast() {
        val p = WakeLockPolicy()
        assertFalse(p.held)
        assertTrue("first reason should acquire", p.add("connection"))
        assertFalse("second reason should not re-acquire", p.add("mic"))
        assertTrue(p.held)
        assertFalse("removing one of two should not release", p.remove("connection"))
        assertTrue(p.held)
        assertTrue("removing the last should release", p.remove("mic"))
        assertFalse(p.held)
    }

    @Test fun duplicateReasonIsIdempotent() {
        val p = WakeLockPolicy()
        assertTrue(p.add("mic"))
        assertFalse("same reason again must not re-acquire", p.add("mic"))
        assertTrue("single remove releases", p.remove("mic"))
        assertFalse(p.held)
    }

    @Test fun removingUnknownReasonIsNoOp() {
        val p = WakeLockPolicy()
        assertFalse(p.remove("nope"))
        p.add("a")
        assertFalse("removing an absent reason never signals release", p.remove("b"))
        assertTrue(p.held)
    }

    @Test fun clearReleasesWhenHeld() {
        val p = WakeLockPolicy()
        p.add("a"); p.add("b")
        assertTrue(p.clear())
        assertFalse(p.held)
        assertFalse("clearing when already released is a no-op", p.clear())
        assertEquals(emptySet<String>(), p.reasons())
    }
}
