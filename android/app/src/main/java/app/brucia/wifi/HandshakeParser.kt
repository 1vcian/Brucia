package app.brucia.wifi

/**
 * Parses a 4-way WPA handshake out of a .pcap captured by the device (Bruce writes
 * the BrucePCAP folder). All parsing runs on the phone. Supports raw 802.11
 * (linktype 105) and radiotap (127). Returns null if no usable M1+M2 are found.
 */
object HandshakeParser {

    private fun u16le(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32le(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF).toLong() or ((b[o + 1].toInt() and 0xFF).toLong() shl 8) or
            ((b[o + 2].toInt() and 0xFF).toLong() shl 16) or ((b[o + 3].toInt() and 0xFF).toLong() shl 24)
    private fun u16be(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun parse(pcap: ByteArray, ssidOverride: String? = null): Handshake? {
        if (pcap.size < 24) return null
        val magic = u32le(pcap, 0)
        // 0xa1b2c3d4 (us) or 0xa1b23c4d (ns); only used to confirm pcap + LE
        if (magic != 0xa1b2c3d4L && magic != 0xa1b23c4dL) return null
        val linktype = u32le(pcap, 20).toInt()

        var ssid = ssidOverride
        var apMac: ByteArray? = null
        var clMac: ByteArray? = null
        var aNonce: ByteArray? = null
        var sNonce: ByteArray? = null
        var m2Eapol: ByteArray? = null
        var m2Mic: ByteArray? = null
        var keyVer = 2

        var off = 24
        while (off + 16 <= pcap.size) {
            val caplen = u32le(pcap, off + 8).toInt()
            val start = off + 16
            off = start + caplen
            // Long sum: a corrupt caplen near Int.MAX_VALUE must not wrap past the bounds check.
            if (caplen <= 0 || start.toLong() + caplen > pcap.size) break
            var p = start
            if (linktype == 127) { // radiotap: skip it_len (LE at +2)
                if (p + 4 > pcap.size) continue
                p += u16le(pcap, p + 2)
            }
            if (p + 24 > pcap.size) continue
            val fc0 = pcap[p].toInt() and 0xFF
            val type = (fc0 shr 2) and 0x3
            val subtype = (fc0 shr 4) and 0xF
            val fc1 = pcap[p + 1].toInt() and 0xFF
            val toDS = fc1 and 0x01
            val fromDS = (fc1 shr 1) and 0x01
            val a1 = pcap.copyOfRange(p + 4, p + 10)
            val a2 = pcap.copyOfRange(p + 10, p + 16)
            val a3 = pcap.copyOfRange(p + 16, p + 22)

            if (type == 0 && (subtype == 8 || subtype == 5)) { // beacon / probe resp
                if (ssid == null) {
                    // fixed params: 24 hdr + 12 (timestamp8+interval2+caps2) then tagged
                    var t = p + 24 + 12
                    while (t + 2 <= start + caplen) {
                        val tag = pcap[t].toInt() and 0xFF
                        val len = pcap[t + 1].toInt() and 0xFF
                        if (tag == 0 && t + 2 + len <= pcap.size) {
                            ssid = if (len == 0) "" else String(pcap.copyOfRange(t + 2, t + 2 + len), Charsets.UTF_8)
                            break
                        }
                        t += 2 + len
                    }
                }
                continue
            }
            if (type != 2) continue // not a data frame
            var hdr = 24
            if ((subtype and 0x8) != 0) hdr += 2 // QoS data
            var d = p + hdr
            // LLC/SNAP + ethertype 0x888E
            if (d + 8 > start + caplen) continue
            val isEapol = (pcap[d].toInt() and 0xFF) == 0xAA && (pcap[d + 1].toInt() and 0xFF) == 0xAA &&
                (pcap[d + 6].toInt() and 0xFF) == 0x88 && (pcap[d + 7].toInt() and 0xFF) == 0x8E
            if (!isEapol) continue
            val e = d + 8 // start of EAPOL frame
            if (e + 95 > start + caplen) continue
            val bodyLen = u16be(pcap, e + 2)
            val eapolLen = 4 + bodyLen
            if (e + eapolLen > start + caplen) continue
            val keyInfo = u16be(pcap, e + 5)
            val hasMic = (keyInfo and 0x0100) != 0
            val nonce = pcap.copyOfRange(e + 17, e + 49)
            keyVer = keyInfo and 0x0007
            if (keyVer != 1 && keyVer != 2) keyVer = 2
            if (!hasMic) { // M1 (from AP)
                aNonce = nonce
                apMac = if (fromDS == 1) a2 else a3
                clMac = if (fromDS == 1) a1 else a2
            } else if ((keyInfo and 0x0080) == 0 && nonce.any { it.toInt() != 0 }) { // M2 (from STA)
                // M3 (ACK set, carries the ANonce) and M4 (zero nonce) also have a MIC; taking them
                // as the client half made a complete 4-way capture uncrackable.
                sNonce = nonce
                m2Mic = pcap.copyOfRange(e + 81, e + 97)
                val frame = pcap.copyOfRange(e, e + eapolLen)
                for (i in 81 until 97) frame[i] = 0 // zero the MIC field
                m2Eapol = frame
                apMac = if (toDS == 1) a1 else a3
                clMac = if (toDS == 1) a2 else a1
            }
        }

        val s = ssid
        if (s == null || apMac == null || clMac == null || aNonce == null ||
            sNonce == null || m2Eapol == null || m2Mic == null
        ) return null
        return Handshake(s, apMac, clMac, aNonce, sNonce, m2Eapol, m2Mic, keyVer)
    }

    /**
     * Extract a clientless PMKID from a .pcap. The PMKID rides in the RSN PMKID KDE
     * (vendor id 0xDD, OUI 00-0F-AC, data type 0x04) inside the key-data of EAPOL
     * message 1 (from the AP, no MIC set). [ssidOverride] supplies the ESSID when the
     * capture carries no beacon. All parsing runs on the phone. Null if none found.
     */
    fun parsePmkid(pcap: ByteArray, ssidOverride: String? = null): PmkidTarget? {
        if (pcap.size < 24) return null
        val magic = u32le(pcap, 0)
        if (magic != 0xa1b2c3d4L && magic != 0xa1b23c4dL) return null
        val linktype = u32le(pcap, 20).toInt()

        var ssid = ssidOverride
        var apMac: ByteArray? = null
        var staMac: ByteArray? = null
        var pmkid: ByteArray? = null

        var off = 24
        while (off + 16 <= pcap.size) {
            val caplen = u32le(pcap, off + 8).toInt()
            val start = off + 16
            off = start + caplen
            // Long sum: a corrupt caplen near Int.MAX_VALUE must not wrap past the bounds check.
            if (caplen <= 0 || start.toLong() + caplen > pcap.size) break
            var p = start
            if (linktype == 127) {
                if (p + 4 > pcap.size) continue
                p += u16le(pcap, p + 2)
            }
            if (p + 24 > pcap.size) continue
            val fc0 = pcap[p].toInt() and 0xFF
            val type = (fc0 shr 2) and 0x3
            val subtype = (fc0 shr 4) and 0xF
            val fc1 = pcap[p + 1].toInt() and 0xFF
            val fromDS = (fc1 shr 1) and 0x01
            val a1 = pcap.copyOfRange(p + 4, p + 10)
            val a2 = pcap.copyOfRange(p + 10, p + 16)
            val a3 = pcap.copyOfRange(p + 16, p + 22)

            if (type == 0 && (subtype == 8 || subtype == 5)) { // beacon / probe resp
                if (ssid == null) {
                    var t = p + 24 + 12
                    while (t + 2 <= start + caplen) {
                        val tag = pcap[t].toInt() and 0xFF
                        val len = pcap[t + 1].toInt() and 0xFF
                        if (tag == 0 && t + 2 + len <= pcap.size) {
                            ssid = if (len == 0) "" else String(pcap.copyOfRange(t + 2, t + 2 + len), Charsets.UTF_8)
                            break
                        }
                        t += 2 + len
                    }
                }
                continue
            }
            if (type != 2) continue
            var hdr = 24
            if ((subtype and 0x8) != 0) hdr += 2
            val dd = p + hdr
            if (dd + 8 > start + caplen) continue
            val isEapol = (pcap[dd].toInt() and 0xFF) == 0xAA && (pcap[dd + 1].toInt() and 0xFF) == 0xAA &&
                (pcap[dd + 6].toInt() and 0xFF) == 0x88 && (pcap[dd + 7].toInt() and 0xFF) == 0x8E
            if (!isEapol) continue
            val e = dd + 8
            if (e + 99 > start + caplen) continue
            val keyInfo = u16be(pcap, e + 5)
            if ((keyInfo and 0x0100) != 0) continue // M1 only (no MIC)
            val kdLen = u16be(pcap, e + 97)
            val kdStart = e + 99
            val kdEnd = (kdStart + kdLen).coerceAtMost(start + caplen)
            var k = kdStart
            while (k + 2 <= kdEnd) {
                val id = pcap[k].toInt() and 0xFF
                val len = pcap[k + 1].toInt() and 0xFF
                if (k + 2 + len > kdEnd) break
                if (id == 0xDD && len >= 20 &&
                    (pcap[k + 2].toInt() and 0xFF) == 0x00 && (pcap[k + 3].toInt() and 0xFF) == 0x0F &&
                    (pcap[k + 4].toInt() and 0xFF) == 0xAC && (pcap[k + 5].toInt() and 0xFF) == 0x04
                ) {
                    val cand = pcap.copyOfRange(k + 6, k + 22)
                    if (cand.any { it.toInt() != 0 }) {
                        pmkid = cand
                        apMac = if (fromDS == 1) a2 else a3
                        staMac = if (fromDS == 1) a1 else a2
                    }
                }
                k += 2 + len
            }
            if (pmkid != null) break
        }

        val s = ssid
        if (pmkid == null || apMac == null || staMac == null || s == null) return null
        return PmkidTarget(pmkid, apMac, staMac, s)
    }
}
