package app.brucia

import app.brucia.mitm.HttpWire
import app.brucia.mitm.HttpReadBudget
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class HttpWireTest {
    private fun bytes(value: String) = ByteArrayInputStream(value.toByteArray(Charsets.ISO_8859_1))
    private fun rejected(value: String) {
        try { HttpWire.readRequest(bytes(value)); fail("Accepted malformed HTTP") }
        catch (_: IOException) { }
    }

    @Test fun rejectsSignedLengthsAndChunkSizes() {
        for (size in listOf("+0", "-0")) {
            rejected("POST / HTTP/1.1\r\nContent-Length: $size\r\n\r\n")
            rejected("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n$size\r\n\r\n")
        }
    }

    @Test fun readsExactAndChunkedRequestsAndPreservesNextMessage() {
        val input = bytes("POST / HTTP/1.1\r\nContent-Length: 3\r\n\r\nabcGET /next HTTP/1.1\r\n\r\n")
        assertArrayEquals("abc".toByteArray(), HttpWire.readRequest(input)!!.body)
        assertEquals("/next", HttpWire.readRequest(input)!!.target)
        val chunked = bytes("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n2;ext=yes\r\nab\r\n1\r\nc\r\n0\r\nTrailer: value\r\n\r\n")
        assertArrayEquals("abc".toByteArray(), HttpWire.readRequest(chunked)!!.body)
    }

    @Test fun rejectsTruncatedOrAmbiguousBodies() {
        for (headers in listOf("Content-Length: -1", "Content-Length: 2147483647",
            "Content-Length: 3\r\nContent-Length: 4", "Content-Length: 3\r\nTransfer-Encoding: chunked",
            "Content-Length: not-a-number", "Transfer-Encoding: gzip")) {
            rejected("POST / HTTP/1.1\r\n$headers\r\n\r\n")
        }
        rejected("POST / HTTP/1.1\r\nContent-Length: 3\r\n\r\nab")
        rejected("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nab")
        rejected("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabcXX0\r\n\r\n")
        rejected("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n-1\r\n")
    }

    @Test fun boundsLinesHeadersAndBodiesBeforeAllocation() {
        rejected("GET /${"x".repeat(8192)} HTTP/1.1\r\n\r\n")
        rejected("GET / HTTP/1.1\r\n" + (0..100).joinToString("") { "X-$it: a\r\n" } + "\r\n")
        rejected("POST / HTTP/1.1\r\nContent-Length: ${HttpWire.MAX_BODY + 1}\r\n\r\n")
        rejected("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n${(HttpWire.MAX_BODY + 1).toString(16)}\r\n")
    }

    @Test fun rejectsIncompleteHeadersAndUnsupportedExpectWithoutWaitingForBody() {
        rejected("GET / HTTP/1.1\r\nHost: example.com\r\n")
        rejected("POST / HTTP/1.1\r\nExpect: 100-continue\r\nContent-Length: 100\r\n\r\n")
        rejected("GET / HTTP/1.1\r\nBad header\r\n\r\n")
        rejected("\r\n".repeat(9))
    }

    @Test fun skipsInterimResponseAndHeadNeverConsumesNextResponse() {
        val input = bytes("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc")
        val response = HttpWire.readResponse(input, "GET")
        assertEquals(200, response.status)
        assertArrayEquals("abc".toByteArray(), response.body)
        val head = bytes("HTTP/1.1 200 OK\r\nContent-Length: 999\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n")
        assertEquals(0, HttpWire.readResponse(head, "HEAD").body.size)
        assertEquals(204, HttpWire.readResponse(head, "GET").status)
    }

    @Test fun eofDelimitedReadErrorsAreNotSuccessfulPartialResponses() {
        val broken = object : InputStream() {
            val header = bytes("HTTP/1.0 200 OK\r\n\r\nabc")
            override fun read(): Int = header.read().also { if (it < 0) throw IOException("broken") }
        }
        try { HttpWire.readResponse(broken, "GET"); fail("Partial response accepted") }
        catch (_: IOException) { }
    }

    @Test fun totalReadBudgetExpiresEvenIfBytesKeepArriving() {
        var now = 0L
        val budget = HttpReadBudget(bytes("abc")) { now }
        assertEquals('a'.code, budget.read())
        now = 120_000_000_001L
        try { budget.read(); fail("Message deadline ignored") } catch (_: IOException) { }
    }
}
