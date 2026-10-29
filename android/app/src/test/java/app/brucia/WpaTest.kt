package app.brucia

import app.brucia.wifi.Handshake
import app.brucia.wifi.Pmkid
import app.brucia.wifi.WpaCracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WpaTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun pbkdf2MatchesRfc6070() {
        // RFC 6070: PBKDF2-HMAC-SHA1("password","salt",4096,20) =
        // 4b007901b765489abead49d926f721d065a429c1
        // PMK uses 4096 iters; the first 20 bytes are stable regardless of dkLen.
        val pmk = WpaCracker.pmk("password", "salt")
        assertEquals("4b007901b765489abead49d926f721d065a429c1", hex(pmk.copyOf(20)))
    }

    @Test fun pmkMatchesCanonicalWpaVectors() {
        // Standard IEEE 802.11i WPA-PSK test vectors (as used by hostapd/wpa_supplicant):
        // the full 32-byte PMK = PBKDF2-HMAC-SHA1(passphrase, ssid, 4096, 32).
        assertEquals(
            "f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e",
            hex(WpaCracker.pmk("password", "IEEE")),
        )
        assertEquals(
            "0dc0d6eb90555ed6419756b9a15ec3e3209b63df707dd508d14581f8982721af",
            hex(WpaCracker.pmk("ThisIsAPassword", "ThisIsASSID")),
        )
    }

    @Test fun crackRecoversPassphraseRoundTrip() {
        val ap = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        val cl = byteArrayOf(0x66, 0x77, 0x2b.toByte(), 0x11, 0x22, 0x33)
        val aNonce = ByteArray(32) { it.toByte() }
        val sNonce = ByteArray(32) { (31 - it).toByte() }
        val eapol = ByteArray(99) { (it * 7 % 256).toByte() }
        val base = Handshake("TestNet", ap, cl, aNonce, sNonce, eapol, ByteArray(16), 2)
        val pass = "testpass123"
        val mic = WpaCracker.computeMic(pass, base)
        val hs = base.copy(mic = mic)

        val found = WpaCracker.crack(hs, sequenceOf("wrongone1", "nope2345", pass, "later9999"))
        assertEquals(pass, found)
    }

    @Test fun crackReturnsNullWhenAbsent() {
        val ap = byteArrayOf(1, 2, 3, 4, 5, 6)
        val cl = byteArrayOf(6, 5, 4, 3, 2, 1)
        val base = Handshake("X", ap, cl, ByteArray(32), ByteArray(32) { 1 }, ByteArray(40), ByteArray(16), 2)
        val hs = base.copy(mic = WpaCracker.computeMic("correct0", base))
        assertNull(WpaCracker.crack(hs, sequenceOf("aaaaaaaa", "bbbbbbbb")))
    }

    @Test fun crackStopsWhenCancelled() {
        // The dictionary loop must honour the isCancelled flag and stop before reaching a
        // candidate placed later in the sequence, proving a running crack can be interrupted.
        val ap = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        val cl = byteArrayOf(0x66, 0x77, 0x2b.toByte(), 0x11, 0x22, 0x33)
        val base = Handshake("CancelNet", ap, cl, ByteArray(32), ByteArray(32) { 1 }, ByteArray(40), ByteArray(16), 2)
        val pass = "stopme123"
        val hs = base.copy(mic = WpaCracker.computeMic(pass, base))

        var seen = 0
        // Cancel after the first candidate; the correct passphrase sits third and must be skipped.
        val found = WpaCracker.crack(
            hs, sequenceOf("wrongone1", "wrongtwo2", pass),
            isCancelled = { (seen++) >= 1 },
        )
        assertNull(found)
    }

    @Test fun pmkidRoundTrip() {
        val ap = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        val sta = byteArrayOf(0x66, 0x77, 0x2b.toByte(), 0x11, 0x22, 0x33)
        val ssid = "PmkidNet"
        val pass = "pmkidpass1"
        val target = WpaCracker.pmkid(pass, ssid, ap, sta)
        val found = WpaCracker.crackPmkid(ssid, ap, sta, target, sequenceOf("x1234567", pass, "y1234567"))
        assertEquals(pass, found)
    }

    @Test fun pmkidParse16800() {
        val line = "2582a8281bf9d4308d6f5731d0e61c61*001122334455*66778899aabb*4578616d706c65"
        val t = Pmkid.parse(line)!!
        assertEquals("Example", t.ssid)
        assertEquals(16, t.pmkid.size)
        assertEquals(6, t.apMac.size)
        assertEquals(6, t.staMac.size)
    }
}
