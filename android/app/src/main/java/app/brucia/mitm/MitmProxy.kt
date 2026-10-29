package app.brucia.mitm

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
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
class MitmProxy(private val ctx: Context, private val port: Int) {

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val upstreamFactory = SSLSocketFactory.getDefault() as SSLSocketFactory

    private companion object {
        const val PREVIEW_MAX = 4096
        const val CLIENT_TIMEOUT_MS = 60_000
        const val UPSTREAM_CONNECT_MS = 15_000
        const val UPSTREAM_READ_MS = 30_000
    }

    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        running = true
        thread(name = "mitm-accept") { acceptLoop(s) }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        pool.shutdownNow()
        MitmCa.clearLeafCache()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                if (running) continue else break
            }
            pool.submit { runCatching { handle(client) } }
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
            Socket().use { up ->
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
            s.useClientMode = false
            s.startHandshake()
            s
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
            up.connect(InetSocketAddress(host, portNum), UPSTREAM_CONNECT_MS)
            up.soTimeout = UPSTREAM_READ_MS
            // Validate the real server's certificate (standard HTTPS rules).
            up.sslParameters = up.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            up.use {
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

    private data class HttpRequest(
        val method: String,
        val target: String,
        val version: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    )

    private data class HttpResponse(
        val version: String,
        val status: Int,
        val reason: String,
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
    )

    private fun readRequest(ins: InputStream): HttpRequest? {
        var line = readLine(ins) ?: return null
        while (line.isEmpty()) line = readLine(ins) ?: return null
        val parts = line.split(" ", limit = 3)
        if (parts.size < 3) throw IOException("Malformed request line: $line")
        val method = parts[0]
        val target = parts[1]
        val version = parts[2]
        val headers = readHeaders(ins)
        val body = if (method.equals("CONNECT", true)) ByteArray(0)
        else readBody(ins, headers, isResponse = false, requestMethod = method, status = 0)
        return HttpRequest(method, target, version, headers, body)
    }

    private fun readResponse(ins: InputStream, requestMethod: String): HttpResponse {
        var line = readLine(ins) ?: throw IOException("No response")
        while (line.isEmpty()) line = readLine(ins) ?: throw IOException("No response")
        val parts = line.split(" ", limit = 3)
        if (parts.size < 2) throw IOException("Malformed status line: $line")
        val version = parts[0]
        val status = parts[1].toIntOrNull() ?: throw IOException("Bad status: ${parts[1]}")
        val reason = parts.getOrElse(2) { "" }
        val headers = readHeaders(ins)
        val body = readBody(ins, headers, isResponse = true, requestMethod = requestMethod, status = status)
        return HttpResponse(version, status, reason, headers, body)
    }

    private fun readHeaders(ins: InputStream): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        while (true) {
            val l = readLine(ins) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i <= 0) continue
            out.add(l.substring(0, i).trim() to l.substring(i + 1).trim())
        }
        return out
    }

    private fun readBody(
        ins: InputStream,
        headers: List<Pair<String, String>>,
        isResponse: Boolean,
        requestMethod: String,
        status: Int,
    ): ByteArray {
        if (isResponse) {
            if (requestMethod.equals("HEAD", true)) return ByteArray(0)
            if (status == 204 || status == 304 || status in 100..199) return ByteArray(0)
        }
        val te = header(headers, "Transfer-Encoding")
        if (te != null && te.contains("chunked", true)) return readChunked(ins)
        val cl = header(headers, "Content-Length")?.toIntOrNull()
        if (cl != null) return readN(ins, cl)
        if (!isResponse) return ByteArray(0) // request without length/chunked has no body
        return readAll(ins) // response body delimited by connection close
    }

    private fun readChunked(ins: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(ins) ?: break
            if (sizeLine.isEmpty()) continue
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // consume trailing headers until blank line
                while (true) {
                    val t = readLine(ins) ?: break
                    if (t.isEmpty()) break
                }
                break
            }
            out.write(readN(ins, size))
            readLine(ins) // trailing CRLF after chunk
        }
        return out.toByteArray()
    }

    private fun readN(ins: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = ins.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    private fun readAll(ins: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val r = try { ins.read(buf) } catch (e: Exception) { -1 }
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private fun readLine(ins: InputStream): String? {
        val out = ByteArrayOutputStream()
        var seen = false
        while (true) {
            val b = ins.read()
            if (b < 0) return if (seen) out.toString("ISO-8859-1") else null
            seen = true
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("ISO-8859-1")
    }

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
