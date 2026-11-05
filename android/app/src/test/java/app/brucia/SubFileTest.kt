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

    private fun key(protocol: String, keyHex: String, bits: Int = 24, freq: Long = 433_920_000L) =
        SubSignal(frequencyHz = freq, preset = SubSignal.DEFAULT_PRESET, protocol = protocol, bits = bits, keyHex = keyHex)

    @Test fun transmitRefusesFilesThatWouldBreakTheDeviceCommand() {
        // The firmware `subghz txp` splits on spaces and converts with std::stoul/stoull, which throw
        // (and abort the device) on non-numeric or out-of-range text. A file-supplied value must not
        // reach it: a multi-word unknown protocol shifts "Atomo" into the frequency slot.
        assertNull(SubFile.transmitCommand(key("CAME Atomo", "A5A")))
        assertNull(SubFile.transmitCommand(key("Princeton", "ZZ12")))
        assertNull(SubFile.transmitCommand(key("Princeton", "1" + "0".repeat(16)))) // 65 bits
        assertNull(SubFile.transmitCommand(key("Princeton", "A5A", freq = 5_000_000_000L))) // > uint32
        assertNull(SubFile.transmitCommand(key("Princeton", "A5A", freq = 0)))
        assertNull(SubFile.transmitCommand(key("Princeton", "", bits = 24)))
        assertNull(SubFile.transmitCommand(key("Princeton", "A5A", bits = 0)))
        // Still accepted: Flipper multi-word names the table maps to a canonical token, leading-zero
        // padded 64-bit keys as Flipper writes them, and unknown single-token names (firmware decides).
        assertEquals("subghz txp NICE_FLO 433920000 12 0000000000000A5A 0 10",
            SubFile.transmitCommand(key("Nice FLO", "0000000000000A5A", bits = 12)))
        assertEquals("subghz txp Somfy 868350000 24 FFFFFFFFFFFFFFFF 0 3",
            SubFile.transmitCommand(key("Somfy", "FFFFFFFFFFFFFFFF", freq = 868_350_000L), repeat = 3))
    }

    @Test fun parsedFlipperKeyFileTransmitsWithCanonicalName() {
        val sig = SubFile.parse("""
            Filetype: Flipper SubGhz Key File
            Version: 1
            Frequency: 433920000
            Preset: FuriHalSubGhzPresetOok650Async
            Protocol: Holtek_HT12X
            Bit: 40
            Key: 00 00 00 12 34 56 78 9A
            TE: 430
        """.trimIndent())!!
        assertEquals("subghz txp Holtek 433920000 40 000000123456789A 430 10", SubFile.transmitCommand(sig))
        assertTrue(SubFile.summary(sig).startsWith("Holtek_HT12X - 40 bits, key 0x000000123456789A @ 433"))
    }

    @Test fun parseRejectsTextWithoutSubFieldsAndDefaultsFiletype() {
        assertNull(SubFile.parse("hello world\nnothing: here"))
        assertNull(SubFile.parse(""))
        // RAW_Data alone is enough; the filetype and protocol default to RAW.
        val raw = SubFile.parse("RAW_Data: 350 -1050 x 1050")!!
        assertEquals("RAW", raw.protocol)
        assertEquals(SubSignal.DEFAULT_FILETYPE_RAW, raw.filetype)
        assertEquals(listOf(350, -1050, 1050), raw.rawData.toList())
        // Unparseable numbers fall back instead of failing the file.
        val k = SubFile.parse("Protocol: CAME\nFrequency: abc\nBit: x\nTE: y\nVersion: z\nKey: a5 a")!!
        assertEquals(0L, k.frequencyHz); assertEquals(0, k.bits); assertEquals(0, k.te); assertEquals(1, k.version)
        assertEquals("A5A", k.keyHex)
        assertEquals(SubSignal.DEFAULT_FILETYPE_KEY, k.filetype)
    }

    @Test fun rawStreamBuildsSignedMarkSpaceAndSplitsLongLines() {
        val sig = SubFile.fromRawAbs(IntArray(600) { 100 + it }, 315_000_000L)
        assertEquals(100, sig.rawData[0]); assertEquals(-101, sig.rawData[1])
        val text = SubFile.generate(sig)
        assertEquals(2, Regex("RAW_Data:").findAll(text).count()) // Flipper's 512 values per line
        assertEquals(sig.rawData.toList(), SubFile.parse(text)!!.rawData.toList())
        assertTrue(SubFile.summary(sig).startsWith("RAW - 600 edges @ "))
    }
}
