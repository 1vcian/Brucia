package app.brucia.wifi

import java.io.OutputStream

/**
 * Minimal classic-pcap (libpcap) writer. Produces files Wireshark/tshark can open directly.
 *
 * The capture holds raw IEEE 802.11 frames, so the global header advertises
 * LINKTYPE_IEEE802_11 (105). Opening the exported file in Wireshark and supplying the
 * network PSK (Edit > Preferences > Protocols > IEEE 802.11 > Decryption keys, "wpa-pwd")
 * lets Wireshark perform the CCMP decryption itself - no decryption is attempted on-device.
 *
 * All multi-byte fields are little-endian, matching the magic 0xa1b2c3d4.
 */
object PcapWriter {

    const val LINKTYPE_IEEE802_11 = 105
    const val DEFAULT_SNAPLEN = 65535

    private const val MAGIC = 0xa1b2c3d4L
    private const val VERSION_MAJOR = 2
    private const val VERSION_MINOR = 4

    /** 24-byte libpcap global header. */
    fun globalHeader(linktype: Int = LINKTYPE_IEEE802_11, snaplen: Int = DEFAULT_SNAPLEN): ByteArray {
        val b = ByteArray(24)
        u32(b, 0, MAGIC)
        u16(b, 4, VERSION_MAJOR)
        u16(b, 6, VERSION_MINOR)
        u32(b, 8, 0)            // thiszone (GMT to local correction)
        u32(b, 12, 0)           // sigfigs
        u32(b, 16, snaplen.toLong())
        u32(b, 20, linktype.toLong())
        return b
    }

    /**
     * One 16-byte per-packet record header followed by the raw frame bytes.
     * [tsMicros] is an absolute or relative timestamp in microseconds.
     */
    fun packetRecord(tsMicros: Long, data: ByteArray, snaplen: Int = DEFAULT_SNAPLEN): ByteArray {
        val inclLen = minOf(data.size, snaplen)
        val b = ByteArray(16 + inclLen)
        u32(b, 0, tsMicros / 1_000_000L)        // ts_sec
        u32(b, 4, tsMicros % 1_000_000L)        // ts_usec
        u32(b, 8, inclLen.toLong())             // incl_len
        u32(b, 12, data.size.toLong())          // orig_len
        System.arraycopy(data, 0, b, 16, inclLen)
        return b
    }

    /** Write a full pcap stream: global header then one record per (tsMicros, frame) pair. */
    fun write(
        out: OutputStream,
        frames: List<Pair<Long, ByteArray>>,
        linktype: Int = LINKTYPE_IEEE802_11,
        snaplen: Int = DEFAULT_SNAPLEN,
    ) {
        out.write(globalHeader(linktype, snaplen))
        for ((ts, data) in frames) out.write(packetRecord(ts, data, snaplen))
        out.flush()
    }

    private fun u32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun u16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }
}
