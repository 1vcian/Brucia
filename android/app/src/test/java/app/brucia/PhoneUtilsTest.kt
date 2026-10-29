package app.brucia

import app.brucia.utils.PhoneUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class PhoneUtilsTest {

    @Test fun charsetRespectsToggles() {
        assertEquals("", PhoneUtils.charset(false, false, false, false))
        assertEquals(PhoneUtils.LOWER, PhoneUtils.charset(true, false, false, false))
        val all = PhoneUtils.charset(true, true, true, true)
        assertTrue(all.contains('a'))
        assertTrue(all.contains('A'))
        assertTrue(all.contains('0'))
        assertTrue(all.any { it in PhoneUtils.SYMBOLS })
    }

    @Test fun passwordLengthAndPoolAreHonoured() {
        val pw = PhoneUtils.generatePassword(32, PhoneUtils.DIGITS, Random(1))
        assertEquals(32, pw.length)
        assertTrue(pw.all { it.isDigit() })
    }

    @Test fun passwordEdgeCases() {
        assertEquals("", PhoneUtils.generatePassword(0, PhoneUtils.LOWER))
        assertEquals("", PhoneUtils.generatePassword(8, ""))
    }

    @Test fun passwordIsDeterministicWithSeededRng() {
        val a = PhoneUtils.generatePassword(20, PhoneUtils.charset(true, true, true, false), Random(42))
        val b = PhoneUtils.generatePassword(20, PhoneUtils.charset(true, true, true, false), Random(42))
        assertEquals(a, b)
    }

    @Test fun hashesMatchKnownVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", PhoneUtils.md5(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", PhoneUtils.md5("abc"))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", PhoneUtils.sha1("abc"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PhoneUtils.sha256("abc"),
        )
    }

    @Test fun base64RoundTrips() {
        assertEquals("aGVsbG8=", PhoneUtils.base64Encode("hello"))
        assertEquals("hello", PhoneUtils.base64Decode("aGVsbG8="))
        val s = "Brucia - fire & offload"
        assertEquals(s, PhoneUtils.base64Decode(PhoneUtils.base64Encode(s)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun base64DecodeRejectsGarbage() {
        PhoneUtils.base64Decode("not valid base64 @@@")
    }

    @Test fun wifiQrPayloadBuildsAndEscapes() {
        assertEquals("WIFI:T:WPA;S:MyNet;P:secret;;", PhoneUtils.wifiQrPayload("MyNet", "secret", "WPA"))
        assertEquals("WIFI:T:nopass;S:Guest;;", PhoneUtils.wifiQrPayload("Guest", "", "nopass"))
        // Special characters in the SSID must be backslash-escaped.
        assertEquals("WIFI:T:WPA;S:a\\;b;P:p\\:w;;", PhoneUtils.wifiQrPayload("a;b", "p:w", "WPA"))
        assertTrue(PhoneUtils.wifiQrPayload("H", "p", "WPA", hidden = true).contains("H:true;"))
    }
}
