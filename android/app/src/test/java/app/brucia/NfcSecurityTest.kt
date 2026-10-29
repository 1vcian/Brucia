package app.brucia

import app.brucia.nfc.NfcSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcSecurityTest {
    @Test fun flagsMifareClassic() {
        val read = "UID:   04 A2 B3 C4\nType:  MIFARE Classic 1K\nSAK:   08\nATQA:  00 04\nPages: 16"
        val f = NfcSecurity.analyze(read)
        assertEquals("high", f.first().severity)
        assertTrue(f.first().title.contains("Classic"))
    }

    @Test fun flagsUltralightAndShortUid() {
        val read = "UID:   04 A2 B3 C4\nType:  MIFARE Ultralight\nSAK:   00"
        val f = NfcSecurity.analyze(read)
        assertTrue(f.any { it.title.contains("Ultralight") })
        assertTrue(f.any { it.title.contains("4-byte UID") })
    }

    @Test fun defaultKeyDictionaryNonTrivial() {
        assertTrue(NfcSecurity.defaultKeys.size >= 10)
    }
}
