package app.brucia.wifi

/**
 * Minimal classic-pcap (libpcap) reader - the counterpart to [PcapWriter]. Parses the 24-byte global
 * header and each record into (timestamp, raw frame), handling little/big-endian magic and the
 * nanosecond variant. For radiotap captures (LINKTYPE 127) it strips the radiotap header so the
 * payload is a raw 802.11 frame that [Dot11Frame] can decode; LINKTYPE 105 is already raw 802.11.
 * Everything runs on the phone - the dissection the device's tiny screen could never show.
 */
object PcapReader {
    data class Record(val tsMicros: Long, val frame: ByteArray)

    private const val LINKTYPE_IEEE802_11 = 105
    private const val LINKTYPE_RADIOTAP = 127
    private const val MAX_RECORDS = 50_000 // safety cap for huge captures

    data class Parsed(val records: List<Record>, val notice: String? = null)

    fun parse(bytes: ByteArray): List<Record> = read(bytes).records

    fun read(bytes: ByteArray): Parsed {
        if (bytes.size < 24) return Parsed(emptyList())
        fun u32le(o: Int) = (bytes[o].toLong() and 0xFF) or ((bytes[o + 1].toLong() and 0xFF) shl 8) or
            ((bytes[o + 2].toLong() and 0xFF) shl 16) or ((bytes[o + 3].toLong() and 0xFF) shl 24)
        fun u32be(o: Int) = ((bytes[o].toLong() and 0xFF) shl 24) or ((bytes[o + 1].toLong() and 0xFF) shl 16) or
            ((bytes[o + 2].toLong() and 0xFF) shl 8) or (bytes[o + 3].toLong() and 0xFF)

        val leMagic = u32le(0)
        val le = leMagic == 0xa1b2c3d4L || leMagic == 0xa1b23c4dL
        val beMagic = u32be(0)
        val be = beMagic == 0xa1b2c3d4L || beMagic == 0xa1b23c4dL
        if (!le && !be) return Parsed(emptyList()) // not a classic pcap
        fun rd32(o: Int) = if (le) u32le(o) else u32be(o)
        // Magic a1b23c4d: the record fraction is nanoseconds, not microseconds.
        val nanos = leMagic == 0xa1b23c4dL || (!le && beMagic == 0xa1b23c4dL)

        val linktype = rd32(20).toInt() and 0xffff // upper bits may carry FCS metadata
        if (linktype != LINKTYPE_IEEE802_11 && linktype != LINKTYPE_RADIOTAP)
            return Parsed(emptyList(), "Unsupported PCAP link type: $linktype")
        val out = ArrayList<Record>()
        var off = 24
        while (off + 16 <= bytes.size) {
            val tsSec = rd32(off)
            val tsFrac = rd32(off + 4)
            val caplen = rd32(off + 8).toInt()
            if (caplen <= 0 || off.toLong() + 16 + caplen > bytes.size) break
            off += 16
            var frame = bytes.copyOfRange(off, off + caplen)
            if (linktype == LINKTYPE_RADIOTAP) {
                val headerLength = if (frame.size >= 8 && frame[0] == 0.toByte())
                    (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8) else 0
                if (headerLength !in 8 until frame.size) {
                    return Parsed(out, "Partial capture: invalid radiotap header.")
                }
                frame = frame.copyOfRange(headerLength, frame.size)
            }
            out.add(Record(tsSec * 1_000_000 + if (nanos) tsFrac / 1000 else tsFrac, frame))
            off += caplen
            if (out.size >= MAX_RECORDS) break
        }
        val notice = when {
            off == bytes.size -> null
            out.size == MAX_RECORDS -> "Partial capture: reached the 50,000-record limit."
            else -> "Partial capture: incomplete or invalid packet record."
        }
        return Parsed(out, notice)
    }
}
