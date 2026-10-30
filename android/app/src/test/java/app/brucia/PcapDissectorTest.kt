package app.brucia

import app.brucia.wifi.PcapDissector
import app.brucia.wifi.PcapWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class PcapDissectorTest {

    private fun mac(last: Int) = byteArrayOf(0x02, 0, 0, 0, 0, last.toByte())
    private fun macStr(last: Int) = "02:00:00:00:00:%02x".format(last)

    /** mgmt/beacon: addr1 broadcast, addr2 = addr3 = BSSID. */
    private fun beacon(bssid: Int): ByteArray {
        val f = ByteArray(24)
        f[0] = 0x80.toByte()
        for (i in 4 until 10) f[i] = 0xFF.toByte()
        System.arraycopy(mac(bssid), 0, f, 10, 6)
        System.arraycopy(mac(bssid), 0, f, 16, 6)
        return f
    }

    /** ctrl/ack: only addr1. */
    private fun ack(): ByteArray = ByteArray(10).also { it[0] = 0xD4.toByte(); System.arraycopy(mac(0x10), 0, it, 4, 6) }

    /** data/qos-data with an LLC/SNAP header for EtherType 0x888E (EAPOL) after the 26-byte header. */
    private fun eapolData(bssid: Int): ByteArray {
        val f = ByteArray(26 + 8)
        f[0] = 0x88.toByte()
        System.arraycopy(mac(bssid), 0, f, 16, 6)
        val snap = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03, 0, 0, 0, 0x88.toByte(), 0x8E.toByte())
        System.arraycopy(snap, 0, f, 26, 8)
        return f
    }

    private fun pcap(frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        PcapWriter.write(out, frames.mapIndexed { i, f -> i.toLong() * 1000 to f })
        return out.toByteArray()
    }

    /** Endless-or-sized stream of zero bytes that records how much was consumed. */
    private class ZeroStream(private var remaining: Long) : InputStream() {
        var consumed = 0L
        override fun read(): Int = if (remaining <= 0) -1 else { remaining--; consumed++; 0 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            b.fill(0, off, off + n)
            remaining -= n; consumed += n
            return n
        }
    }

    @Test fun countsTypesAndEapol() {
        val r = PcapDissector.dissect(pcap(listOf(beacon(1), ack(), eapolData(1), beacon(2))))
        assertNotNull(r)
        val s = r!!.summary
        assertEquals(4, s.frames)
        assertEquals(2, s.mgmt)
        assertEquals(1, s.ctrl)
        assertEquals(1, s.data)
        assertEquals(1, s.eapol)
        assertEquals(listOf(macStr(1) to 2, macStr(2) to 1), s.bssids) // ack has no addr3
        assertEquals(4, r.rows.size)
        val b = r.rows[0]
        assertEquals(1, b.n)
        assertEquals("mgmt/beacon", b.label)
        assertEquals(macStr(1), b.src)
        assertEquals("ff:ff:ff:ff:ff:ff", b.dst)
        assertEquals(macStr(1), b.bssid)
        assertFalse(b.eapol)
        assertEquals("ctrl/ack", r.rows[1].label)
        assertEquals("", r.rows[1].bssid)
        assertTrue(r.rows[2].eapol)
        assertEquals(3, r.rows[2].n)
    }

    @Test fun rowsCappedButSummaryCountsAll() {
        val r = PcapDissector.dissect(pcap(List(PcapDissector.MAX_ROWS + 1) { beacon(1) }))!!
        assertEquals(1001, r.summary.frames)
        assertEquals(1001, r.summary.mgmt)
        assertEquals(1000, r.rows.size)
        assertEquals(1000, r.rows.last().n)
        assertEquals(listOf(macStr(1) to 1001), r.summary.bssids)
    }

    @Test fun bssidRankingIsDeterministicWithTiesAndTopEightCut() {
        val counts = mapOf(9 to 3, 5 to 3, 7 to 2, 3 to 2, 0 to 1, 1 to 1, 2 to 1, 4 to 1, 6 to 1, 8 to 1)
        val frames = counts.flatMap { (k, n) -> List(n) { beacon(k) } }
        val expected = listOf(5 to 3, 9 to 3, 3 to 2, 7 to 2, 0 to 1, 1 to 1, 2 to 1, 4 to 1).map { macStr(it.first) to it.second }
        assertEquals(expected, PcapDissector.dissect(pcap(frames))!!.summary.bssids)
        assertEquals(expected, PcapDissector.dissect(pcap(frames.reversed()))!!.summary.bssids)
    }

    @Test fun invalidEmptyOrTruncatedInput() {
        assertNull(PcapDissector.dissect(ByteArray(0)))
        assertNull(PcapDissector.dissect(PcapWriter.globalHeader().copyOf(23)))
        assertNull(PcapDissector.dissect(PcapWriter.globalHeader()))                   // header, no records
        val bad = pcap(listOf(beacon(1))).also { it[0] = 0 }
        assertNull(PcapDissector.dissect(bad))                                         // invalid magic
        val two = pcap(listOf(beacon(1), beacon(2)))
        assertNull(PcapDissector.dissect(two.copyOf(24 + 16 + 23)))                    // first record cut
        val partial = PcapDissector.dissect(two.copyOf(two.size - 1))!!                // second record cut
        assertEquals(1, partial.summary.frames)
        assertEquals(1, partial.rows.size)
    }

    @Test fun checkActiveRunsPeriodicallyAndAbortsWhenItThrows() {
        val bytes = pcap(List(1001) { beacon(1) })
        var calls = 0
        PcapDissector.dissect(bytes) { calls++ }
        assertEquals(4, calls) // records 0, 256, 512, 768

        class Stop : RuntimeException()
        var seen = 0
        assertThrows(Stop::class.java) { PcapDissector.dissect(bytes) { if (++seen == 2) throw Stop() } }
        assertEquals(2, seen)
    }

    @Test fun readBoundedAcceptsExactlyTheLimit() {
        val bytes = PcapDissector.readBounded(ZeroStream(PcapDissector.MAX_CAPTURE_BYTES.toLong()))
        assertEquals(32 * 1024 * 1024, bytes.size)
    }

    @Test fun readBoundedRejectsOverLimitWithoutReadingEverything() {
        val e = assertThrows(IOException::class.java) {
            PcapDissector.readBounded(ZeroStream(PcapDissector.MAX_CAPTURE_BYTES + 1L))
        }
        assertEquals("Capture exceeds 32 MiB", e.message)
        val endless = ZeroStream(Long.MAX_VALUE)
        assertThrows(IOException::class.java) { PcapDissector.readBounded(endless) }
        assertTrue(endless.consumed <= PcapDissector.MAX_CAPTURE_BYTES + 64 * 1024L)
    }
}
