package app.brucia.wordlist

import app.brucia.update.AssetIntegrity
import app.brucia.update.DownloadIO
import app.brucia.update.UpdateHttp
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Data downloads only. HTTPS, bounded copies, whole-file text validation, optional
 * size/SHA-256 pinning, and promotion only after everything passed.
 *
 * Cancel and a total time limit are enforced by [TransferWatch]: besides being checked
 * between reads, they disconnect the connection so a read blocked on a stalled or
 * trickling server returns instead of outliving the request.
 */
object DictionaryDownload {
    /** Total time for an in-memory download ([read], at most a few MiB). */
    const val READ_TOTAL_MS = 2L * 60 * 1000

    /** Total time for a stored download ([store]; rockyou is ~140 MB, about 19 min at 1 Mbit/s). */
    const val STORE_TOTAL_MS = 45L * 60 * 1000

    private fun open(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Brucia")
    }

    /** [integrity]: expected size/SHA-256, or null for unpinned sources. */
    fun read(url: String, limit: Long, cancelled: () -> Boolean = { false },
             connect: (URL) -> HttpURLConnection = ::open, integrity: AssetIntegrity? = null,
             totalMillis: Long = READ_TOTAL_MS, clock: () -> Long = System::nanoTime): ByteArray =
        TransferWatch.guard(cancelled, totalMillis, clock) { watch ->
            val out = ByteArrayOutputStream()
            fetch(url, connect, watch) { conn, input ->
                transfer(input, out, conn.contentLengthLong, limit, integrity, watch) { _, _ -> }
            }
            watch.ensureRunning()
            out.toByteArray()
        }

    /**
     * Streams [url] to a private `.part` next to [destination] and promotes it only when the
     * whole body passed the text check and, when [integrity] is non-null, its size and SHA-256.
     * Any failure, cancel or timeout leaves the previous [destination] untouched.
     */
    fun store(destination: File, url: String, limit: Long, cancelled: () -> Boolean,
              progress: (Long, Long) -> Unit, connect: (URL) -> HttpURLConnection = ::open,
              integrity: AssetIntegrity? = null, totalMillis: Long = STORE_TOTAL_MS,
              clock: () -> Long = System::nanoTime) {
        val part = File.createTempFile("dictionary-", ".part", destination.parentFile)
        try {
            TransferWatch.guard(cancelled, totalMillis, clock) { watch ->
                fetch(url, connect, watch) { conn, input ->
                    part.outputStream().use { out ->
                        transfer(input, out, conn.contentLengthLong, limit, integrity, watch, progress)
                    }
                }
                watch.ensureRunning()
                DownloadIO.promote(part, destination)
            }
        } finally { part.delete() }
    }

    /** Opens [url] (redirects checked by [UpdateHttp]) with every connection abortable by [watch]. */
    private fun fetch(url: String, connect: (URL) -> HttpURLConnection, watch: TransferWatch,
                      body: (HttpURLConnection, InputStream) -> Unit) {
        val conn = UpdateHttp.follow(URL(url)) { target ->
            connect(target).also { c -> watch.onAbort { c.disconnect() } }
        }
        try {
            DownloadIO.checkStatus(conn)
            conn.inputStream.use { body(conn, it) }
        } finally { conn.disconnect() }
    }

    /** Bounded copy that validates every byte as text and, if pinned, size/SHA-256. */
    private fun transfer(input: InputStream, sink: OutputStream, expected: Long, limit: Long,
                         integrity: AssetIntegrity?, watch: TransferWatch, progress: (Long, Long) -> Unit) {
        val text = DictionaryTextOutputStream(sink)
        if (integrity != null) {
            DownloadIO.copyVerified(input, text, expected, limit, integrity, watch::stopped, progress)
        } else {
            DownloadIO.copy(input, text, expected, limit, watch::stopped, progress)
        }
        text.check.finish()
    }
}
