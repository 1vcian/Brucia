package app.brucia

import app.brucia.wifi.Dot11Frame
import app.brucia.wifi.PcapWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class PcapWriterTest {

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    @Test fun globalHeaderIsLinktype105() {
        val h = PcapWriter.globalHeader()
        assertEquals(24, h.size)
        assertEquals(0xa1b2c3d4L, u32(h, 0))         // magic
        assertEquals(2L, (h[4].toLong() and 0xFF))   // version major
        assertEquals(4L, (h[6].toLong() and 0xFF))   // version minor
        assertEquals(65535L, u32(h, 16))             // snaplen
        assertEquals(105L, u32(h, 20))               // LINKTYPE_IEEE802_11
    }

    @Test fun packetRecordSplitsMicrosAndCarriesPayload() {
        val data = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val rec = PcapWriter.packetRecord(1_500_000L, data)      // 1.5 s
        assertEquals(16 + 3, rec.size)
        assertEquals(1L, u32(rec, 0))                            // ts_sec
        assertEquals(500_000L, u32(rec, 4))                      // ts_usec
        assertEquals(3L, u32(rec, 8))                            // incl_len
        assertEquals(3L, u32(rec, 12))                           // orig_len
        assertArrayEquals(data, rec.copyOfRange(16, 19))
    }

    @Test fun writeProducesHeaderThenRecords() {
        val f1 = byteArrayOf(1, 2, 3)
        val f2 = byteArrayOf(4, 5)
        val out = ByteArrayOutputStream()
        PcapWriter.write(out, listOf(0L to f1, 1_000_000L to f2))
        val bytes = out.toByteArray()
        // 24 (global) + 16 + 3 + 16 + 2
        assertEquals(24 + 16 + 3 + 16 + 2, bytes.size)
        assertEquals(0xa1b2c3d4L, u32(bytes, 0))
        assertEquals(3L, u32(bytes, 24 + 8))                     // first incl_len
        assertEquals(1L, u32(bytes, 24 + 16 + 3 + 0))            // second ts_sec
    }

    @Test fun parseBeacon() {
        // Frame control 0x80 0x00 = mgmt (type 0) / beacon (subtype 8).
        val f = ByteArray(24)
        f[0] = 0x80.toByte()
        // addr3 (bssid) = 11:22:33:44:55:66 at offset 16
        val bssid = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        System.arraycopy(bssid, 0, f, 16, 6)
        val info = Dot11Frame.parse(f)
        assertEquals(0, info.type)
        assertEquals(8, info.subtype)
        assertEquals("mgmt/beacon", info.label)
        assertEquals("11:22:33:44:55:66", info.addr3)
        assertFalse(info.isEapol)
    }

    @Test fun parseAckHasOnlyAddr1() {
        // 0xD4 0x00 = ctrl (type 1) / ack (subtype 13).
        val f = ByteArray(10)
        f[0] = 0xD4.toByte()
        val info = Dot11Frame.parse(f)
        assertEquals(1, info.type)
        assertEquals("ctrl/ack", info.label)
        assertEquals("", info.addr2)
        assertEquals("", info.addr3)
    }

    @Test fun detectsEapolInQosDataFrame() {
        // 0x88 0x00 = data (type 2) / qos-data (subtype 8); header = 24 + 2 (QoS) = 26.
        val f = ByteArray(26 + 8)
        f[0] = 0x88.toByte()
        val snap = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03, 0, 0, 0, 0x88.toByte(), 0x8E.toByte())
        System.arraycopy(snap, 0, f, 26, 8)
        val info = Dot11Frame.parse(f)
        assertEquals(2, info.type)
        assertTrue(info.isEapol)
    }

    @Test fun nonEapolDataFrame() {
        val f = ByteArray(24 + 8)
        f[0] = 0x08.toByte()   // data / data
        // SNAP with IPv4 ethertype 0x0800 -> not EAPOL
        val snap = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03, 0, 0, 0, 0x08, 0x00)
        System.arraycopy(snap, 0, f, 24, 8)
        assertFalse(Dot11Frame.parse(f).isEapol)
    }
}
