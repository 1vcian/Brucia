package app.brucia.mitm

import android.content.Context
import android.app.Application
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * On-phone HTTP(S) intercepting proxy (Burp / mitmproxy style), the "offload" role.
 *
 * - Plain HTTP (absolute-form request): parsed, forwarded upstream, response relayed,
 *   full transaction logged.
 * - HTTPS (CONNECT): TLS is terminated with a per-host leaf certificate signed by the
 *   Brucia MITM CA, the decrypted request is forwarded to the real upstream over TLS,
 *   and the decrypted request/response are logged. If the TLS handshake with the client
 *   fails (HSTS / certificate pinning, or the CA is not installed/trusted), the
 *   connection is logged as a non-decrypted tunnel instead of crashing.
 *
 * AUTHORIZED USE ONLY: run this against devices and networks you own or are explicitly
 * authorized to test.
 */
class MitmProxy(context: Context, private val port: Int) {
    private val ctx = context.applicationContext as Application

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val pool = ThreadPoolExecutor(4, 8, 60, TimeUnit.SECONDS, ArrayBlockingQueue(32))
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val upstreamFactory = SSLSocketFactory.getDefault() as SSLSocketFactory

    private companion object {
        const val PREVIEW_MAX = 4096
        const val CLIENT_TIMEOUT_MS = 60_000
        const val UPSTREAM_CONNECT_MS = 15_000
        const val UPSTREAM_READ_MS = 30_000
    }

