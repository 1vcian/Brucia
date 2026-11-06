package app.brucia.mitm

import android.content.Context
import app.brucia.net.WifiLanSelector

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
        // Only on the active Wi-Fi network (not mobile data or a VPN): that is the LAN the target shares.
        val wifi = WifiLanSelector.current(ctx).getOrElse {
            MitmLog.setError("Proxy not started: ${it.message}")
            MitmLog.setRunning(false)
            return false
        }
        return try {
            MitmCa.ensureCa(ctx.applicationContext) // generate the CA up front if needed
            val p = MitmProxy(ctx.applicationContext, port)
            p.start()
            proxy = p
            MitmLog.setPort(port)
            MitmLog.setLocalIp(wifi.ip)
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

    /** This phone's IPv4 on the active Wi-Fi network (what the target points its proxy at), or null. */
    fun wifiIpv4(ctx: Context): String? = WifiLanSelector.current(ctx).getOrNull()?.ip
}
