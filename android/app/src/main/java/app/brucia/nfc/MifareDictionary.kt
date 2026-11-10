package app.brucia.nfc

import app.brucia.BrandLinks
import app.brucia.update.AssetIntegrity
import app.brucia.update.DownloadIO
import app.brucia.wordlist.DictionaryDownload
import app.brucia.wordlist.DictionaryTextOutputStream
import app.brucia.wordlist.TransferWatch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

data class MifareDictionarySource(val id: String, val title: String, val url: String, val description: String,
                                  /** Expected size/SHA-256 of the hosted asset; null = not pinned (F42 seam). */
                                  val integrity: AssetIntegrity? = null)

/** MIFARE Classic's 6-byte keys, not Wi-Fi passwords or keys for every NFC technology. */
object MifareDictionary {
    const val MAX_BYTES = 1024L * 1024
    const val MAX_KEYS = 50_000
    val sources = listOf(
        MifareDictionarySource("flipper", "Flipper Classic dictionary",
            "${BrandLinks.DOWNLOAD_PREFIX}wordlists/flipper-mf-classic.nfc",
            "Flipper firmware community dictionary, derived from Proxmark3. Overlaps the other list.",
            AssetIntegrity(38_847L, "e973b28d0e2d0049131bfa5a14a015125f53acdc79504996e3d1b4cb87ff1696")),
        MifareDictionarySource("proxmark3", "Proxmark3 Classic dictionary",
            "${BrandLinks.DOWNLOAD_PREFIX}wordlists/proxmark3-mfc-default-keys.dic",
            "RfidResearchGroup / Iceman public default-key dictionary. MIFARE Classic only.",
            AssetIntegrity(46_037L, "9e32e7ac46e7cd278a3a5e291d1adea5a4367da6b55352eecc8bea5e62679066")),
    )

    fun parse(text: String): List<String> = text.removePrefix("\uFEFF").lineSequence()
        .map { it.substringBefore('#').trim().uppercase(Locale.ROOT) }
        .filter { it.length == 12 && it.all { c -> c in "0123456789ABCDEF" } }
        .distinct().take(MAX_KEYS + 1).toList().also {
            require(it.size <= MAX_KEYS) { "Too many keys (maximum $MAX_KEYS)" }
        }

    private fun checked(bytes: ByteArray): List<String> = parse(bytes.toString(Charsets.UTF_8)).also {
        require(it.isNotEmpty()) { "No MIFARE Classic keys found: expected one 12-hex key per line" }
    }

    /** Total time for a local/provider file import; a stalled provider is closed after it. */
    const val IMPORT_TOTAL_MS = 60L * 1000

    /**
     * Reads a user-chosen file. [cancelled] and the [totalMillis] limit close [input], so a
     * blocked provider read returns; the bytes get the same text check as downloads.
     */
    fun read(input: InputStream, cancelled: () -> Boolean = { false }, totalMillis: Long = IMPORT_TOTAL_MS,
             clock: () -> Long = System::nanoTime): List<String> = input.use { stream ->
        TransferWatch.guard(cancelled, totalMillis, clock) { watch ->
            watch.onAbort { stream.close() }
            val out = ByteArrayOutputStream()
            val text = DictionaryTextOutputStream(out)
            DownloadIO.copy(stream, text, -1, MAX_BYTES, watch::stopped) { _, _ -> }
            text.check.finish()
            watch.ensureRunning()
            checked(out.toByteArray())
        }
    }

    /** Downloads [source]; pinned sources must match their recorded size and SHA-256. */
    fun download(source: MifareDictionarySource, cancelled: () -> Boolean = { false }): List<String> =
        checked(DictionaryDownload.read(source.url, MAX_BYTES, cancelled, integrity = source.integrity))
}
