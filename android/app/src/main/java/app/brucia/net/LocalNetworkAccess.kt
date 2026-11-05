package app.brucia.net

/**
 * Local network protection (LNP) gating for the phone-side LAN tools (Network Scan probes LAN
 * hosts over TCP; the MITM proxy accepts LAN clients and may connect to LAN servers).
 *
 * Per developer.android.com/privacy-and-security/local-network-permission (Android 17
 * enforcement and the split permission model):
 *  - Outgoing and incoming TCP, and UDP unicast/multicast/broadcast in both directions, to or
 *    from a local network address need `ACCESS_LOCAL_NETWORK` (NEARBY_DEVICES group, added in
 *    API 37). DNS to a LAN resolver on port 53 is exempt; internet traffic is unaffected.
 *  - Enforced only for apps targeting 37+ running on Android 17+. An app targeting 36 or lower
 *    keeps implicit access through INTERNET and must NOT declare or request the permission.
 *  - Android 16 had a developer opt-in (`am compat enable RESTRICT_LOCAL_NETWORK`), now
 *    deprecated; under it the app cannot see a permission state, only the socket errors
 *    (TCP connect times out, UDP send fails with EPERM).
 *
 * So the decision is: request [PERMISSION] only when both the device and this app's target are
 * at [ENFORCED_SDK]; independently, a UDP probe that fails with EPERM means the platform is
 * blocking the LAN anyway (opt-in on 16, revoked or policy-blocked on 17) and the tool shows a
 * blocked state instead of running a scan that can only time out.
 */
object LocalNetworkAccess {
    /** `Manifest.permission.ACCESS_LOCAL_NETWORK` (API 37); a literal so lower API levels link. */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Android 17: first platform with the permission and first target SDK that enforces it. */
    const val ENFORCED_SDK = 37

    /**
     * The runtime permission to hold before LAN traffic, or null when the platform grants LAN
     * access implicitly (device below Android 17, or this app still targeting 36 or lower).
     */
    fun requiredPermission(deviceSdk: Int, targetSdk: Int): String? =
        if (deviceSdk >= ENFORCED_SDK && targetSdk >= ENFORCED_SDK) PERMISSION else null

    /** What a LAN tool shows next to its start control. */
    enum class Status {
        /** LAN access is available (implicit, or the permission is granted): tool enabled. */
        READY,
        /** Permission needed and still requestable: explain why, then offer the system prompt. */
        NEEDS_PERMISSION,
        /** Permission denied and the system will not prompt again: point to app settings. */
        DENIED,
        /** The platform blocks LAN traffic although no prompt is possible (EPERM seen). */
        BLOCKED,
    }

    /**
     * Gate state for a LAN tool.
     *
     * @param required result of [requiredPermission]
     * @param granted whether [required] is currently granted (ignored when [required] is null)
     * @param asked whether this app already showed the system prompt for it in this install
     * @param canAskAgain `shouldShowRequestPermissionRationale` after a denial
     * @param probeBlocked last LAN probe failed with EPERM (cleared on retry or when granted)
     */
    fun status(
        required: String?,
        granted: Boolean,
        asked: Boolean,
        canAskAgain: Boolean,
        probeBlocked: Boolean,
    ): Status = when {
        required != null && !granted -> if (!asked || canAskAgain) Status.NEEDS_PERMISSION else Status.DENIED
        probeBlocked -> Status.BLOCKED
        else -> Status.READY
    }

    /** True when the tool's start control may run (the gate shows nothing extra). */
    fun canStart(status: Status): Boolean = status == Status.READY

    /**
     * True when [error] (or any cause) is the kernel refusing the operation with EPERM, which is
     * how a blocked UDP send surfaces (SocketException "sendto failed: EPERM (Operation not
     * permitted)" wrapping android.system.ErrnoException). A timeout is never classified as
     * blocked: TCP to a LAN host that is simply down looks the same.
     */
    fun isPermissionDenied(error: Throwable?): Boolean {
        var e = error
        var depth = 0
        while (e != null && depth < 8) {
            val msg = e.message.orEmpty()
            if (msg.contains("EPERM") || msg.contains("Operation not permitted", ignoreCase = true)) return true
            e = e.cause
            depth++
        }
        return false
    }

    /**
     * A LAN address to probe for the subnet that [localIpv4] belongs to: the conventional
     * gateway (.1) of its /24, or .2 when the phone itself is .1. Null for a non-IPv4 input.
     */
    fun probeTarget(localIpv4: String?): String? {
        val parts = localIpv4?.split('.') ?: return null
        if (parts.size != 4 || parts.any { p -> p.toIntOrNull()?.let { it in 0..255 } != true }) return null
        val base = parts.take(3).joinToString(".")
        return if (parts[3] == "1") "$base.2" else "$base.1"
    }
}
