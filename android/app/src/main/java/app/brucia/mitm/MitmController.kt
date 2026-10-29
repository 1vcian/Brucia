package app.brucia.mitm

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Process-wide owner of the running [MitmProxy]. Keeps the proxy alive independently of
 * which Compose screen is shown and exposes start/stop to the UI. Run state and the
 * transaction log live in [MitmLog].
 */
object MitmController {

    @Volatile private var proxy: MitmProxy? = null

    @Synchronized
    fun start(ctx: Context, port: Int): Boolean {
        if (proxy != null) return true
        return try {
            MitmCa.ensureCa(ctx.applicationContext) // generate the CA up front if needed
            val p = MitmProxy(ctx.applicationContext, port)
            p.start()
            proxy = p
            MitmLog.setPort(port)
            MitmLog.setLocalIp(localIpv4())
            MitmLog.setError(null)
            MitmLog.setRunning(true)
            true
        } catch (e: Exception) {
            MitmLog.setError("Could not start proxy on port $port: ${e.message}")
            MitmLog.setRunning(false)
            proxy = null
            false
        }
    }

    @Synchronized
    fun stop() {
        proxy?.let { runCatching { it.stop() } }
        proxy = null
        MitmLog.setRunning(false)
    }

    fun isRunning(): Boolean = proxy != null

    /** Best-effort site-local IPv4 of this phone (for the target to point its proxy at). */
    fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
