package app.brucia

import app.brucia.wardrive.OuiLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OuiLookupTest {

    @Test fun prefixNormalisesSeparatorsAndCase() {
        assertEquals("AABBCC", OuiLookup.prefix("aa:bb:cc:dd:ee:ff"))
        assertEquals("AABBCC", OuiLookup.prefix("AA-BB-CC-DD-EE-FF"))
        assertEquals("AABBCC", OuiLookup.prefix("aabb.ccdd.eeff"))
        assertEquals("286FB9", OuiLookup.prefix("28:6F:B9:01:02:03"))
    }

    @Test fun prefixRejectsShortOrJunk() {
        assertNull(OuiLookup.prefix("AA:BB"))
        assertNull(OuiLookup.prefix("not-a-mac"))
        assertNull(OuiLookup.prefix(""))
    }

    @Test fun parseReadsTabSeparatedTable() {
        val table = OuiLookup.parse(
            "286FB9\tNokia Shanghai Bell Co., Ltd.\nE80AB9\tCisco Systems, Inc\nbad line\n".reader().buffered()
        )
        assertEquals(2, table.size)
        assertEquals("Nokia Shanghai Bell Co., Ltd.", table["286FB9"])
        assertEquals("Cisco Systems, Inc", table["E80AB9"])
    }

    @Test fun vendorResolvesFirstThreeOctets() {
        OuiLookup.installForTest(mapOf("286FB9" to "Nokia Shanghai Bell Co., Ltd.", "E80AB9" to "Cisco Systems, Inc"))
        assertEquals("Nokia Shanghai Bell Co., Ltd.", OuiLookup.vendor("28:6f:b9:aa:bb:cc"))
        assertEquals("Cisco Systems, Inc", OuiLookup.vendor("E80AB9112233"))
        assertNull(OuiLookup.vendor("00:00:00:00:00:00"))
        assertNull(OuiLookup.vendor("xy"))
        assertTrue(OuiLookup.isLoaded)
    }
}
