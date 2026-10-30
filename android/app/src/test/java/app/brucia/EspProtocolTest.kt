package app.brucia

import app.brucia.update.AppUpdater
import app.brucia.update.EspProtocol
import app.brucia.update.SlipCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EspProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun slipEncodeEscapesDelimiterAndEscapeBytes() {
        val payload = bytes(0x00, 0xC0, 0x11, 0xDB, 0x22)
        val expected = bytes(0xC0, 0x00, 0xDB, 0xDC, 0x11, 0xDB, 0xDD, 0x22, 0xC0)
        assertArrayEquals(expected, SlipCodec.encode(payload))
    }

    @Test fun slipEncodeWrapsPlainPayload() {
        val payload = bytes(0x01, 0x02, 0x03)
        val expected = bytes(0xC0, 0x01, 0x02, 0x03, 0xC0)
        assertArrayEquals(expected, SlipCodec.encode(payload))
    }

    @Test fun slipDecodeIsInverseOfEncode() {
        val payload = bytes(0xC0, 0xDB, 0x00, 0xFF, 0xC0, 0xDB)
        val framed = SlipCodec.encode(payload)
        // Strip the leading/trailing END delimiters before decode().
        val inner = framed.copyOfRange(1, framed.size - 1)
        assertArrayEquals(payload, SlipCodec.decode(inner))
    }

    @Test fun frameReaderExtractsCompleteFrame() {
        val payload = bytes(0x01, 0x08, 0xC0, 0xDB)
        val stream = SlipCodec.encode(payload)
        val reader = SlipCodec.FrameReader()
        var result: ByteArray? = null
        for (b in stream) {
            val f = reader.offer(b.toInt() and 0xFF)
            if (f != null) result = f
        }
        assertArrayEquals(payload, result)
    }

    @Test fun frameReaderToleratesBackToBackDelimiters() {
        val reader = SlipCodec.FrameReader()
        // Two END bytes in a row must not produce an empty frame.
        assertNull(reader.offer(0xC0))
        assertNull(reader.offer(0xC0))
        assertNull(reader.offer(0x42))
        val f = reader.offer(0xC0)
        assertArrayEquals(bytes(0x42), f)
    }

    @Test fun checksumMatchesMagicSeed() {
        assertEquals(0xEF, EspProtocol.checksum(ByteArray(0)))
        assertEquals(0xEF xor 0x55, EspProtocol.checksum(bytes(0x55)))
        assertEquals(0xEF xor 0x01 xor 0x02, EspProtocol.checksum(bytes(0x01, 0x02)))
    }

    @Test fun buildPacketHasCorrectHeader() {
        val data = bytes(0xAA, 0xBB)
        val pkt = EspProtocol.buildPacket(EspProtocol.CMD_SYNC, data, checksum = 0)
        assertEquals(0x00, pkt[0].toInt() and 0xFF) // request direction
        assertEquals(EspProtocol.CMD_SYNC, pkt[1].toInt() and 0xFF)
        assertEquals(2, EspProtocol.readLe16(pkt, 2)) // size
        assertEquals(0L, EspProtocol.readLe32(pkt, 4)) // checksum
        assertArrayEquals(data, pkt.copyOfRange(8, pkt.size))
    }

    @Test fun parseResponseExtractsStatus() {
        // direction=1, cmd=0x02, size=4, value=0, data=[0,0, status=0, error=0]
        val frame = bytes(0x01, 0x02, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val resp = EspProtocol.parseResponse(frame, statusLen = 2)!!
        assertEquals(0x02, resp.command)
        assertTrue(resp.ok)
    }

    @Test fun parseResponseFlagsFailureStatus() {
        // last two data bytes = status 1, error 5
        val frame = bytes(0x01, 0x02, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x05)
        val resp = EspProtocol.parseResponse(frame, statusLen = 2)!!
        assertFalse(resp.ok)
        assertEquals(1, resp.status)
        assertEquals(5, resp.error)
    }

    @Test fun parseResponseRejectsRequestDirection() {
        val frame = bytes(0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertNull(EspProtocol.parseResponse(frame))
    }

    @Test fun numBlocksAndPadding() {
        assertEquals(1, EspProtocol.numBlocks(1, blockSize = 16))
        assertEquals(2, EspProtocol.numBlocks(17, blockSize = 16))
        val padded = EspProtocol.padBlock(bytes(0x01), blockSize = 4)
        assertArrayEquals(bytes(0x01, 0xFF, 0xFF, 0xFF), padded)
    }

    @Test fun flashMd5PayloadLayout() {
        val p = EspProtocol.flashMd5Payload(0x1000, 0x20)
        assertArrayEquals(
            bytes(0x00, 0x10, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00, 0, 0, 0, 0, 0, 0, 0, 0),
            p,
        )
    }

    @Test fun parseMd5RomReturnsAsciiHex() {
        // ROM loader: 32 ASCII hex chars followed by the 2 status bytes.
        val hex = "d41d8cd98f00b204e9800998ecf8427e"
        val data = hex.toByteArray(Charsets.US_ASCII) + bytes(0x00, 0x00)
        val resp = EspProtocol.Response(EspProtocol.CMD_SPI_FLASH_MD5, 0, data, 0, 0)
        assertEquals(hex, EspProtocol.parseMd5(resp))
    }

    @Test fun parseMd5StubReturnsRawBytesAsHex() {
        // Software stub: 16 raw digest bytes followed by the 2 status bytes.
        val raw = ByteArray(16) { it.toByte() }
        val data = raw + bytes(0x00, 0x00)
        val resp = EspProtocol.Response(EspProtocol.CMD_SPI_FLASH_MD5, 0, data, 0, 0)
        assertEquals("000102030405060708090a0b0c0d0e0f", EspProtocol.parseMd5(resp))
    }

    @Test fun parseMd5TooShortIsNull() {
        val resp = EspProtocol.Response(EspProtocol.CMD_SPI_FLASH_MD5, 0, bytes(0x00, 0x00), 0, 0)
        assertNull(EspProtocol.parseMd5(resp))
    }

    @Test fun versionComparisonStripsLeadingV() {
        assertTrue(AppUpdater.isNewer("0.3.0", "v0.4.0"))
        assertTrue(AppUpdater.isNewer("v0.3.0", "0.3.1"))
        assertFalse(AppUpdater.isNewer("0.3.0", "0.3.0"))
        assertFalse(AppUpdater.isNewer("0.4.0", "v0.3.9"))
        assertFalse(AppUpdater.isNewer("0.3.0", ""))
        assertTrue(AppUpdater.isNewer("0.3", "0.3.1"))
    }

    @Test fun firmwareMigrationHandlesLegacyVersionLineAndMissingAssets() {
        assertTrue(AppUpdater.firmwareUpdateNeeded("0.8.23", "v0.1", false, true))
        assertFalse(AppUpdater.firmwareUpdateNeeded("0.1", "v0.1", true, true))
        assertTrue(AppUpdater.firmwareUpdateNeeded("0.1", "v0.2", true, true))
        assertFalse(AppUpdater.firmwareUpdateNeeded("0.8.23", "v0.1", false, false))
        assertFalse(AppUpdater.firmwareUpdateNeeded("", "v0.1", false, true))
        assertTrue(AppUpdater.supportsInAppFirmware("LilyGo T-Embed CC1101"))
        assertFalse(AppUpdater.supportsInAppFirmware("M5Stack Cardputer"))
    }
}
