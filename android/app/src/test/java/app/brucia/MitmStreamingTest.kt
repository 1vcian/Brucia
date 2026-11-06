package app.brucia

import app.brucia.mitm.BodyFraming
import app.brucia.mitm.HttpWire
import app.brucia.mitm.MitmLog
import app.brucia.mitm.MitmProxy
import app.brucia.mitm.MitmTxn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.CRC32
import kotlin.concurrent.thread

/**
 * The MITM proxy's plain-HTTP path end to end over LOOPBACK ONLY (proxy, upstream and client all
 * on 127.0.0.1): bodies far above the old 4 MiB inspection cap stream through intact, in both
 * directions and with chunked framing; the log keeps a bounded prefix and says it was truncated;
 * HEAD/304 heads keep the server's Content-Length.
 */
class MitmStreamingTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private lateinit var proxy: MitmProxy
    private val upstreams = mutableListOf<ServerSocket>()

    @Before fun setUp() {
        proxy = MitmProxy(0, loopback) { error("no TLS in these tests") }
        proxy.start()
    }

    @After fun tearDown() {
        proxy.stop()
        upstreams.forEach { runCatching { it.close() } }
    }

    private val big = 20L * 1024 * 1024 // 20 MiB, generated on the fly, never held in memory

    /** Deterministic byte pattern: byte i = (i * 31 + 7) mod 251. */
    private fun pattern(i: Long) = ((i * 31 + 7) % 251).toInt()

    private fun writePattern(out: OutputStream, size: Long, chunked: Boolean = false) {
        val buf = ByteArray(64 * 1024)
        var pos = 0L
        while (pos < size) {
            val n = minOf(buf.size.toLong(), size - pos).toInt()
            for (k in 0 until n) buf[k] = pattern(pos + k).toByte()
            if (chunked) out.write((Integer.toHexString(n) + "\r\n").toByteArray())
            out.write(buf, 0, n)
            if (chunked) out.write("\r\n".toByteArray())
            pos += n
        }
        if (chunked) out.write("0\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun patternCrc(size: Long): Long {
        val crc = CRC32()
        val buf = ByteArray(64 * 1024)
        var pos = 0L
        while (pos < size) {
            val n = minOf(buf.size.toLong(), size - pos).toInt()
            for (k in 0 until n) buf[k] = pattern(pos + k).toByte()
            crc.update(buf, 0, n)
            pos += n
        }
        return crc.value
    }

    /** Reads a body with the (separately tested) HttpWire framing and returns (size, crc). */
    private fun drain(ins: InputStream, framing: BodyFraming): Pair<Long, Long> {
        val crc = CRC32()
        val n = HttpWire.streamBody(ins, framing, { b, o, l -> crc.update(b, o, l) })
        return n to crc.value
    }

    /** Loopback upstream that answers each accepted connection with [handler]. */
    private fun upstream(handler: (InputStream, OutputStream) -> Unit): Int {
        val server = ServerSocket(0, 50, loopback)
        upstreams += server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use { runCatching { handler(BufferedInputStream(it.getInputStream()), it.getOutputStream()) } }
                }
            }
        }
        return server.localPort
    }

    private fun client(): Socket = Socket(loopback, proxy.localPort).apply { soTimeout = 30_000 }

    private fun logged(path: String): MitmTxn {
        repeat(200) {
            MitmLog.txns.value.firstOrNull { it.path == path }?.let { return it }
            Thread.sleep(25)
        }
        error("no log entry for $path")
    }

    private fun header(headers: List<Pair<String, String>>, name: String) =
        headers.firstOrNull { it.first.equals(name, true) }?.second

    @Test(timeout = 60_000) fun largeContentLengthResponseStreamsThroughAndLogIsTruncated() {
        val port = upstream { ins, out ->
            HttpWire.readRequestHead(ins)
            out.write("HTTP/1.1 200 OK\r\nContent-Length: $big\r\nConnection: close\r\n\r\n".toByteArray())
            writePattern(out, big)
        }
        client().use { c ->
            c.getOutputStream().write("GET http://127.0.0.1:$port/big-cl HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
            val ins = BufferedInputStream(c.getInputStream())
            val head = HttpWire.readResponseHead(ins, "GET")
            assertEquals(200, head.status)
            assertEquals(big.toString(), header(head.headers, "Content-Length"))
            val (n, crc) = drain(ins, head.framing)
            assertEquals(big, n)
            assertEquals(patternCrc(big), crc)
        }
        val txn = logged("/big-cl")
        assertEquals(big, txn.respBytes)
        assertTrue(txn.respTruncated)
        assertTrue(txn.respPreview.contains("truncated"))
        assertFalse(txn.reqTruncated)
    }

    @Test(timeout = 60_000) fun largeChunkedResponseIsRechunkedForAnHttp11Client() {
        val port = upstream { ins, out ->
            HttpWire.readRequestHead(ins)
            out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
            writePattern(out, big, chunked = true)
        }
        client().use { c ->
            val out = c.getOutputStream()
            val ins = BufferedInputStream(c.getInputStream())
            out.write("GET http://127.0.0.1:$port/big-chunked HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val head = HttpWire.readResponseHead(ins, "GET")
            assertEquals("chunked", header(head.headers, "Transfer-Encoding"))
            assertNull(header(head.headers, "Content-Length"))
            val (n, crc) = drain(ins, head.framing)
            assertEquals(big, n)
            assertEquals(patternCrc(big), crc)
        }
        assertEquals(big, logged("/big-chunked").respBytes)
    }

    @Test(timeout = 60_000) fun largeRequestBodiesReachUpstreamWithLengthAndChunked() {
        val received = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()
        val port = upstream { ins, out ->
            val req = HttpWire.readRequestHead(ins)!!
            received[req.target] = drain(ins, req.framing)
            out.write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }
        client().use { c ->
            val out = c.getOutputStream()
            out.write("POST http://127.0.0.1:$port/up-cl HTTP/1.1\r\nHost: x\r\nContent-Length: $big\r\nConnection: close\r\n\r\n".toByteArray())
            writePattern(out, big)
            assertEquals(204, HttpWire.readResponseHead(BufferedInputStream(c.getInputStream()), "POST").status)
        }
        client().use { c ->
            val out = c.getOutputStream()
            out.write("POST http://127.0.0.1:$port/up-chunked HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n".toByteArray())
            writePattern(out, big, chunked = true)
            assertEquals(204, HttpWire.readResponseHead(BufferedInputStream(c.getInputStream()), "POST").status)
        }
        assertEquals(big to patternCrc(big), received["/up-cl"])
        assertEquals(big to patternCrc(big), received["/up-chunked"])
        val txn = logged("/up-chunked")
        assertEquals(big, txn.reqBytes)
        assertTrue(txn.reqTruncated)
    }

    @Test(timeout = 30_000) fun headAnd304KeepTheServersContentLengthAndTheConnectionStaysUsable() {
        val port = upstream { ins, out ->
            while (true) {
                val req = HttpWire.readRequestHead(ins) ?: break
                val reply = if (req.method == "HEAD") "HTTP/1.1 200 OK\r\nContent-Length: 12345\r\n\r\n"
                else "HTTP/1.1 304 Not Modified\r\nETag: \"v1\"\r\nContent-Length: 777\r\n\r\n"
                out.write(reply.toByteArray()); out.flush()
            }
        }
        client().use { c ->
            val out = c.getOutputStream()
            val ins = BufferedInputStream(c.getInputStream())
            out.write("HEAD http://127.0.0.1:$port/head HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val head = HttpWire.readResponseHead(ins, "HEAD")
            assertEquals("12345", header(head.headers, "Content-Length"))
            // Same connection: the next response is read cleanly (no phantom body was relayed).
            out.write("GET http://127.0.0.1:$port/cond HTTP/1.1\r\nHost: x\r\nIf-None-Match: \"v1\"\r\n\r\n".toByteArray())
            val notModified = HttpWire.readResponseHead(ins, "GET")
            assertEquals(304, notModified.status)
            assertEquals("777", header(notModified.headers, "Content-Length"))
        }
    }

    @Test(timeout = 30_000) fun closeDelimitedResponseToHttp10ClientEndsWithConnectionClose() {
        val body = "x".repeat(5000)
        val port = upstream { ins, out ->
            HttpWire.readRequestHead(ins)
            out.write("HTTP/1.0 200 OK\r\n\r\n$body".toByteArray()); out.flush()
        }
        client().use { c ->
            c.getOutputStream().write("GET http://127.0.0.1:$port/old HTTP/1.0\r\n\r\n".toByteArray())
            val ins = BufferedInputStream(c.getInputStream())
            val head = HttpWire.readResponseHead(ins, "GET")
            assertEquals("close", header(head.headers, "Connection"))
            assertEquals(BodyFraming.UntilClose, head.framing)
            assertEquals(body.length.toLong(), drain(ins, head.framing).first)
        }
        val txn = logged("/old")
        assertEquals(5000L, txn.respBytes)
        assertTrue(txn.respTruncated) // 5000 > 4096-byte log prefix
    }
}