    fun start() {
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
        } catch (error: Exception) { runCatching { s.close() }; throw error }
        server = s
        running = true
        thread(name = "mitm-accept") { acceptLoop(s) }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        pool.shutdownNow()
        MitmCa.clearLeafCache()
    }

    private fun track(socket: Socket): Boolean {
        connections.add(socket)
        if (running) return true
        connections.remove(socket)
        runCatching { socket.close() }
        return false
    }

    private inline fun <S : Socket, T> withUpstream(socket: S, block: (S) -> T): T = socket.use {
        if (!track(socket)) throw IOException("Proxy stopped")
        try { block(socket) } finally { connections.remove(socket) }
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                if (running) continue else break
            }
            if (!track(client)) break
            try {
                pool.execute {
                    try { handle(client) } catch (_: Exception) { }
                    finally { connections.remove(client); runCatching { client.close() } }
                }
            } catch (_: RejectedExecutionException) {
                connections.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = CLIENT_TIMEOUT_MS
        client.use {
            val cin = BufferedInputStream(client.getInputStream())
            val cout = BufferedOutputStream(client.getOutputStream())
            val first = runCatching { readRequest(cin) }.getOrNull() ?: return
            if (first.method.equals("CONNECT", true)) {
                handleConnect(client, cout, first)
            } else {
                handlePlainHttp(cin, cout, first)
            }
        }
    }

    // ---- Plain HTTP (absolute-form request target) --------------------------------

    private fun handlePlainHttp(cin: InputStream, cout: OutputStream, firstReq: HttpRequest) {
        var req: HttpRequest? = firstReq
        while (req != null) {
            val keepAlive = processPlain(req, cout)
            if (!keepAlive) break
            req = runCatching { readRequest(cin) }.getOrNull()
        }
    }

    private fun processPlain(req: HttpRequest, cout: OutputStream): Boolean {
        val id = MitmLog.nextId()
        val started = System.currentTimeMillis()
        val target = parseAbsoluteTarget(req.target)
        if (target == null) {
            MitmLog.add(errorTxn(id, started, req.method, "http", "?", req.target, "Unparseable request target"))
            return false
        }
        val (host, portNum, path) = target
        return try {
            withUpstream(Socket()) { up ->
                up.connect(InetSocketAddress(host, portNum), UPSTREAM_CONNECT_MS)
                up.soTimeout = UPSTREAM_READ_MS
                exchange(
                    id, started, "http", host, req.method, path, req,
                    up.getInputStream(), BufferedOutputStream(up.getOutputStream()),
                    cout, decrypted = true,
                )
            }
            keepAlive(req.version, req.headers)
        } catch (e: Exception) {
            MitmLog.add(errorTxn(id, started, req.method, "http", host, path, "Upstream error: ${e.message}"))
            false
        }
    }

    // ---- HTTPS (CONNECT + TLS termination) ----------------------------------------

    private fun handleConnect(client: Socket, cout: OutputStream, connect: HttpRequest) {
        val hp = connect.target.split(":")
        val host = hp[0]
        val portNum = hp.getOrNull(1)?.toIntOrNull() ?: 443
        val id = MitmLog.nextId()
        val started = System.currentTimeMillis()

        // Acknowledge the tunnel to the client.
        runCatching {
            cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            cout.flush()
        }.getOrElse {
            MitmLog.add(errorTxn(id, started, "CONNECT", "https", host, "", "Failed to ack tunnel"))
            return
        }

        val serverSock = try {
            val sslCtx = MitmCa.serverContextFor(ctx, host)
            val s = sslCtx.socketFactory.createSocket(client, host, portNum, true) as SSLSocket
            try { s.useClientMode = false; s.startHandshake(); s }
            catch (error: Exception) { runCatching { s.close() }; throw error }
        } catch (e: Exception) {
            // Client refused our leaf: HSTS / pinning, or CA not trusted. Metadata only.
            MitmLog.add(
                errorTxn(
                    id, started, "CONNECT", "https", host, "",
                    "TLS not intercepted (HSTS/pinning or CA not trusted): ${e.message}",
                ).copy(decrypted = false, note = "CONNECT tunnel, not decrypted (client rejected the MITM certificate)"),
            )
            return
        }

        serverSock.use {
            val tin = BufferedInputStream(serverSock.inputStream)
            val tout = BufferedOutputStream(serverSock.outputStream)
            var req = runCatching { readRequest(tin) }.getOrNull()
            while (req != null) {
                val keepAlive = processHttps(host, portNum, req, tout)
                if (!keepAlive) break
                req = runCatching { readRequest(tin) }.getOrNull()
            }
        }
    }

    private fun processHttps(host: String, portNum: Int, req: HttpRequest, tout: OutputStream): Boolean {
        val id = MitmLog.nextId()
        val started = System.currentTimeMillis()
        return try {
            val up = upstreamFactory.createSocket() as SSLSocket
            withUpstream(up) {
                up.connect(InetSocketAddress(host, portNum), UPSTREAM_CONNECT_MS)
                up.soTimeout = UPSTREAM_READ_MS
                // Validate both trust chain and hostname of the real upstream.
                up.sslParameters = up.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                up.startHandshake()
                exchange(
                    id, started, "https", host, req.method, req.target, req,
                    up.inputStream, BufferedOutputStream(up.outputStream),
                    tout, decrypted = true,
                )
            }
            keepAlive(req.version, req.headers)
        } catch (e: Exception) {
            MitmLog.add(errorTxn(id, started, req.method, "https", host, req.target, "Upstream TLS error: ${e.message}"))
            false
        }
    }

    // ---- Shared request/response exchange + logging -------------------------------

    private fun exchange(
        id: Long,
        started: Long,
        scheme: String,
        host: String,
        method: String,
        path: String,
        req: HttpRequest,
        upIn: InputStream,
        upOut: OutputStream,
        clientOut: OutputStream,
        decrypted: Boolean,
    ) {
        // Forward request upstream (origin-form, Accept-Encoding stripped for readable bodies).
        upOut.write(buildRequest(method, path, req.version, req.headers, req.body))
        upOut.flush()

        val resp = readResponse(BufferedInputStream(upIn), method)

        // Relay response to the client.
        clientOut.write(buildResponse(resp.version, resp.status, resp.reason, resp.headers, resp.body))
        clientOut.flush()

        MitmLog.add(
            MitmTxn(
                id = id,
                timeMs = started,
                method = method,
                scheme = scheme,
                host = host,
                path = path,
                status = resp.status,
                reqBytes = req.body.size,
                respBytes = resp.body.size,
                durationMs = System.currentTimeMillis() - started,
                decrypted = decrypted,
                note = "",
                reqHeaders = headersToString(req.headers),
                respHeaders = headersToString(resp.headers),
                reqPreview = preview(req.body),
                respPreview = preview(resp.body),
            ),
        )
    }

    private fun errorTxn(
        id: Long, started: Long, method: String, scheme: String, host: String, path: String, note: String,
    ) = MitmTxn(
        id = id, timeMs = started, method = method, scheme = scheme, host = host, path = path,
        status = -1, reqBytes = 0, respBytes = 0, durationMs = System.currentTimeMillis() - started,
        decrypted = false, note = note, reqHeaders = "", respHeaders = "", reqPreview = "", respPreview = "",
    )

    // ---- HTTP parsing / serialization --------------------------------------------

    private fun readRequest(ins: InputStream): HttpRequest? = HttpWire.readRequest(ins)
    private fun readResponse(ins: InputStream, method: String): HttpResponse = HttpWire.readResponse(ins, method)

    private fun buildRequest(
        method: String, path: String, version: String,
        headers: List<Pair<String, String>>, body: ByteArray,
    ): ByteArray {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
        for ((k, v) in headers) {
            if (k.equals("Proxy-Connection", true)) continue
            if (k.equals("Transfer-Encoding", true)) continue
            if (k.equals("Content-Length", true)) continue
            if (k.equals("Accept-Encoding", true)) continue // ask upstream for plaintext
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        if (body.isNotEmpty() || hasBodyMethod(method)) sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun buildResponse(
        version: String, status: Int, reason: String,
        headers: List<Pair<String, String>>, body: ByteArray,
    ): ByteArray {
        val sb = StringBuilder()
        sb.append(version).append(' ').append(status)
        if (reason.isNotEmpty()) sb.append(' ').append(reason)
        sb.append("\r\n")
        for ((k, v) in headers) {
            if (k.equals("Transfer-Encoding", true)) continue
            if (k.equals("Content-Length", true)) continue
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun hasBodyMethod(method: String) =
        method.equals("POST", true) || method.equals("PUT", true) || method.equals("PATCH", true)

    private fun header(headers: List<Pair<String, String>>, name: String): String? =
        headers.firstOrNull { it.first.equals(name, true) }?.second

    private fun keepAlive(version: String, headers: List<Pair<String, String>>): Boolean {
        val conn = header(headers, "Connection")?.lowercase()
        if (conn != null) {
            if (conn.contains("close")) return false
            if (conn.contains("keep-alive")) return true
        }
        return version.endsWith("1.1")
    }

    private fun parseAbsoluteTarget(target: String): Triple<String, Int, String>? {
        val m = Regex("^http://([^/:]+)(?::(\\d+))?(/.*)?$", RegexOption.IGNORE_CASE).find(target) ?: return null
        val host = m.groupValues[1]
        val port = m.groupValues[2].toIntOrNull() ?: 80
        val path = m.groupValues[3].ifEmpty { "/" }
        return Triple(host, port, path)
    }

    private fun headersToString(headers: List<Pair<String, String>>): String =
        headers.joinToString("\n") { "${it.first}: ${it.second}" }

    private fun preview(body: ByteArray): String {
        if (body.isEmpty()) return ""
        val slice = if (body.size > PREVIEW_MAX) body.copyOf(PREVIEW_MAX) else body
        val text = String(slice, Charsets.UTF_8)
        // Mark as binary if it has many non-printable, non-whitespace bytes.
        val nonPrintable = text.count { it.code < 9 || (it.code in 14..31) }
        val head = if (nonPrintable > text.length / 10) "[binary body, ${body.size} bytes]" else text
        return if (body.size > PREVIEW_MAX) "$head\n... [truncated, ${body.size} bytes total]" else head
    }
}
