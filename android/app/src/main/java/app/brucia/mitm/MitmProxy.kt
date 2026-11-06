package app.brucia.mitm

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * On-phone HTTP(S) intercepting proxy (Burp / mitmproxy style), the "offload" role.
 *
 * - Plain HTTP (absolute-form request): parsed, forwarded upstream, response relayed.
 *   Bodies of any size stream through ([HttpRelay]); the log keeps headers and the first
 *   [BodyCapture.PREVIEW_MAX] bytes of each body, marked when truncated.
 * - HTTPS (CONNECT): TLS is terminated with a per-host leaf certificate signed by the
 *   Brucia MITM CA, the decrypted request is forwarded to the real upstream over TLS,
 *   and the decrypted request/response are logged. If the TLS handshake with the client
 *   fails (HSTS / certificate pinning, or the CA is not installed/trusted), the
 *   connection is logged as a non-decrypted tunnel instead of crashing.
 *
 * AUTHORIZED USE ONLY: run this against devices and networks you own or are explicitly
 * authorized to test.
 */
class MitmProxy internal constructor(
    private val port: Int,
    private val bindAddress: InetAddress?,
    private val serverContextFor: (String) -> SSLContext,
) {
    /** The production proxy: listens on all interfaces (LAN clients), leaves signed by the Brucia CA. */
    constructor(context: Context, port: Int) : this(port, null, run {
        val app = context.applicationContext
        val f: (String) -> SSLContext = { host -> MitmCa.serverContextFor(app, host) }
        f
    })

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val pool = ThreadPoolExecutor(4, 8, 60, TimeUnit.SECONDS, ArrayBlockingQueue(32))
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val upstreamFactory = SSLSocketFactory.getDefault() as SSLSocketFactory

    /** Port actually bound (differs from the requested one when that was 0). */
    val localPort: Int get() = server?.localPort ?: -1

    private companion object {
        const val CLIENT_TIMEOUT_MS = 60_000
        const val UPSTREAM_CONNECT_MS = 15_000
        const val UPSTREAM_READ_MS = 30_000
    }

    fun start() {
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(bindAddress, port))
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
            val first = runCatching { HttpWire.readRequestHead(cin) }.getOrNull() ?: return
            if (first.method == "CONNECT") {
                handleConnect(client, cout, first)
            } else {
                handlePlainHttp(cin, cout, first)
            }
        }
    }

    // ---- Plain HTTP (absolute-form request target) --------------------------------

    private fun handlePlainHttp(cin: InputStream, cout: OutputStream, firstReq: RequestHead) {
        var req: RequestHead? = firstReq
        while (req != null) {
            val keepAlive = processPlain(req, cin, cout)
            if (!keepAlive) break
            req = runCatching { HttpWire.readRequestHead(cin) }.getOrNull()
        }
    }

    private fun processPlain(req: RequestHead, cin: InputStream, cout: OutputStream): Boolean {
        val started = System.currentTimeMillis()
        val target = parseAbsoluteTarget(req.target)
        if (target == null) {
            MitmLog.add(errorTxn(started, req.method, "http", "?", req.target, "Unparseable request target"))
            return false
        }
        val (host, portNum, path) = target
        return RequestLog().run(
            failed = { e -> errorTxn(started, req.method, "http", host, path, "Upstream error: ${e.message}") },
            onFailure = false,
        ) { log ->
            var reusable = false
            withUpstream(Socket()) { up ->
                up.connect(InetSocketAddress(host, portNum), UPSTREAM_CONNECT_MS)
                up.soTimeout = UPSTREAM_READ_MS
                reusable = exchange(
                    log, started, "http", host, path, req, cin,
                    up.getInputStream(), BufferedOutputStream(up.getOutputStream()),
                    cout, decrypted = true,
                )
            }
            reusable && keepAlive(req.version, req.headers)
        }
    }

    // ---- HTTPS (CONNECT + TLS termination) ----------------------------------------

    private fun handleConnect(client: Socket, cout: OutputStream, connect: RequestHead) {
        val hp = connect.target.split(":")
        val host = hp[0]
        val portNum = hp.getOrNull(1)?.toIntOrNull() ?: 443
        val started = System.currentTimeMillis()

        // Acknowledge the tunnel to the client.
        runCatching {
            cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            cout.flush()
        }.getOrElse {
            MitmLog.add(errorTxn(started, "CONNECT", "https", host, "", "Failed to ack tunnel"))
            return
        }

        val serverSock = try {
            val sslCtx = serverContextFor(host)
            val s = sslCtx.socketFactory.createSocket(client, host, portNum, true) as SSLSocket
            try { s.useClientMode = false; s.startHandshake(); s }
            catch (error: Exception) { runCatching { s.close() }; throw error }
        } catch (e: Exception) {
            // Client refused our leaf: HSTS / pinning, or CA not trusted. Metadata only.
            MitmLog.add(
                errorTxn(
                    started, "CONNECT", "https", host, "",
                    "TLS not intercepted (HSTS/pinning or CA not trusted): ${e.message}",
                ).copy(decrypted = false, note = "CONNECT tunnel, not decrypted (client rejected the MITM certificate)"),
            )
            return
        }

        serverSock.use {
            val tin = BufferedInputStream(serverSock.inputStream)
            val tout = BufferedOutputStream(serverSock.outputStream)
            var req = runCatching { HttpWire.readRequestHead(tin) }.getOrNull()
            while (req != null) {
                val keepAlive = processHttps(host, portNum, req, tin, tout)
                if (!keepAlive) break
                req = runCatching { HttpWire.readRequestHead(tin) }.getOrNull()
            }
        }
    }

    private fun processHttps(host: String, portNum: Int, req: RequestHead, tin: InputStream, tout: OutputStream): Boolean {
        val started = System.currentTimeMillis()
        return RequestLog().run(
            failed = { e -> errorTxn(started, req.method, "https", host, req.target, "Upstream TLS error: ${e.message}") },
            onFailure = false,
        ) { log ->
            val up = upstreamFactory.createSocket() as SSLSocket
            var reusable = false
            withUpstream(up) {
                up.connect(InetSocketAddress(host, portNum), UPSTREAM_CONNECT_MS)
                up.soTimeout = UPSTREAM_READ_MS
                // Validate both trust chain and hostname of the real upstream.
                up.sslParameters = up.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                up.startHandshake()
                reusable = exchange(
                    log, started, "https", host, req.target, req, tin,
                    up.inputStream, BufferedOutputStream(up.outputStream),
                    tout, decrypted = true,
                )
            }
            reusable && keepAlive(req.version, req.headers)
        }
    }

    // ---- Shared request/response exchange + logging -------------------------------

    /**
     * Stream the request (head + body from [clientIn]) upstream and the response back to
     * [clientOut]; bodies of any size pass through, the log keeps a bounded prefix of each.
     * Returns whether the client connection can carry another request.
     */
    private fun exchange(
        log: RequestLog,
        started: Long,
        scheme: String,
        host: String,
        path: String,
        req: RequestHead,
        clientIn: InputStream,
        upIn: InputStream,
        upOut: OutputStream,
        clientOut: OutputStream,
        decrypted: Boolean,
    ): Boolean {
        val reqBody = BodyCapture()
        HttpRelay.forwardRequest(req, path, clientIn, upOut, reqBody)
        val upstream = BufferedInputStream(upIn)
        val resp = HttpWire.readResponseHead(upstream, req.method)
        val respBody = BodyCapture()
        val reusable = HttpRelay.relayResponse(resp, upstream, clientOut, req.version, respBody)

        log.log(
            MitmTxn(
                timeMs = started,
                method = req.method,
                scheme = scheme,
                host = host,
                path = path,
                status = resp.status,
                reqBytes = reqBody.total,
                respBytes = respBody.total,
                durationMs = System.currentTimeMillis() - started,
                decrypted = decrypted,
                note = "",
                reqHeaders = headersToString(req.headers),
                respHeaders = headersToString(resp.headers),
                reqPreview = reqBody.preview(),
                respPreview = respBody.preview(),
                reqTruncated = reqBody.truncated,
                respTruncated = respBody.truncated,
            ),
        )
        return reusable
    }

    private fun errorTxn(
        started: Long, method: String, scheme: String, host: String, path: String, note: String,
    ) = MitmTxn(
        timeMs = started, method = method, scheme = scheme, host = host, path = path,
        status = -1, reqBytes = 0, respBytes = 0, durationMs = System.currentTimeMillis() - started,
        decrypted = false, note = note, reqHeaders = "", respHeaders = "", reqPreview = "", respPreview = "",
    )

    // ---- Helpers ------------------------------------------------------------------

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
}
