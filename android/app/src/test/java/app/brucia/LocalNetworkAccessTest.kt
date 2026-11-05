package app.brucia

import app.brucia.net.LocalNetworkAccess
import app.brucia.net.LocalNetworkAccess.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.SocketException
import java.net.SocketTimeoutException

class LocalNetworkAccessTest {

    private val p = LocalNetworkAccess.PERMISSION

    @Test
    fun permissionConstantMatchesPlatformName() {
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", p)
        assertEquals(37, LocalNetworkAccess.ENFORCED_SDK)
    }

    @Test
    fun requiredOnlyWhenDeviceAndTargetAreAndroid17() {
        // Today's build: target 36 never asks, on any device.
        for (device in listOf(26, 33, 35, 36, 37, 38)) {
            assertNull("device $device target 36", LocalNetworkAccess.requiredPermission(device, 36))
        }
        // After the target bump: only devices that have the permission (Android 17+).
        assertNull(LocalNetworkAccess.requiredPermission(36, 37))
        assertNull(LocalNetworkAccess.requiredPermission(33, 37))
        assertEquals(p, LocalNetworkAccess.requiredPermission(37, 37))
        assertEquals(p, LocalNetworkAccess.requiredPermission(38, 38))
        assertEquals(p, LocalNetworkAccess.requiredPermission(38, 37))
        assertNull(LocalNetworkAccess.requiredPermission(38, 35))
    }

    @Test
    fun notRequiredIsReadyUnlessProbeBlocked() {
        assertEquals(Status.READY, LocalNetworkAccess.status(null, granted = false, asked = false, canAskAgain = false, probeBlocked = false))
        assertEquals(Status.READY, LocalNetworkAccess.status(null, granted = false, asked = true, canAskAgain = false, probeBlocked = false))
        // Android 16 developer opt-in (RESTRICT_LOCAL_NETWORK): no permission state, only EPERM.
        assertEquals(Status.BLOCKED, LocalNetworkAccess.status(null, granted = false, asked = false, canAskAgain = false, probeBlocked = true))
    }

    @Test
    fun requiredAndNotGrantedAsksThenExplainsDenial() {
        assertEquals(Status.NEEDS_PERMISSION, LocalNetworkAccess.status(p, granted = false, asked = false, canAskAgain = false, probeBlocked = false))
        // Denied once, system still willing to prompt.
        assertEquals(Status.NEEDS_PERMISSION, LocalNetworkAccess.status(p, granted = false, asked = true, canAskAgain = true, probeBlocked = false))
        // Denied and the system will not prompt again: settings only.
        assertEquals(Status.DENIED, LocalNetworkAccess.status(p, granted = false, asked = true, canAskAgain = false, probeBlocked = false))
        // A missing grant wins over a stale probe result.
        assertEquals(Status.DENIED, LocalNetworkAccess.status(p, granted = false, asked = true, canAskAgain = false, probeBlocked = true))
    }

    @Test
    fun grantedIsReadyUnlessProbeBlocked() {
        assertEquals(Status.READY, LocalNetworkAccess.status(p, granted = true, asked = true, canAskAgain = false, probeBlocked = false))
        assertEquals(Status.BLOCKED, LocalNetworkAccess.status(p, granted = true, asked = false, canAskAgain = false, probeBlocked = true))
    }

    @Test
    fun onlyReadyMayStart() {
        assertTrue(LocalNetworkAccess.canStart(Status.READY))
        assertFalse(LocalNetworkAccess.canStart(Status.NEEDS_PERMISSION))
        assertFalse(LocalNetworkAccess.canStart(Status.DENIED))
        assertFalse(LocalNetworkAccess.canStart(Status.BLOCKED))
    }

    @Test
    fun epermIsBlockedTimeoutsAndOtherErrorsAreNot() {
        assertTrue(LocalNetworkAccess.isPermissionDenied(SocketException("sendto failed: EPERM (Operation not permitted)")))
        // Wrapped, as libcore wraps android.system.ErrnoException.
        assertTrue(LocalNetworkAccess.isPermissionDenied(
            SocketException("send failed").apply { initCause(Exception("sendto failed: EPERM (Operation not permitted)")) }))
        assertTrue(LocalNetworkAccess.isPermissionDenied(Exception("connect failed: operation not permitted")))
        assertFalse(LocalNetworkAccess.isPermissionDenied(SocketTimeoutException("connect timed out")))
        assertFalse(LocalNetworkAccess.isPermissionDenied(SocketException("sendto failed: ENETUNREACH (Network is unreachable)")))
        assertFalse(LocalNetworkAccess.isPermissionDenied(SocketException("Permission denied")))
        assertFalse(LocalNetworkAccess.isPermissionDenied(null))
    }

    @Test
    fun probeTargetIsTheSubnetGateway() {
        assertEquals("192.168.1.1", LocalNetworkAccess.probeTarget("192.168.1.57"))
        assertEquals("10.0.2.1", LocalNetworkAccess.probeTarget("10.0.2.16"))
        assertEquals("192.168.4.2", LocalNetworkAccess.probeTarget("192.168.4.1"))
        assertNull(LocalNetworkAccess.probeTarget(null))
        assertNull(LocalNetworkAccess.probeTarget("fe80::1"))
        assertNull(LocalNetworkAccess.probeTarget("192.168.1"))
        assertNull(LocalNetworkAccess.probeTarget("192.168.1.300"))
    }

    /**
     * Tripwire for the target bump. The docs (developer.android.com/privacy-and-security/
     * local-network-permission, "Split permission model") say: targeting 36 or lower, do not
     * declare or request ACCESS_LOCAL_NETWORK; targeting 37+, declare and request it.
     */
    @Test
    fun manifestDeclarationFollowsTargetSdk() {
        val gradle = File("build.gradle.kts").readText()
        val target = Regex("""targetSdk\s*=\s*(\d+)""").find(gradle)!!.groupValues[1].toInt()
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = manifest.contains("\"$p\"")
        if (target >= LocalNetworkAccess.ENFORCED_SDK) {
            assertTrue("targetSdk $target needs <uses-permission android:name=\"$p\" />", declared)
        } else {
            assertFalse("targetSdk $target must not declare $p yet", declared)
        }
    }
}
