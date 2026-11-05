package app.brucia

import app.brucia.wifi.Dot11Frame
import app.brucia.wifi.PcapReader
import app.brucia.wifi.PcapWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Classic pcap input as the device or another tool writes it: byte order, ns variant, radiotap. */
class PcapReaderTest {

    private fun u32(v: Long, le: Boolean) = if (le)
        byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    else byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun pcap(magic: Long, le: Boolean, linktype: Long, vararg recs: Triple<Long, Long, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(u32(magic, le)); o.write(ByteArray(4)); o.write(ByteArray(8)); o.write(u32(65535, le)); o.write(u32(linktype, le))
        for ((sec, frac, data) in recs) {
            o.write(u32(sec, le)); o.write(u32(frac, le)); o.write(u32(data.size.toLong(), le)); o.write(u32(data.size.toLong(), le)); o.write(data)
        }
        return o.toByteArray()
    }

    private val frame = byteArrayOf(0x80.toByte(), 0x00) + ByteArray(22) { it.toByte() }

    @Test fun bigEndianMicrosecondCapture() {
        val recs = PcapReader.parse(pcap(0xa1b2c3d4L, false, 105, Triple(2, 500_000, frame)))
        assertEquals(1, recs.size)
        assertEquals(2_500_000L, recs[0].tsMicros)
        assertArrayEquals(frame, recs[0].frame)
    }

    @Test fun nanosecondCaptureIsConvertedToMicroseconds() {
        // Magic a1b23c4d: the fraction is nanoseconds. Reading it as microseconds put a packet
        // 0.5 s into the second at +500 s in the dissector's relative time column.
        for (le in listOf(true, false)) {
            val recs = PcapReader.parse(pcap(0xa1b23c4dL, le, 105,
                Triple(1, 500_000_000, frame), Triple(1, 750_000_123, frame)))
            assertEquals(listOf(1_500_000L, 1_750_000L), recs.map { it.tsMicros })
        }
    }

    @Test fun radiotapHeaderIsStrippedOnlyWhenItsLengthFits() {
        val rt = byteArrayOf(0x00, 0x00, 0x08, 0x00, 0, 0, 0, 0)
        val recs = PcapReader.parse(pcap(0xa1b2c3d4L, true, 127,
            Triple(0, 0, rt + frame),
            Triple(0, 0, byteArrayOf(0x00, 0x00, 0x40, 0x00) + frame), // it_len beyond the record: kept raw
        ))
        assertArrayEquals(frame, recs[0].frame)
        assertEquals(4 + frame.size, recs[1].frame.size)
        assertEquals("mgmt/beacon", Dot11Frame.parse(recs[0].frame).label)
    }

    @Test fun writerOutputRoundTrips() {
        val bytes = ByteArrayOutputStream().also { PcapWriter.write(it, listOf(123_456_789L to frame, 123_456_790L to frame)) }.toByteArray()
        assertEquals(listOf(123_456_789L, 123_456_790L), PcapReader.parse(bytes).map { it.tsMicros })
    }

    @Test fun truncatedAndCorruptRecordsStopCleanly() {
        val good = pcap(0xa1b2c3d4L, true, 105, Triple(0, 0, frame))
        assertEquals(1, PcapReader.parse(good + u32(0, true) + u32(0, true) + u32(5000, true)).size) // short body
        assertEquals(1, PcapReader.parse(good + ByteArray(16) + frame).size) // caplen 0
        assertEquals(1, PcapReader.parse(good + ByteArray(8) + u32(0x7FFFFFF0L, true) + ByteArray(20)).size)
        assertTrue(PcapReader.parse("not a pcap at all, just text".toByteArray()).isEmpty())
    }

    @Test fun dot11HeaderDecodesRuntsControlFramesAndWdsEapol() {
        assertEquals("runt", Dot11Frame.parse(byteArrayOf(0x08)).label)
        val rts = Dot11Frame.parse(byteArrayOf(0xB4.toByte(), 0) + ByteArray(14) { 1 })
        assertEquals("ctrl/rts", rts.label); assertEquals("01:01:01:01:01:01", rts.addr2); assertEquals("", rts.addr3)
        val ack = Dot11Frame.parse(byteArrayOf(0xD4.toByte(), 0) + ByteArray(8))
        assertEquals("ctrl/ack", ack.label); assertEquals("", ack.addr2)
        assertEquals("ext/subtype-0", Dot11Frame.parse(byteArrayOf(0x0C, 0) + ByteArray(22)).label)
        // Four-address (toDS+fromDS) QoS data: SNAP sits after 30 + 2 header bytes.
        val snap = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03, 0, 0, 0, 0x88.toByte(), 0x8E.toByte())
        val wds = byteArrayOf(0x88.toByte(), 0x03) + ByteArray(30) + snap
        assertTrue(Dot11Frame.parse(wds).isEapol)
        assertFalse(Dot11Frame.parse(byteArrayOf(0x88.toByte(), 0x00) + ByteArray(30) + snap).isEapol)
    }

    @Test fun hexDumpLayout() {
        val dump = Dot11Frame.hexDump(ByteArray(18) { (0x41 + it).toByte() })
        assertEquals(
            "0000  41 42 43 44 45 46 47 48  49 4a 4b 4c 4d 4e 4f 50  ABCDEFGHIJKLMNOP\n" +
                "0010  51 52                                             QR\n",
            dump,
        )
        assertEquals("", Dot11Frame.hexDump(ByteArray(0)))
    }
}
