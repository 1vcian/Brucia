package app.brucia

import app.brucia.nfc.NdefBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdefBuilderTest {
    @Test fun uriRecordUsesPrefixCode() {
        val r = NdefBuilder.uri("https://example.com")
        assertEquals(0xD1.toByte(), r[0])          // header
        assertEquals(0x55.toByte(), r[3])          // type 'U'
        assertEquals(0x04.toByte(), r[4])          // https:// prefix code
        val rest = String(r.copyOfRange(5, r.size))
        assertEquals("example.com", rest)
    }

    @Test fun textRecordHasLangAndType() {
        val r = NdefBuilder.text("hi", "en")
        assertEquals(0x54.toByte(), r[3])          // type 'T'
        assertEquals(0x02.toByte(), r[4])          // lang length "en"
        assertTrue(String(r).contains("enhi"))
    }

    @Test fun tlvWrapsMessage() {
        val n = NdefBuilder.uri("https://x.io")
        val t = NdefBuilder.tlv(n)
        assertEquals(0x03.toByte(), t[0])
        assertEquals(n.size.toByte(), t[1])
        assertEquals(0xFE.toByte(), t[t.size - 1])
    }
}
