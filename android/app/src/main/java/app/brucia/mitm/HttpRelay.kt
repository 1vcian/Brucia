package app.brucia.mitm

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Keeps the first [limit] bytes of a body that streams through, for the log, and counts the rest.
 * [truncated] is true when the body was larger than what was kept.
 */
internal class BodyCapture(private val limit: Int = PREVIEW_MAX) : BodySink {
    private val kept = ByteArrayOutputStream()
    var total = 0L
        private set
    val truncated: Boolean get() = total > kept.size()

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        total += length
        val room = limit - kept.size()
        if (room > 0) kept.write(buffer, offset, minOf(room, length))
    }

    fun bytes(): ByteArray = kept.toByteArray()

    /** Text preview of the kept prefix; marks a binary body and a truncated one. */
    fun preview(): String {
        if (total == 0L) return ""
        val text = String(kept.toByteArray(), Charsets.UTF_8)
        val nonPrintable = text.count { it.code < 9 || (it.code in 14..31) }
        val head = if (nonPrintable > text.length / 10) "[binary body, $total bytes]" else text
        return if (truncated) "$head\n... [truncated: first ${kept.size()} of $total bytes logged]" else head
    }

    companion object { const val PREVIEW_MAX = 4096 }
}

/**
 * Streaming HTTP/1.x relay used by [MitmProxy]: heads are parsed and rewritten, bodies pass
 * through as they arrive (any size), and only a bounded prefix is captured for the log. No
 * Android dependency, so it is tested over loopback sockets on the JVM.
 */
internal object HttpRelay {

    /** Send [head] (origin-form [path]) and its body, read from [clientIn], to [upOut]. */
    fun forwardRequest(head: RequestHead, path: String, clientIn: InputStream, upOut: OutputStream, capture: BodyCapture) {
        if (head.framing == BodyFraming.UntilClose) throw IOException("Request without a delimited body")
        val chunked = head.framing == BodyFraming.Chunked
        val sb = StringBuilder()
        sb.append(head.method).append(' ').append(path).append(' ').append(head.version).append("\r\n")
        for ((k, v) in head.headers) {
            if (k.equals("Proxy-Connection", true) || k.equals("Accept-Encoding", true)) continue // plaintext upstream
            if (isFraming(k)) continue
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        when (val f = head.framing) {
            is BodyFraming.Length -> sb.append("Content-Length: ").append(f.size).append("\r\n")
            BodyFraming.Chunked -> sb.append("Transfer-Encoding: chunked\r\n")
            else -> if (hasBodyMethod(head.method)) sb.append("Content-Length: 0\r\n")
        }
        sb.append("\r\n")
        upOut.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        relayBody(clientIn, head.framing, upOut, chunked, capture)
        upOut.flush()
    }

    /**
     * Send the response [resp] and its body, read from [upIn], to [clientOut] (a client speaking
     * [clientVersion]). HEAD/204/304 heads go out exactly as the server sent them (its
     * Content-Length describes the entity, there is no body). Returns false when the response
     * had to be delimited by closing the client connection.
     */
    fun relayResponse(resp: ResponseHead, upIn: InputStream, clientOut: OutputStream, clientVersion: String, capture: BodyCapture): Boolean {
        val sb = StringBuilder()
        val framing = resp.framing
        if (framing == BodyFraming.None) {
            statusLine(sb, resp.version, resp)
            for ((k, v) in resp.headers) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("\r\n")
            clientOut.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            clientOut.flush()
            return true
        }
        // A body of unknown or chunked length: re-chunk for an HTTP/1.1 client, else close-delimit.
        val chunked = framing !is BodyFraming.Length && clientVersion == "HTTP/1.1"
        val closeDelimited = framing !is BodyFraming.Length && !chunked
        statusLine(sb, if (chunked) "HTTP/1.1" else resp.version, resp)
        for ((k, v) in resp.headers) {
            if (isFraming(k)) continue
            if (closeDelimited && k.equals("Connection", true)) continue
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        when {
            framing is BodyFraming.Length -> sb.append("Content-Length: ").append(framing.size).append("\r\n")
            chunked -> sb.append("Transfer-Encoding: chunked\r\n")
            else -> sb.append("Connection: close\r\n")
        }
        sb.append("\r\n")
        clientOut.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        relayBody(upIn, framing, clientOut, chunked, capture)
        clientOut.flush()
        return !closeDelimited
    }

    private fun statusLine(sb: StringBuilder, version: String, resp: ResponseHead) {
        sb.append(version).append(' ').append(resp.status)
        if (resp.reason.isNotEmpty()) sb.append(' ').append(resp.reason)
        sb.append("\r\n")
    }

    /** Copy the body delimited by [framing] from [ins] to [out] (as chunks when [chunked]), teeing it into [capture]. */
    private fun relayBody(ins: InputStream, framing: BodyFraming, out: OutputStream, chunked: Boolean, capture: BodyCapture) {
        if (framing == BodyFraming.None) return
        HttpWire.streamBody(ins, framing, { b, o, l ->
            if (l > 0) {
                if (chunked) out.write((Integer.toHexString(l) + "\r\n").toByteArray(Charsets.ISO_8859_1))
                out.write(b, o, l)
                if (chunked) out.write(CRLF)
                capture.write(b, o, l)
            }
        })
        if (chunked) out.write(LAST_CHUNK)
    }

    private fun isFraming(name: String) =
        name.equals("Content-Length", true) || name.equals("Transfer-Encoding", true)

    private fun hasBodyMethod(method: String) =
        method.equals("POST", true) || method.equals("PUT", true) || method.equals("PATCH", true)

    private val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
    private val LAST_CHUNK = "0\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
}
