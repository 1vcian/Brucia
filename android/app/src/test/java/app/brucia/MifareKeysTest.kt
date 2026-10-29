package app.brucia

import app.brucia.nfc.NfcSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MifareKeysTest {
    @Test fun parseFiltersInvalidLines() {
        val text = "FFFFFFFFFFFF\n# comment\nA0A1A2A3A4A5\nxyz\n1122\nffffffffffff\n"
        val keys = NfcSecurity.parseKeys(text)
        assertTrue(keys.contains("FFFFFFFFFFFF"))
        assertTrue(keys.contains("A0A1A2A3A4A5"))
        assertFalse(keys.contains("XYZ"))
        assertEquals(2, keys.size) // dedup FFFF.. (case-insensitive), drop junk
    }
}
