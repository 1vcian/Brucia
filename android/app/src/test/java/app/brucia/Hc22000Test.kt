package app.brucia

import app.brucia.wifi.Handshake
import app.brucia.wifi.Hc22000
import app.brucia.wifi.WpaCracker
import app.brucia.wifi.PmkidTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Hc22000Test {
    @Test fun handshakeLine() {
        val h = Handshake(
            "Net", byteArrayOf(0,1,2,3,4,5), byteArrayOf(6,7,8,9,10,11),
            ByteArray(32), ByteArray(32) { 1 }, ByteArray(99) { 2 }, ByteArray(16) { 3 }, 2,
        )
        val line = Hc22000.export(h)
        val parts = line.split("*")
        assertEquals("WPA", parts[0]); assertEquals("02", parts[1])
        assertEquals(9, parts.size)
        assertEquals(32, parts[2].length)                 // MIC 16 bytes
        assertEquals("4e6574", parts[5])                  // "Net" hex
        assertEquals(64, parts[6].length)                 // ANonce 32 bytes
    }

    @Test fun pmkidLine() {
        val p = PmkidTarget(ByteArray(16), byteArrayOf(0,1,2,3,4,5), byteArrayOf(6,7,8,9,10,11), "AB")
        val line = Hc22000.export(p)
        assertTrue(line.startsWith("WPA*01*"))
        assertTrue(line.endsWith("*4142***"))             // essid "AB" + empty tail
    }

    @Test fun roundTripHandshakeExportParseCrack() {
        val ap = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        val cl = byteArrayOf(0x66, 0x77, 0x2b.toByte(), 0x11, 0x22, 0x33)
        val aNonce = ByteArray(32) { it.toByte() }
        val sNonce = ByteArray(32) { (31 - it).toByte() }
        // Build an EAPOL M2 (99 bytes) with sNonce at offset 17, MIC zeroed.
        val eapol = ByteArray(99)
        eapol[0] = 2; eapol[1] = 3; eapol[3] = 95
        eapol[5] = 0x01; eapol[6] = 0x0a  // key info: pairwise + mic + ver2
        for (i in 0 until 32) eapol[17 + i] = sNonce[i]
        val pass = "testpass123"
        val base = app.brucia.wifi.Handshake("Net22000", ap, cl, aNonce, sNonce, eapol, ByteArray(16), 2)
        val mic = WpaCracker.computeMic(pass, base)
        val hs = base.copy(mic = mic)
        val line = Hc22000.export(hs)
        val (parsed, pm) = Hc22000.parse(line)!!
        assertTrue(pm == null)
        val found = WpaCracker.crack(parsed!!, sequenceOf("x1234567", pass, "y1234567"))
        assertEquals(pass, found)
    }
}
