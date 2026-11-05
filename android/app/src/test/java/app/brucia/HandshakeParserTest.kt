package app.brucia

import app.brucia.wifi.Handshake
import app.brucia.wifi.HandshakeParser
import app.brucia.wifi.WpaCracker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // --- 4-way handshake ordering, PMKID, radiotap and malformed records -------------------

    private val bssid = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
    private val sta = byteArrayOf(0x66, 0x77, 0x2b.toByte(), 0x11, 0x22, 0x33)
    private val aNonce = ByteArray(32) { it.toByte() }
    private val sNonce = ByteArray(32) { (31 - it).toByte() }

    /** EAPOL-Key data frame with explicit direction, nonce, MIC and key data. */
    private fun keyFrame(fromAp: Boolean, keyInfo: Int, nonce: ByteArray, mic: ByteArray, keyData: ByteArray = ByteArray(0)): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0x08, (if (fromAp) 0x02 else 0x01).toByte(), 0x00, 0x00))
        if (fromAp) { o.write(sta); o.write(bssid); o.write(bssid) } else { o.write(bssid); o.write(sta); o.write(bssid) }
        o.write(byteArrayOf(0x00, 0x00))
        o.write(byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03, 0x00, 0x00, 0x00, 0x88.toByte(), 0x8E.toByte()))
        val body = ByteArrayOutputStream()
        body.write(0x02); body.write(be16(keyInfo)); body.write(be16(16)); body.write(ByteArray(8))
        body.write(nonce); body.write(ByteArray(16)); body.write(ByteArray(8)); body.write(ByteArray(8))
        body.write(mic); body.write(be16(keyData.size)); body.write(keyData)
        val b = body.toByteArray()
        o.write(0x02); o.write(0x03); o.write(be16(b.size)); o.write(b)
        return o.toByteArray()
    }

    private fun pcap(linktype: Long, vararg frames: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(le32(0xa1b2c3d4L)); o.write(le16(2)); o.write(le16(4)); o.write(le32(0)); o.write(le32(0))
        o.write(le32(65535)); o.write(le32(linktype))
        for (f in frames) o.write(record(f))
        return o.toByteArray()
    }

    private fun radiotap(frame: ByteArray) = byteArrayOf(0x00, 0x00, 0x0C, 0x00) + ByteArray(8) + frame

    /** M1, M2 with the real MIC for [pass], M3 and M4 as a client and AP actually exchange them. */
    private fun fourWay(pass: String): List<ByteArray> {
        val m2Zero = keyFrame(false, 0x010a, sNonce, ByteArray(16))
        val base = Handshake("BruciaNet", bssid, sta, aNonce, sNonce, m2Zero.copyOfRange(32, m2Zero.size), ByteArray(16), 2)
        val mic = WpaCracker.computeMic(pass, base)
        return listOf(
            keyFrame(true, 0x008a, aNonce, ByteArray(16)),                // M1: ACK, no MIC
            keyFrame(false, 0x010a, sNonce, mic),                          // M2: MIC, SNonce
            keyFrame(true, 0x13ca, aNonce, ByteArray(16) { 0x5A }),        // M3: ACK+MIC+Install+Secure, ANonce
            keyFrame(false, 0x030a, ByteArray(32), ByteArray(16) { 0x3C }), // M4: MIC+Secure, zero nonce
        )
    }

    @Test fun completeFourWayHandshakeKeepsMessageTwo() {
        // A capture holding all four messages (the normal case for a full association) must still
        // crack: M3 (ANonce, ACK set) and M4 (zero nonce) are not the client's M2.
        val pass = "testpass123"
        val hs = HandshakeParser.parse(pcap(105, beacon("BruciaNet", bssid), *fourWay(pass).toTypedArray()))
        assertNotNull(hs)
        assertArrayEquals(aNonce, hs!!.aNonce)
        assertArrayEquals(sNonce, hs.sNonce)
        assertArrayEquals(bssid, hs.apMac)
        assertArrayEquals(sta, hs.clMac)
        assertEquals(pass, WpaCracker.crack(hs, sequenceOf("wrongpass1", pass)))
    }

    @Test fun radiotapCaptureAndSsidOverrideParse() {
        val pass = "radiotap99"
        val frames = fourWay(pass).take(2).map { radiotap(it) }
        // No beacon: the ESSID comes from the override, as the capture workflow supplies it.
        assertNull(HandshakeParser.parse(pcap(127, *frames.toTypedArray())))
        val hs = HandshakeParser.parse(pcap(127, *frames.toTypedArray()), ssidOverride = "BruciaNet")
        assertNotNull(hs)
        assertEquals("BruciaNet", hs!!.ssid)
        assertEquals(pass, WpaCracker.crack(hs, sequenceOf(pass)))
    }

    @Test fun hugeRecordLengthStopsParsingWithoutThrowing() {
        // Corrupt record header after a complete handshake: caplen 0x7FFFFFF0 made start + caplen
        // overflow Int, the bounds check passed and the next header read went out of bounds.
        val good = pcap(105, beacon("BruciaNet", bssid), *fourWay("testpass123").take(2).toTypedArray())
        val corrupt = good + le32(0) + le32(0) + le32(0x7FFFFFF0L) + le32(0x7FFFFFF0L) + ByteArray(40)
        assertNotNull(HandshakeParser.parse(corrupt))
        assertNull(HandshakeParser.parsePmkid(corrupt))
        assertEquals(emptyList<Any>(), app.brucia.wifi.PcapReader.parse(
            pcap(105) + le32(0) + le32(0) + le32(0x7FFFFFF0L) + le32(0x7FFFFFF0L) + ByteArray(40)))
    }

    @Test fun notPcapOrTruncatedReturnsNull() {
        assertNull(HandshakeParser.parse(ByteArray(10)))
        assertNull(HandshakeParser.parse(ByteArray(64)))
        assertNull(HandshakeParser.parsePmkid("<html>".toByteArray() + ByteArray(40)))
        // M1 only: no client half.
        assertNull(HandshakeParser.parse(pcap(105, beacon("X", bssid), fourWay("p").first())))
    }

    private fun pmkidKde(pmkid: ByteArray) = byteArrayOf(0xDD.toByte(), 0x14, 0x00, 0x0F, 0xAC.toByte(), 0x04) + pmkid

    @Test fun pmkidIsTakenFromMessageOneKeyData() {
        val pmkid = ByteArray(16) { (0xA0 + it).toByte() }
        // RSN IE first (skipped), then the PMKID KDE.
        val keyData = byteArrayOf(0x30, 0x02, 0x01, 0x00) + pmkidKde(pmkid)
        val m1 = keyFrame(true, 0x008a, aNonce, ByteArray(16), keyData)
        val t = HandshakeParser.parsePmkid(pcap(105, beacon("PmkNet", bssid), m1))
        assertNotNull(t)
        assertArrayEquals(pmkid, t!!.pmkid)
        assertArrayEquals(bssid, t.apMac)
        assertArrayEquals(sta, t.staMac)
        assertEquals("PmkNet", t.ssid)
        // The ESSID override is used when no beacon was captured.
        assertEquals("Other", HandshakeParser.parsePmkid(pcap(105, m1), ssidOverride = "Other")!!.ssid)
        assertNull("no ESSID known", HandshakeParser.parsePmkid(pcap(105, m1)))
    }

    @Test fun pmkidIgnoresZeroPmkidMessagesWithMicAndOverlongKde() {
        val zero = keyFrame(true, 0x008a, aNonce, ByteArray(16), pmkidKde(ByteArray(16)))
        assertNull(HandshakeParser.parsePmkid(pcap(105, beacon("N", bssid), zero)))
        val withMic = keyFrame(false, 0x010a, sNonce, ByteArray(16) { 1 }, pmkidKde(ByteArray(16) { 7 }))
        assertNull(HandshakeParser.parsePmkid(pcap(105, beacon("N", bssid), withMic)))
        // KDE claims more bytes than the key data holds: stop, do not read past the frame.
        val overlong = byteArrayOf(0xDD.toByte(), 0x40, 0x00, 0x0F, 0xAC.toByte(), 0x04) + ByteArray(16) { 9 }
        assertNull(HandshakeParser.parsePmkid(pcap(105, beacon("N", bssid), keyFrame(true, 0x008a, aNonce, ByteArray(16), overlong))))
    }
}
