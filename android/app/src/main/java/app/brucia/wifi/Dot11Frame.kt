package app.brucia.wifi

/**
 * Lightweight decode of a raw IEEE 802.11 frame header - enough to drive a Wireshark-style
 * packet list (type/subtype label and the three MAC addresses). It deliberately does not
 * parse frame bodies; full dissection/decryption is left to Wireshark on the exported pcap.
 *
 * addr1/addr2/addr3 are the raw header addresses. For the common
 * (toDS=0, fromDS=0) case they are receiver/destination, transmitter/source and BSSID; the
 * UI labels them dst / src / bssid, which holds for most captured frames.
 */
data class Dot11Info(
    val type: Int,          // 0 mgmt, 1 ctrl, 2 data, 3 ext
    val subtype: Int,
    val label: String,      // e.g. "mgmt/beacon", "data/qos-data", "ctrl/ack"
    val addr1: String,      // receiver / destination
    val addr2: String,      // transmitter / source
    val addr3: String,      // bssid (typical)
    val toDs: Boolean,
    val fromDs: Boolean,
    val isEapol: Boolean,   // data frame carrying an EAPOL (handshake) payload
)

object Dot11Frame {

    private val MGMT = mapOf(
        0 to "assoc-req", 1 to "assoc-resp", 2 to "reassoc-req", 3 to "reassoc-resp",
        4 to "probe-req", 5 to "probe-resp", 6 to "timing-adv", 8 to "beacon", 9 to "atim",
        10 to "disassoc", 11 to "auth", 12 to "deauth", 13 to "action", 14 to "action-no-ack",
    )
    private val CTRL = mapOf(
        4 to "beamforming", 5 to "vht-ndp-ann", 7 to "ctrl-wrapper", 8 to "block-ack-req",
        9 to "block-ack", 10 to "ps-poll", 11 to "rts", 12 to "cts", 13 to "ack",
        14 to "cf-end", 15 to "cf-end-ack",
    )
    private val DATA = mapOf(
        0 to "data", 1 to "data-cf-ack", 2 to "data-cf-poll", 4 to "null",
        8 to "qos-data", 9 to "qos-data-cf-ack", 10 to "qos-data-cf-poll", 12 to "qos-null",
    )

    fun parse(f: ByteArray): Dot11Info {
        if (f.size < 2) {
            return Dot11Info(-1, -1, "runt", "", "", "", false, false, false)
        }
        val fc0 = f[0].toInt() and 0xFF
        val fc1 = f[1].toInt() and 0xFF
        val type = (fc0 shr 2) and 0x3
        val subtype = (fc0 shr 4) and 0xF
        val toDs = (fc1 and 0x01) != 0
        val fromDs = (fc1 and 0x02) != 0
        val typeName = when (type) { 0 -> "mgmt"; 1 -> "ctrl"; 2 -> "data"; else -> "ext" }
        val subName = when (type) {
            0 -> MGMT[subtype]
            1 -> CTRL[subtype]
            2 -> DATA[subtype]
            else -> null
        } ?: "subtype-$subtype"
        val label = "$typeName/$subName"

        val a1 = mac(f, 4)
        // Control frames other than those with a transmitter address carry only addr1.
        val a2 = if (type == 1 && subtype != 8 && subtype != 9 && subtype != 10 &&
            subtype != 11 && subtype != 12 && subtype != 14 && subtype != 15) ""
        else mac(f, 10)
        val a3 = if (type == 1) "" else mac(f, 16)

        val eapol = type == 2 && isEapol(f, subtype, toDs, fromDs)
        return Dot11Info(type, subtype, label, a1, a2, a3, toDs, fromDs, eapol)
    }

    /** True if a data frame's payload begins with the SNAP header for EtherType 0x888E (EAPOL). */
    private fun isEapol(f: ByteArray, subtype: Int, toDs: Boolean, fromDs: Boolean): Boolean {
        var hdr = 24                         // 3-address data header
        if (toDs && fromDs) hdr += 6         // 4th address (WDS)
        if (subtype >= 8) hdr += 2           // QoS control field
        // LLC/SNAP: AA AA 03 00 00 00 <ethertype hi> <ethertype lo>
        if (f.size < hdr + 8) return false
        val snapOk = (f[hdr].toInt() and 0xFF) == 0xAA &&
            (f[hdr + 1].toInt() and 0xFF) == 0xAA &&
            (f[hdr + 2].toInt() and 0xFF) == 0x03
        if (!snapOk) return false
        val eth = ((f[hdr + 6].toInt() and 0xFF) shl 8) or (f[hdr + 7].toInt() and 0xFF)
        return eth == 0x888E
    }

    private fun mac(f: ByteArray, off: Int): String {
        if (f.size < off + 6) return ""
        val sb = StringBuilder(17)
        for (i in 0 until 6) {
            if (i > 0) sb.append(':')
            sb.append("%02x".format(f[off + i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    /** Canonical hex dump (offset + bytes + ascii) for the per-packet detail view. */
    fun hexDump(f: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < f.size) {
            sb.append("%04x  ".format(i))
            val end = minOf(i + 16, f.size)
            for (j in i until i + 16) {
                sb.append(if (j < end) "%02x ".format(f[j].toInt() and 0xFF) else "   ")
                if (j - i == 7) sb.append(' ')
            }
            sb.append(' ')
            for (j in i until end) {
                val c = f[j].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append('\n')
            i += 16
        }
        return sb.toString()
    }
}
