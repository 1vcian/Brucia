package app.brucia

import app.brucia.update.EspProtocol
import app.brucia.update.SlipCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flasher's persistent SLIP receive buffer: frames that share a USB read, frames split over
 * many reads (including inside an escape), junk before a frame and a partial frame left at a
 * timeout. Wire bytes follow esptool: ROM replies end with 4 status bytes, stub replies with 2,
 * and the stub greets with the raw frame C0 4F 48 41 49 C0.
 */
class SlipReceiverTest {

    private fun hex(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    /** ROM reply to [cmd]: dir 01, size 4, value 0, status/error/reserved. */
    private fun rom(cmd: Int, status: Int = 0, error: Int = 0) =
        hex("C0 01 %02X 04 00 00 00 00 00 %02X %02X 00 00 C0".format(cmd, status, error))

    /** Stub reply to [cmd]: dir 01, size 2, value 0, status/error. */
    private fun stub(cmd: Int, status: Int = 0) = hex("C0 01 %02X 02 00 00 00 00 00 %02X 00 C0".format(cmd, status))

    private val ohai = hex("C0 4F 48 41 49 C0")

    /** Reply whose body holds both escape cases: value C0 DB 11 22 is sent as DB DC DB DD 11 22. */
    private val escaped = hex("C0 01 0A 04 00 DB DC DB DD 11 22 00 00 00 00 C0")
    private val escapedBody = hex("01 0A 04 00 C0 DB 11 22 00 00 00 00")

    private fun frames(rx: SlipCodec.Receiver): List<ByteArray> = generateSequence { rx.nextFrame() }.toList()

    private fun parse(frame: ByteArray, statusLen: Int) = EspProtocol.parseResponse(frame, statusLen)!!

    @Test fun twoRepliesInOneReadAreBothKept() {
        val rx = SlipCodec.Receiver()
        rx.feed(rom(EspProtocol.CMD_MEM_BEGIN) + rom(EspProtocol.CMD_MEM_DATA, status = 1, error = 5))
        val first = parse(rx.nextFrame()!!, 4)
        assertEquals(EspProtocol.CMD_MEM_BEGIN, first.command)
        assertTrue("second reply still buffered", rx.buffered > 0)
        val second = parse(rx.nextFrame()!!, 4)
        assertEquals(EspProtocol.CMD_MEM_DATA to (1 to 5), second.command to (second.status to second.error))
        assertNull(rx.nextFrame())
        assertEquals(0, rx.buffered)
    }

    @Test fun replyThenGreetingInOneReadKeepsTheGreeting() {
        val rx = SlipCodec.Receiver()
        rx.feed(rom(EspProtocol.CMD_MEM_END) + ohai)
        assertEquals(EspProtocol.CMD_MEM_END, parse(rx.nextFrame()!!, 4).command)
        assertTrue(EspProtocol.isStubGreeting(rx.nextFrame()!!))
        assertNull(rx.nextFrame())
    }

    @Test fun greetingThenNextReplyInOneReadKeepsTheReply() {
        val rx = SlipCodec.Receiver()
        rx.feed(ohai + stub(EspProtocol.CMD_SPI_ATTACH, status = 1))
        val greeting = rx.nextFrame()!!
        assertTrue(EspProtocol.isStubGreeting(greeting))
        assertNull("the greeting is not a response", EspProtocol.parseResponse(greeting, 2))
        // After OHAI the stub length applies: the status is the last two data bytes.
        val reply = parse(rx.nextFrame()!!, 2)
        assertEquals(EspProtocol.CMD_SPI_ATTACH to 1, reply.command to reply.status)
        assertNull(rx.nextFrame())
    }

    @Test fun frameFedOneByteAtATimeCompletesOnTheLastByte() {
        val stream = rom(EspProtocol.CMD_SYNC) + escaped + ohai
        val rx = SlipCodec.Receiver()
        val got = mutableListOf<ByteArray>()
        for (b in stream) {
            rx.feed(byteArrayOf(b))
            got += frames(rx)
        }
        assertEquals(3, got.size)
        assertEquals(EspProtocol.CMD_SYNC, parse(got[0], 4).command)
        assertArrayEquals(escapedBody, got[1])
        assertTrue(EspProtocol.isStubGreeting(got[2]))
    }

    @Test fun replySplitAtEverySplitPointDecodesTheSame() {
        // Split the escaped reply followed by a stub reply at every byte position, so the cut also
        // falls between DB and DC, between DB and DD, and between the two frames.
        val stream = escaped + stub(EspProtocol.CMD_FLASH_DATA)
        for (cut in 0..stream.size) {
            val rx = SlipCodec.Receiver()
            rx.feed(stream.copyOfRange(0, cut))
            val got = frames(rx).toMutableList()
            if (cut < escaped.size) assertTrue("cut $cut: no frame before its last byte", got.isEmpty())
            rx.feed(stream.copyOfRange(cut, stream.size))
            got += frames(rx)
            assertEquals("cut $cut", 2, got.size)
            assertArrayEquals("cut $cut", escapedBody, got[0])
            assertEquals("cut $cut", EspProtocol.CMD_FLASH_DATA, parse(got[1], 2).command)
        }
    }

    @Test fun escapeSplitAcrossReadsKeepsTheEscapedByte() {
        val rx = SlipCodec.Receiver()
        val esc = escaped.indexOf(0xDB.toByte())
        rx.feed(escaped.copyOfRange(0, esc + 1)) // ends on DB
        assertNull(rx.nextFrame())
        rx.feed(escaped.copyOfRange(esc + 1, esc + 3)) // DC then DB
        assertNull(rx.nextFrame())
        rx.feed(escaped.copyOfRange(esc + 3, escaped.size)) // DD ...
        assertArrayEquals(escapedBody, rx.nextFrame())
    }

    @Test fun junkBeforeAFrameIsSkipped() {
        val rx = SlipCodec.Receiver()
        val boot = "ESP-ROM:esp32s3-20210327\r\nwaiting for download\r\n".toByteArray(Charsets.US_ASCII)
        rx.feed(boot + rom(EspProtocol.CMD_SYNC))
        val all = frames(rx)
        assertEquals(1, all.size)
        assertEquals(EspProtocol.CMD_SYNC, parse(all[0], 4).command)
    }

    @Test fun partialFrameLeftAtATimeoutIsCompletedByTheNextRead() {
        val reply = rom(EspProtocol.CMD_READ_REG)
        val rx = SlipCodec.Receiver()
        rx.feed(reply.copyOfRange(0, 7))
        assertNull("a timeout here returns no frame", rx.nextFrame())
        rx.feed(reply.copyOfRange(7, reply.size) + rom(EspProtocol.CMD_SYNC))
        assertEquals(EspProtocol.CMD_READ_REG, parse(rx.nextFrame()!!, 4).command)
        assertEquals(EspProtocol.CMD_SYNC, parse(rx.nextFrame()!!, 4).command)
    }

    @Test fun abandonedJunkFrameDoesNotSwallowTheNextReply() {
        // A frame that never completes (junk after a stray C0) is closed by the next reply's
        // opening delimiter; it comes out as an unparseable frame and the reply is kept.
        val rx = SlipCodec.Receiver()
        rx.feed(hex("C0 55 55 55"))
        assertNull(rx.nextFrame())
        rx.feed(rom(EspProtocol.CMD_SYNC))
        val all = frames(rx)
        assertEquals(2, all.size)
        assertNull(EspProtocol.parseResponse(all[0], 4))
        assertEquals(EspProtocol.CMD_SYNC, parse(all[1], 4).command)
    }

    @Test fun clearDropsBufferedBytesAndPartialFrame() {
        val rx = SlipCodec.Receiver()
        rx.feed(rom(EspProtocol.CMD_SYNC) + rom(EspProtocol.CMD_SYNC).copyOfRange(0, 5))
        rx.clear()
        assertEquals(0, rx.buffered)
        assertNull(rx.nextFrame())
        rx.feed(hex("00 00 00 00 C0") + rom(EspProtocol.CMD_READ_REG)) // tail of the dropped frame, then a reply
        val all = frames(rx)
        assertEquals(listOf(EspProtocol.CMD_READ_REG), all.mapNotNull { EspProtocol.parseResponse(it, 4)?.command })
    }

    @Test fun feedHonoursTheReadCountAndGrowsPastTheInitialBuffer() {
        val rx = SlipCodec.Receiver()
        val buf = ByteArray(4096)
        val reply = rom(EspProtocol.CMD_FLASH_DATA)
        reply.copyInto(buf)
        rx.feed(buf, reply.size) // the rest of buf is stale and must not be read
        assertEquals(reply.size, rx.buffered)
        assertEquals(EspProtocol.CMD_FLASH_DATA, parse(rx.nextFrame()!!, 4).command)
        assertNull(rx.nextFrame())
        // Many replies in one large read, beyond the initial capacity.
        val many = (0 until 200).fold(ByteArray(0)) { acc, i -> acc + stub(i and 0x7F) }
        rx.feed(many)
        assertEquals((0 until 200).map { it and 0x7F }, frames(rx).map { parse(it, 2).command })
        assertFalse(rx.buffered > 0)
        // A kept frame ahead of a read that only fits once the buffer is compacted.
        rx.feed(rom(EspProtocol.CMD_SYNC) + rom(EspProtocol.CMD_READ_REG))
        assertEquals(EspProtocol.CMD_SYNC, parse(rx.nextFrame()!!, 4).command)
        rx.feed((0 until 41).fold(ByteArray(0)) { acc, _ -> acc + stub(EspProtocol.CMD_FLASH_DATA) })
        val rest = frames(rx).map { EspProtocol.parseResponse(it, 2)!!.command }
        assertEquals(listOf(EspProtocol.CMD_READ_REG) + List(41) { EspProtocol.CMD_FLASH_DATA }, rest)
    }

    @Test fun greetingIsRecognisedOnlyFromItsOwnBytes() {
        assertTrue(EspProtocol.isStubGreeting(hex("4F 48 41 49")))
        assertFalse(EspProtocol.isStubGreeting(hex("4F 48 41")))
        assertFalse(EspProtocol.isStubGreeting(hex("01 06 04 00 00 00 00 00 00 00 00 00")))
    }
}
