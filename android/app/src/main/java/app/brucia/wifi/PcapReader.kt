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

    fun parse(bytes: ByteArray): List<Record> {
        if (bytes.size < 24) return emptyList()
        fun u32le(o: Int) = (bytes[o].toLong() and 0xFF) or ((bytes[o + 1].toLong() and 0xFF) shl 8) or
            ((bytes[o + 2].toLong() and 0xFF) shl 16) or ((bytes[o + 3].toLong() and 0xFF) shl 24)
        fun u32be(o: Int) = ((bytes[o].toLong() and 0xFF) shl 24) or ((bytes[o + 1].toLong() and 0xFF) shl 16) or
            ((bytes[o + 2].toLong() and 0xFF) shl 8) or (bytes[o + 3].toLong() and 0xFF)

        val leMagic = u32le(0)
        val le = leMagic == 0xa1b2c3d4L || leMagic == 0xa1b23c4dL
        val beMagic = u32be(0)
        val be = beMagic == 0xa1b2c3d4L || beMagic == 0xa1b23c4dL
        if (!le && !be) return emptyList() // not a classic pcap
        fun rd32(o: Int) = if (le) u32le(o) else u32be(o)
        // Magic a1b23c4d: the record fraction is nanoseconds, not microseconds.
        val nanos = leMagic == 0xa1b23c4dL || (!le && beMagic == 0xa1b23c4dL)

        val linktype = rd32(20).toInt()
        val out = ArrayList<Record>()
        var off = 24
        while (off + 16 <= bytes.size) {
            val tsSec = rd32(off)
            val tsFrac = rd32(off + 4)
            val caplen = rd32(off + 8).toInt()
            off += 16
            if (caplen <= 0 || off.toLong() + caplen > bytes.size) break
            var frame = bytes.copyOfRange(off, off + caplen)
            if (linktype == LINKTYPE_RADIOTAP && frame.size >= 4) {
                val itLen = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
                if (itLen in 1 until frame.size) frame = frame.copyOfRange(itLen, frame.size)
            }
            out.add(Record(tsSec * 1_000_000 + if (nanos) tsFrac / 1000 else tsFrac, frame))
            off += caplen
            if (out.size >= MAX_RECORDS) break
        }
        return out
    }
}
