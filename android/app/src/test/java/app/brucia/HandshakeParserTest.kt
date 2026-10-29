package app.brucia

import app.brucia.wifi.Handshake
import app.brucia.wifi.HandshakeParser
import app.brucia.wifi.WpaCracker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class HandshakeParserTest {

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun record(data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(le32(0)); o.write(le32(0))          // ts
        o.write(le32(data.size.toLong())); o.write(le32(data.size.toLong()))
        o.write(data)
        return o.toByteArray()
    }

    private fun beacon(ssid: String, bssid: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00))    // FC beacon + dur
        o.write(byteArrayOf(-1, -1, -1, -1, -1, -1))             // a1 broadcast
        o.write(bssid); o.write(bssid)                           // a2,a3
        o.write(byteArrayOf(0x00, 0x00))                         // seq
        o.write(ByteArray(12))                                   // timestamp+interval+caps
        o.write(byteArrayOf(0x00, ssid.length.toByte()))         // SSID IE
        o.write(ssid.toByteArray(Charsets.UTF_8))
        return o.toByteArray()
    }

    // EAPOL-Key 802.11 data frame. mic==null -> M1 (fromDS), else M2 (toDS).
    private fun eapolFrame(bssid: ByteArray, sta: ByteArray, keyInfo: Int, nonce: ByteArray, mic: ByteArray?): ByteArray {
        val o = ByteArrayOutputStream()
        val fc1 = if (mic == null) 0x02 else 0x01                // fromDS / toDS
        o.write(byteArrayOf(0x08, fc1.toByte(), 0x00, 0x00))     // FC data + dur
        if (mic == null) { o.write(sta); o.write(bssid); o.write(bssid) } // a1=sta,a2=bssid,a3=bssid
        else { o.write(bssid); o.write(sta); o.write(bssid) }    // a1=bssid,a2=sta,a3=bssid
        o.write(byteArrayOf(0x00, 0x00))                         // seq
        o.write(byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03, 0x00, 0x00, 0x00, 0x88.toByte(), 0x8E.toByte()))
        // EAPOL header
        val body = ByteArrayOutputStream()
        body.write(0x02)                                         // descriptor type
        body.write(be16(keyInfo))
        body.write(be16(16))                                     // key length
        body.write(ByteArray(8))                                 // replay
        body.write(nonce)                                        // 32
        body.write(ByteArray(16))                                // iv
        body.write(ByteArray(8))                                 // rsc
        body.write(ByteArray(8))                                 // key id
        body.write(mic ?: ByteArray(16))                         // mic
        body.write(be16(0))                                      // key data len
        val b = body.toByteArray()
        o.write(0x02)                                            // EAPOL version
        o.write(0x03)                                            // type = key
        o.write(be16(b.size))                                    // body length
        o.write(b)
        return o.toByteArray()
    }

    @Test fun parsesSyntheticPcapAndCracks() {
        val ssid = "BruciaNet"
        val bssid = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        val sta = byteArrayOf(0x66, 0x77, 0x2b.toByte(), 0x11, 0x22, 0x33)
        val aNonce = ByteArray(32) { it.toByte() }
        val sNonce = ByteArray(32) { (31 - it).toByte() }
        val pass = "testpass123"

        // Build M2 with zeroed MIC, compute real MIC, then rebuild with it.
        val m2Zero = eapolFrame(bssid, sta, 0x010a, sNonce, ByteArray(16))
        // EAPOL frame starts after 24 (hdr) + 8 (LLC) = 32 in this data frame
        val eapolZero = m2Zero.copyOfRange(32, m2Zero.size)
        val base = Handshake(ssid, bssid, sta, aNonce, sNonce, eapolZero, ByteArray(16), 2)
        val mic = WpaCracker.computeMic(pass, base)
        val m2 = eapolFrame(bssid, sta, 0x010a, sNonce, mic)
        val m1 = eapolFrame(bssid, sta, 0x008a, aNonce, null)

        val pcap = ByteArrayOutputStream()
        pcap.write(le32(0xa1b2c3d4L))
        pcap.write(le16(2)); pcap.write(le16(4))                 // version
        pcap.write(le32(0)); pcap.write(le32(0))                 // thiszone, sigfigs
        pcap.write(le32(65535)); pcap.write(le32(105))           // snaplen, linktype raw 802.11
        pcap.write(record(beacon(ssid, bssid)))
        pcap.write(record(m1))
        pcap.write(record(m2))

        val hs = HandshakeParser.parse(pcap.toByteArray())
        assertNotNull("handshake should parse", hs)
        assertEquals(ssid, hs!!.ssid)
        assertArrayEquals(bssid, hs.apMac)
        assertArrayEquals(sta, hs.clMac)
        assertArrayEquals(aNonce, hs.aNonce)
        assertArrayEquals(sNonce, hs.sNonce)

        val found = WpaCracker.crack(hs, sequenceOf("nope1234", pass, "later999"))
        assertEquals(pass, found)
    }
}
