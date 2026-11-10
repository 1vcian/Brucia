package app.brucia.nfc

import app.brucia.BrandLinks
import app.brucia.update.DownloadIO
import app.brucia.wordlist.DictionaryDownload
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

data class MifareDictionarySource(val id: String, val title: String, val url: String, val description: String)

/** MIFARE Classic's 6-byte keys, not Wi-Fi passwords or keys for every NFC technology. */
object MifareDictionary {
    const val MAX_BYTES = 1024L * 1024
    const val MAX_KEYS = 50_000
    val sources = listOf(
        MifareDictionarySource("flipper", "Flipper Classic dictionary",
            "${BrandLinks.DOWNLOAD_PREFIX}wordlists/flipper-mf-classic.nfc",
            "Flipper firmware community dictionary, derived from Proxmark3. Overlaps the other list."),
        MifareDictionarySource("proxmark3", "Proxmark3 Classic dictionary",
            "${BrandLinks.DOWNLOAD_PREFIX}wordlists/proxmark3-mfc-default-keys.dic",
            "RfidResearchGroup / Iceman public default-key dictionary. MIFARE Classic only."),
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

    fun read(input: InputStream): List<String> = input.use { stream ->
        val out = ByteArrayOutputStream()
        DownloadIO.copy(stream, out, -1, MAX_BYTES, { false }) { _, _ -> }
        checked(out.toByteArray())
    }

    fun download(source: MifareDictionarySource): List<String> =
        checked(DictionaryDownload.read(source.url, MAX_BYTES))
}
