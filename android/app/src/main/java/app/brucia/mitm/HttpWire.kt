package app.brucia.mitm

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

internal data class HttpRequest(val method: String, val target: String, val version: String,
    val headers: List<Pair<String, String>>, val body: ByteArray)
internal data class HttpResponse(val version: String, val status: Int, val reason: String,
    val headers: List<Pair<String, String>>, val body: ByteArray)

/** Bounded inspection parser. Oversized/ambiguous/incomplete messages fail explicitly. */
internal object HttpWire {
    const val MAX_BODY = 4 * 1024 * 1024
    private const val MAX_LINE = 8192
    private const val MAX_HEADERS = 100
    private const val MAX_HEADER_BYTES = 64 * 1024

    fun readRequest(input: InputStream): HttpRequest? {
        val ins = HttpReadBudget(input)
        val line = startLine(ins) ?: return null
        val parts = line.split(" ", limit = 3)
        if (parts.size != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2] !in listOf("HTTP/1.0", "HTTP/1.1"))
            throw IOException("Malformed request line")
        val headers = readHeaders(ins)
        if (headers.any { it.first.equals("Expect", true) }) throw IOException("Expect requests are not supported")
        val body = if (parts[0].equals("CONNECT", true)) ByteArray(0)
            else readBody(ins, headers, false, parts[0], 0)
        return HttpRequest(parts[0], parts[1], parts[2], headers, body)
    }

    fun readResponse(input: InputStream, method: String): HttpResponse {
        val ins = HttpReadBudget(input)
        repeat(8) {
            val parts = (startLine(ins) ?: throw EOFException("No response")).split(" ", limit = 3)
            val code = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("Malformed response status")
            if (parts[0] !in listOf("HTTP/1.0", "HTTP/1.1") || code !in 100..599) throw IOException("Invalid HTTP response")
            val headers = readHeaders(ins)
            if (code == 101) throw IOException("Protocol switching is not supported")
            if (code >= 200) return HttpResponse(parts[0], code, parts.getOrElse(2) { "" }, headers,
                readBody(ins, headers, true, method, code))
        }
        throw IOException("Too many interim responses")
    }

    private fun startLine(ins: InputStream): String? {
        repeat(8) { val line = line(ins) ?: return null; if (line.isNotEmpty()) return line }
        throw IOException("Too many empty HTTP lines")
    }

    private fun readHeaders(ins: InputStream): List<Pair<String, String>> {
        val headers = mutableListOf<Pair<String, String>>()
        var bytes = 0
        while (true) {
            val line = line(ins) ?: throw EOFException("Incomplete HTTP headers")
            if (line.isEmpty()) return headers
            bytes += line.length + 2
            if (headers.size >= MAX_HEADERS || bytes > MAX_HEADER_BYTES) throw IOException("HTTP headers exceed limit")
            val colon = line.indexOf(':')
            if (colon < 1 || line[0].isWhitespace()) throw IOException("Malformed HTTP header")
            val name = line.substring(0, colon)
            if (!name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#\$%&'*+-.^_`|~" })
                throw IOException("Invalid HTTP header name")
            val value = line.substring(colon + 1).trim()
            if (value.any { it.code < 32 && it != '\t' }) throw IOException("Invalid HTTP header value")
            headers.add(name to value)
        }
    }

    private fun readBody(ins: InputStream, headers: List<Pair<String, String>>, response: Boolean, method: String, code: Int): ByteArray {
        if (response && (method.equals("HEAD", true) || code == 204 || code == 304)) return ByteArray(0)
        val lengths = headers.filter { it.first.equals("Content-Length", true) }
        val encodings = headers.filter { it.first.equals("Transfer-Encoding", true) }
        if (lengths.size > 1 || encodings.size > 1 || (lengths.isNotEmpty() && encodings.isNotEmpty()))
            throw IOException("Ambiguous HTTP message length")
        if (encodings.isNotEmpty()) {
            if (!encodings[0].second.equals("chunked", true)) throw IOException("Unsupported transfer encoding")
            return chunked(ins)
        }
        if (lengths.isNotEmpty()) {
            if (!Regex("[0-9]+").matches(lengths[0].second)) throw IOException("Invalid Content-Length")
            val size = lengths[0].second.toLongOrNull() ?: throw IOException("Invalid Content-Length")
            if (size !in 0..MAX_BODY.toLong()) throw IOException("HTTP body exceeds inspection limit")
            return exactly(ins, size.toInt())
        }
        if (!response) return ByteArray(0)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = ins.read(buffer)
            if (count < 0) return out.toByteArray()
            if (count == 0) throw IOException("HTTP stream made no progress")
            if (count > MAX_BODY - out.size()) throw IOException("HTTP body exceeds inspection limit")
            out.write(buffer, 0, count)
        }
    }

    private fun chunked(ins: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val text = line(ins) ?: throw EOFException("Missing chunk size")
            val digits = text.substringBefore(';')
            if (!Regex("[0-9a-fA-F]+").matches(digits)) throw IOException("Invalid chunk size")
            val size = digits.toLongOrNull(16) ?: throw IOException("Invalid chunk size")
            if (size < 0 || size > MAX_BODY - out.size()) throw IOException("Chunked body exceeds inspection limit")
            if (size == 0L) { readHeaders(ins); return out.toByteArray() }
            out.write(exactly(ins, size.toInt()))
            if (ins.read() != 13 || ins.read() != 10) throw IOException("Missing chunk terminator")
        }
    }

    private fun exactly(ins: InputStream, size: Int): ByteArray {
        val data = ByteArray(size)
        var count = 0
        while (count < size) {
            val read = ins.read(data, count, size - count)
            if (read <= 0) throw EOFException("Incomplete HTTP body")
            count += read
        }
        return data
    }

    private fun line(ins: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val byte = ins.read()
            if (byte < 0) { if (bytes.size() == 0) return null; throw EOFException("Incomplete HTTP line") }
            if (byte == 10) {
                val text = bytes.toString("ISO-8859-1").removeSuffix("\r")
                if ('\r' in text || '\u0000' in text) throw IOException("Invalid HTTP line")
                return text
            }
            if (bytes.size() >= MAX_LINE) throw IOException("HTTP line exceeds limit")
            bytes.write(byte)
        }
    }
}

/** A socket read can add at most its socket timeout to this total message budget. */
internal class HttpReadBudget(private val input: InputStream, private val clock: () -> Long = System::nanoTime) : InputStream() {
    private val started = clock()
    private fun checkTime() { if (clock() - started > 120_000_000_000L) throw IOException("HTTP message time limit exceeded") }
    override fun read(): Int { checkTime(); return input.read().also { checkTime() } }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkTime(); return input.read(buffer, offset, length).also { checkTime() }
    }
}
