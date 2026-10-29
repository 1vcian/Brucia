package app.brucia.netscan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** One open service found on a host. */
data class ServicePort(
    val port: Int,
    val service: String,
    val banner: String = "",
    val version: String = "",
    val vulns: List<VulnHit> = emptyList(),
)

/** A discovered host. */
data class ScanHost(
    val ip: String,
    val ports: List<ServicePort> = emptyList(),
)

/** A likely vulnerability matched from a banner/version. */
data class VulnHit(val id: String, val title: String, val severity: String, val url: String)

/**
 * Phone-side LAN scanner (an "nmap-lite"): finds live hosts on the local /24, TCP-connect scans a
 * curated port list, grabs banners for a few common services, extracts a version and flags likely
 * vulnerabilities from a curated heuristic rule set (links to CVE details). All on the phone - no
 * ESP needed - which is exactly the kind of recon Bruce/Flipper can't do on their own.
 */
object NetworkScanner {

    // Curated, high-signal port list (service name per port).
    val PORTS: List<Pair<Int, String>> = listOf(
        21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "dns", 80 to "http",
        110 to "pop3", 111 to "rpcbind", 135 to "msrpc", 139 to "netbios", 143 to "imap",
        443 to "https", 445 to "smb", 554 to "rtsp", 587 to "smtp", 631 to "ipp", 993 to "imaps",
        995 to "pop3s", 1080 to "socks", 1723 to "pptp", 1883 to "mqtt", 3000 to "http-alt",
        3306 to "mysql", 3389 to "rdp", 5000 to "upnp", 5432 to "postgres", 5900 to "vnc",
        6379 to "redis", 7547 to "tr069", 8000 to "http-alt", 8080 to "http-proxy",
        8443 to "https-alt", 8888 to "http-alt", 9000 to "http-alt", 9100 to "printer",
        27017 to "mongodb",
    )

    /** The phone's own IPv4 + prefix length on the active (Wi-Fi) interface, or null. */
    fun localIpv4(): Pair<String, Int>? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress!! to ia.networkPrefixLength.toInt()
                    }
                }
            }
        } catch (e: Exception) { app.brucia.BruciaLog.w("NetScan", "host scan failed", e) }
        return null
    }

    /** The /24 base (e.g. 192.168.1.) of the phone's IPv4, or null if unavailable. */
    fun subnet24(): String? {
        val ip = localIpv4()?.first ?: return null
        val p = ip.lastIndexOf('.')
        return if (p > 0) ip.substring(0, p + 1) else null
    }

    /**
     * Scan the /24 of the phone's subnet. Reports hosts as they complete via [onHost] and overall
     * progress via [onProgress] (done, total). Honors cancellation of the calling coroutine.
     */
    suspend fun scan(
        base: String,
        connectTimeoutMs: Int = 350,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onHost: (ScanHost) -> Unit = {},
    ) = coroutineScope {
        val total = 254
        var done = 0
        val gate = Semaphore(64) // cap concurrent host scans
        val lock = Any()
        for (h in 1..254) {
            val ip = "$base$h"
            launch(Dispatchers.IO) {
                gate.withPermit {
                    val host = scanHost(ip, connectTimeoutMs)
                    synchronized(lock) { done++; onProgress(done, total) }
                    if (host != null) onHost(host)
                }
            }
        }
    }

    /** Scan one host: probe the port list, return it if any port is open. */
    private suspend fun scanHost(ip: String, timeoutMs: Int): ScanHost? {
        val open = ArrayList<ServicePort>()
        val gate = Semaphore(16)
        coroutineScope {
            for ((port, svc) in PORTS) {
                launch(Dispatchers.IO) {
                    gate.withPermit {
                        val sp = probe(ip, port, svc, timeoutMs)
                        if (sp != null) synchronized(open) { open.add(sp) }
                    }
                }
            }
        }
        if (open.isEmpty()) return null
        return ScanHost(ip, open.sortedBy { it.port })
    }

    /** TCP-connect to [ip]:[port]; on success grab a banner and classify. */
    private suspend fun probe(ip: String, port: Int, svc: String, timeoutMs: Int): ServicePort? =
        withContext(Dispatchers.IO) {
            val sock = Socket()
            try {
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
            } catch (_: Exception) {
                try { sock.close() } catch (e: Exception) { app.brucia.BruciaLog.d("NetScan", "socket close: ${e.message}") }
                return@withContext null
            }
            val banner = try { grabBanner(sock, port, ip, timeoutMs) } catch (_: Exception) { "" }
            try { sock.close() } catch (e: Exception) { app.brucia.BruciaLog.d("NetScan", "socket close: ${e.message}") }
            val version = extractVersion(svc, banner)
            ServicePort(port, svc, banner.trim().take(200), version, VulnRules.match(svc, banner, version))
        }

    private suspend fun grabBanner(sock: Socket, port: Int, ip: String, timeoutMs: Int): String =
        withContext(Dispatchers.IO) {
            sock.soTimeout = timeoutMs + 300
            val out: OutputStream = sock.getOutputStream()
            val ins: InputStream = sock.getInputStream()
            // For HTTP-ish ports, send a HEAD to elicit a Server header; others speak first.
            if (port == 80 || port == 8080 || port == 8000 || port == 8888 || port == 3000 || port == 9000) {
                out.write("HEAD / HTTP/1.0\r\nHost: $ip\r\n\r\n".toByteArray()); out.flush()
            } else if (port == 443 || port == 8443) {
                return@withContext "" // TLS: no plaintext banner without a handshake
            }
            val buf = ByteArray(1024)
            val n = withTimeoutOrNull((timeoutMs + 300).toLong()) {
                withContext(Dispatchers.IO) { ins.read(buf) }
            } ?: -1
            if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else ""
        }

    /** Pull a "Server:" header, SSH/FTP version token, etc. out of a banner. */
    private fun extractVersion(svc: String, banner: String): String {
        if (banner.isBlank()) return ""
        Regex("(?i)^Server:\\s*(.+)$", RegexOption.MULTILINE).find(banner)?.let { return it.groupValues[1].trim() }
        if (svc == "ssh") return banner.lineSequence().firstOrNull { it.startsWith("SSH-") }?.trim() ?: ""
        if (svc == "ftp" || svc == "smtp" || svc == "pop3" || svc == "imap")
            return banner.lineSequence().firstOrNull()?.trim() ?: ""
        return ""
    }
}
