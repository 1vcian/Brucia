package app.brucia.wifi

/** A captured PMKID target (hashcat 16800 format: pmkid*apmac*stamac*essid-hex). */
data class PmkidTarget(val pmkid: ByteArray, val apMac: ByteArray, val staMac: ByteArray, val ssid: String)

object Pmkid {
    private fun hex(s: String): ByteArray {
        val clean = s.trim().replace(":", "")
        return ByteArray(clean.length / 2) { ((clean[it * 2].digitToInt(16) shl 4) or clean[it * 2 + 1].digitToInt(16)).toByte() }
    }

    /** Parse a hashcat 16800 line: pmkid*ap_mac*sta_mac*essid(hex). Null if invalid. */
    fun parse(line: String): PmkidTarget? = try {
        val p = line.trim().split("*")
        if (p.size < 4) null
        else PmkidTarget(hex(p[0]), hex(p[1]), hex(p[2]), String(hex(p[3]), Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }
}
