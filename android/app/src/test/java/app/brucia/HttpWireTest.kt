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

    @Test fun headerValuesTrimOnlySpaceAndTabBeforeValidation() {
        for (edge in listOf("\u000b", "\u000c", "\u001c", "\u001f", "\u00a0")) {
            rejected("POST / HTTP/1.1\r\nContent-Length: ${edge}3\r\n\r\nabc")
            rejected("POST / HTTP/1.1\r\nContent-Length: 3$edge\r\n\r\nabc")
            rejected("POST / HTTP/1.1\r\nTransfer-Encoding: chunked$edge\r\n\r\n0\r\n\r\n")
        }
        val ows = HttpWire.readRequest(bytes("POST / HTTP/1.1\r\nContent-Length: \t3 \t\r\n\r\nabc"))!!
        assertArrayEquals("abc".toByteArray(), ows.body)
        assertEquals("3", ows.headers.single().second)
    }

    @Test fun responseStatusMustBeExactlyThreeDigits() {
        for (status in listOf("+200", "0200", "+100", "20", "2000")) {
            try {
                HttpWire.readResponse(bytes("HTTP/1.1 $status OK\r\nContent-Length: 0\r\n\r\n"), "GET")
                fail("Accepted status $status")
            } catch (_: IOException) { }
        }
    }

    @Test fun chunkSizeLinesRequireCrlfWhileFieldLinesTolerateBareLf() {
        rejected("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\nabc\r\n0\r\n\r\n")
        rejected("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3;x=y\nabc\r\n0\r\n\r\n")
        rejected("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\n\r\n")
        // RFC 9112 2.2: start-line and field lines (headers, trailers) may end in bare LF.
        val lenient = HttpWire.readRequest(bytes("POST / HTTP/1.1\nTransfer-Encoding: chunked\n\n1\r\na\r\n0\r\nT: v\n\n"))!!
        assertArrayEquals("a".toByteArray(), lenient.body)
    }

    @Test fun boundsTotalChunkExtensionBytesAndSizeDigits() {
        val ext = ";e=" + "x".repeat(1000)
        rejected("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n" + "1$ext\r\na\r\n".repeat(5) + "0\r\n\r\n")
        rejected("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n" + "0".repeat(100) + "1\r\na\r\n0\r\n\r\n")
        val ok = HttpWire.readRequest(bytes("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n" + "1$ext\r\na\r\n".repeat(4) + "0\r\n\r\n"))!!
        assertArrayEquals("aaaa".toByteArray(), ok.body)
    }

    @Test fun headerValuesRejectDelButKeepObsText() {
        rejected("GET / HTTP/1.1\r\nX-A: a\u007fb\r\n\r\n")
        val obs = HttpWire.readRequest(bytes("GET / HTTP/1.1\r\nX-A: caf\u00e9\u00ff\r\n\r\n"))!!
        assertEquals("caf\u00e9\u00ff", obs.headers.single().second)
    }

    @Test fun http10TransferEncodingIsAFramingError() {
        rejected("POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n")
        rejected("GET / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n")
        for (method in listOf("GET", "HEAD")) {
            try {
                HttpWire.readResponse(bytes("HTTP/1.0 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"), method)
                fail("Accepted HTTP/1.0 response with Transfer-Encoding")
            } catch (_: IOException) { }
        }
    }

    @Test fun onlyExactUppercaseConnectSkipsTheRequestBody() {
        for (method in listOf("connect", "Connect")) {
            val input = bytes("$method example.com:443 HTTP/1.1\r\nContent-Length: 3\r\n\r\nabcGET /next HTTP/1.1\r\n\r\n")
            val first = HttpWire.readRequest(input)!!
            assertEquals(method, first.method)
            assertArrayEquals("abc".toByteArray(), first.body)
            assertEquals("/next", HttpWire.readRequest(input)!!.target)
        }
        val tunnel = HttpWire.readRequest(bytes("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\n\r\n"))!!
        assertEquals(0, tunnel.body.size)
    }

    @Test fun totalReadBudgetExpiresEvenIfBytesKeepArriving() {
        var now = 0L
        val budget = HttpReadBudget(bytes("abc")) { now }
        assertEquals('a'.code, budget.read())
        now = 120_000_000_001L
        try { budget.read(); fail("Message deadline ignored") } catch (_: IOException) { }
    }
}
