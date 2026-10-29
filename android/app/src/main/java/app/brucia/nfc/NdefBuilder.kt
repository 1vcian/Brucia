package app.brucia.nfc

/** Builds NDEF records (URI/Text) on the phone, to write to a tag via the device. */
object NdefBuilder {
    private val uriPrefixes = listOf(
        "" to 0x00, "http://www." to 0x01, "https://www." to 0x02,
        "http://" to 0x03, "https://" to 0x04, "tel:" to 0x05, "mailto:" to 0x06,
    )

    /** NDEF URI record (well-known type 'U'). */
    fun uri(url: String): ByteArray {
        val match = uriPrefixes.filter { it.first.isNotEmpty() && url.startsWith(it.first) }
            .maxByOrNull { it.first.length }
        val code = match?.second ?: 0x00
        val rest = if (match != null) url.substring(match.first.length) else url
        val payload = byteArrayOf(code.toByte()) + rest.toByteArray(Charsets.UTF_8)
        return record(0x55, payload) // 'U'
    }

    /** NDEF Text record (well-known type 'T'). */
    fun text(txt: String, lang: String = "en"): ByteArray {
        val l = lang.toByteArray(Charsets.US_ASCII)
        val payload = byteArrayOf(l.size.toByte()) + l + txt.toByteArray(Charsets.UTF_8)
        return record(0x54, payload) // 'T'
    }

    /** Wrap an NDEF message in a Type-2 tag TLV (0x03 len ... 0xFE). */
    fun tlv(ndef: ByteArray): ByteArray = byteArrayOf(0x03, ndef.size.toByte()) + ndef + byteArrayOf(0xFE.toByte())

    fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    private fun record(type: Int, payload: ByteArray): ByteArray {
        // MB|ME|SR|TNF=1 (well-known) = 0xD1, type length 1, payload length (short), type
        return byteArrayOf(0xD1.toByte(), 0x01, payload.size.toByte(), type.toByte()) + payload
    }
}
