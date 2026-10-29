package app.brucia.wifi

/** Export captured material to the hashcat 22000 format (WPA-PBKDF2-EAPOL / PMKID). */
object Hc22000 {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun essidHex(ssid: String) = hex(ssid.toByteArray(Charsets.UTF_8))

    /** WPA*02 line (handshake). Reinserts the captured MIC into the EAPOL frame. */
    fun export(h: Handshake): String {
        val eapol = h.eapol.copyOf()
        val mic = h.mic.copyOf(16)
        if (eapol.size >= 97) for (i in 0 until 16) eapol[81 + i] = mic[i]
        return "WPA*02*${hex(mic)}*${hex(h.apMac)}*${hex(h.clMac)}*${essidHex(h.ssid)}*" +
            "${hex(h.aNonce)}*${hex(eapol)}*00"
    }

    /** WPA*01 line (PMKID). */
    fun export(p: PmkidTarget): String =
        "WPA*01*${hex(p.pmkid)}*${hex(p.apMac)}*${hex(p.staMac)}*${essidHex(p.ssid)}***"

    private fun unhex(s: String): ByteArray {
        val c = s.trim()
        return ByteArray(c.length / 2) { ((c[it * 2].digitToInt(16) shl 4) or c[it * 2 + 1].digitToInt(16)).toByte() }
    }

    /** Parse a hashcat 22000 line into a Handshake (WPA*02) or PmkidTarget (WPA*01). */
    fun parse(line: String): Pair<Handshake?, PmkidTarget?>? = try {
        val p = line.trim().split("*")
        if (p.size < 6 || p[0] != "WPA") {
            null
        } else {
            val apMac = unhex(p[3]); val staMac = unhex(p[4]); val ssid = String(unhex(p[5]), Charsets.UTF_8)
            when (p[1]) {
                "01" -> Pair(null, PmkidTarget(unhex(p[2]), apMac, staMac, ssid))
                "02" -> {
                    if (p.size < 8) {
                        null
                    } else {
                        val mic = unhex(p[2]); val aNonce = unhex(p[6]); val eapol = unhex(p[7])
                        if (eapol.size < 97) {
                            null
                        } else {
                            val keyVer = (((eapol[5].toInt() and 0xFF) shl 8) or (eapol[6].toInt() and 0xFF)) and 0x0007
                            val sNonce = eapol.copyOfRange(17, 49)
                            val eapolZero = eapol.copyOf()
                            for (i in 81 until 97) eapolZero[i] = 0
                            Pair(Handshake(ssid, apMac, staMac, aNonce, sNonce, eapolZero, mic, if (keyVer == 1) 1 else 2), null)
                        }
                    }
                }
                else -> null
            }
        }
    } catch (e: Exception) {
        null
    }
}
