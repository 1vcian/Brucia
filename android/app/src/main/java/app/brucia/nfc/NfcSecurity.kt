package app.brucia.nfc

import android.content.Context
import app.brucia.device.CliParse
import app.brucia.wardrive.Finding

/**
 * Phone-side NFC/RFID security assessment of a card read by the device. The ESP is
 * the antenna; the analysis (card-type weaknesses, default-key dictionary, clone
 * risk) runs on the phone. Authorized research only.
 */
object NfcSecurity {

    /** Well-known MIFARE Classic default keys (the phone can host the full dictionary). */
    val defaultKeys = listOf(
        "FFFFFFFFFFFF", "A0A1A2A3A4A5", "D3F7D3F7D3F7", "000000000000",
        "B0B1B2B3B4B5", "4D3A99C351DD", "1A982C7E459A", "AABBCCDDEEFF",
        "714C5C886E97", "587EE5F9350F", "A0478CC39091", "533CB6C723F6",
    )

    /** Parse a MIFARE key dictionary (one 12-hex key per line), filtering invalid lines. */
    fun parseKeys(text: String): List<String> =
        text.lineSequence()
            .map { it.trim().uppercase() }
            .filter { it.length == 12 && it.all { c -> c in "0123456789ABCDEF" } }
            .distinct()
            .toList()

    /** Load the bundled MIFARE key dictionary (assets/mifare_keys.txt), or the defaults. */
    fun loadKeyDictionary(context: Context): List<String> = try {
        parseKeys(context.assets.open("mifare_keys.txt").bufferedReader().use { it.readText() })
            .ifEmpty { defaultKeys }
    } catch (e: Exception) {
        defaultKeys
    }

    fun analyze(readText: String): List<Finding> {
        val out = ArrayList<Finding>()
        val type = (CliParse.field(readText, "Type") ?: "").lowercase()
        val uid = (CliParse.field(readText, "UID") ?: "").replace(" ", "").replace(":", "")
        val sak = (CliParse.field(readText, "SAK") ?: "").replace(" ", "").uppercase()

        if (type.contains("classic") || sak in setOf("08", "18", "88", "28", "09")) {
            out.add(
                Finding(
                    "high", "MIFARE Classic (Crypto-1)",
                    "Crypto-1 is broken: keys are recoverable (nested/darkside/hardnested) and " +
                        "${defaultKeys.size} well-known default keys frequently work. Treat as insecure.",
                )
            )
        }
        if (type.contains("ultralight") || type.contains("ntag")) {
            out.add(
                Finding(
                    "medium", "Ultralight / NTAG",
                    "Usually no read authentication: contents are readable and the tag is clonable. " +
                        "Use password/AUTH0 protection where supported.",
                )
            )
        }
        if (uid.length == 8) {
            out.add(Finding("info", "4-byte UID", "Short UID: clonable onto 'magic' UID-changeable cards."))
        }
        if (readText.contains("--- data ---")) {
            out.add(Finding("info", "Memory dump present", "Card memory was dumped; review it for stored secrets or keys."))
        }
        if (out.isEmpty()) {
            out.add(Finding("info", "No obvious weakness", "No known-weak card type detected from this read."))
        }
        val order = mapOf("high" to 0, "medium" to 1, "info" to 2)
        return out.sortedBy { order[it.severity] ?: 3 }
    }
}
