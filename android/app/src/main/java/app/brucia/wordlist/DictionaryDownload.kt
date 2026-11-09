package app.brucia.wordlist

import app.brucia.update.DownloadIO
import app.brucia.update.UpdateHttp
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Data downloads only. HTTPS, bounded copies, and promotion after validation. */
object DictionaryDownload {
    private fun open(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Brucia")
    }

    fun read(url: String, limit: Long, cancelled: () -> Boolean = { false },
             connect: (URL) -> HttpURLConnection = ::open): ByteArray {
        val conn = UpdateHttp.follow(URL(url), connect)
        try {
            DownloadIO.checkStatus(conn)
            return ByteArrayOutputStream().use { out ->
                conn.inputStream.use { DownloadIO.copy(it, out, conn.contentLengthLong, limit, cancelled) { _, _ -> } }
                out.toByteArray().also(::checkText)
            }
        } finally { conn.disconnect() }
    }

    fun store(destination: File, url: String, limit: Long, cancelled: () -> Boolean,
              progress: (Long, Long) -> Unit, connect: (URL) -> HttpURLConnection = ::open) {
        val part = File.createTempFile("dictionary-", ".part", destination.parentFile)
        try {
            val conn = UpdateHttp.follow(URL(url), connect)
            try {
                DownloadIO.checkStatus(conn)
                conn.inputStream.use { input -> part.outputStream().use { out ->
                    DownloadIO.copy(input, out, conn.contentLengthLong, limit, cancelled, progress)
                } }
            } finally { conn.disconnect() }
            checkText(part.inputStream().use { input ->
                val head = ByteArray(2048)
                head.copyOf(input.read(head).coerceAtLeast(0))
            })
            if (cancelled()) throw InterruptedException("Cancelled")
            DownloadIO.promote(part, destination)
        } finally { part.delete() }
    }

    private fun checkText(bytes: ByteArray) {
        val head = bytes.take(2048).toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val zip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            bytes[2] == 3.toByte() && bytes[3] == 4.toByte()
        val gzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        if (listOf("<!doctype", "<html", "<head", "<body").any { head.startsWith(it, ignoreCase = true) } || head.startsWith("version https://git-lfs") ||
            bytes.any { it == 0.toByte() } ||
            zip || gzip)
            throw IOException("Expected a plain-text dictionary, not a web page or archive")
    }
}
