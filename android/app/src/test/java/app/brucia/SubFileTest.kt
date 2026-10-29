package app.brucia

import app.brucia.subghz.SubFile
import app.brucia.subghz.SubSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubFileTest {

    @Test fun protocolRoundTrip() {
        val sig = SubSignal(
            frequencyHz = 433_920_000L,
            preset = SubSignal.DEFAULT_PRESET,
            protocol = "Princeton",
            bits = 24,
            keyHex = "A5B3C7",
            te = 350,
            filetype = SubSignal.DEFAULT_FILETYPE_KEY
        )
        val text = SubFile.generate(sig)
        val parsed = SubFile.parse(text)
        assertNotNull(parsed)
        parsed!!
        assertEquals(433_920_000L, parsed.frequencyHz)
        assertEquals("Princeton", parsed.protocol)
        assertEquals(24, parsed.bits)
        assertEquals("A5B3C7", parsed.keyHex.trimStart('0'))
        assertEquals(350, parsed.te)
        assertTrue(!parsed.isRaw)
    }

    @Test fun rawRoundTrip() {
        val raw = intArrayOf(350, -1050, 1050, -350, 350, -1050, 350, -10850)
        val sig = SubSignal(
            frequencyHz = 315_000_000L,
            preset = SubSignal.DEFAULT_PRESET,
            protocol = "RAW",
            rawData = raw,
            filetype = SubSignal.DEFAULT_FILETYPE_RAW
        )
        val text = SubFile.generate(sig)
        val parsed = SubFile.parse(text)
        assertNotNull(parsed)
        parsed!!
        assertTrue(parsed.isRaw)
        assertEquals(315_000_000L, parsed.frequencyHz)
        assertTrue(raw.contentEquals(parsed.rawData))
    }

    @Test fun parsesMultiLineRawAndVersionWithoutColon() {
        val text = """
            Filetype: Flipper SubGhz RAW File
            Version 1
            Frequency: 433920000
            Preset: FuriHalSubGhzPresetOok650Async
            Protocol: RAW
            RAW_Data: 350 -1050 1050 -350
            RAW_Data: 350 -10850
        """.trimIndent()
        val parsed = SubFile.parse(text)
        assertNotNull(parsed)
        parsed!!
        assertEquals(6, parsed.rawData.size)
        assertEquals(1, parsed.version)
        assertEquals(350, parsed.rawData[0])
        assertEquals(-10850, parsed.rawData[5])
    }

    @Test fun transmitCommandForProtocol() {
        val sig = SubSignal(
            frequencyHz = 433_920_000L,
            preset = SubSignal.DEFAULT_PRESET,
            protocol = "CAME",
            bits = 12,
            keyHex = "A5A",
            te = 320
        )
        val cmd = SubFile.transmitCommand(sig, 10)
        assertNotNull(cmd)
        assertEquals("subghz txp CAME 433920000 12 A5A 320 10", cmd)
    }

    @Test fun rawWithUnknownProtocolHasNoTxCommand() {
        // Random noise timings decode to nothing -> cannot transmit over USB.
        val sig = SubSignal(
            frequencyHz = 433_920_000L,
            preset = SubSignal.DEFAULT_PRESET,
            protocol = "RAW",
            rawData = intArrayOf(11, -13, 7, -9)
        )
        assertNull(SubFile.transmitCommand(sig))
    }
}
