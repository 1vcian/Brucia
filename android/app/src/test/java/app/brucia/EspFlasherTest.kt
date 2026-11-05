package app.brucia

import app.brucia.update.EspFlasher
import app.brucia.update.EspProtocol
import app.brucia.update.SlipCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/**
 * Drives the whole [EspFlasher] flow against a scripted ESP32-S3 download loader (no hardware).
 * Wire facts follow esptool (loader.py check_command, targets/esp32s3.py): the ROM loader appends
 * four status bytes (status, error, two reserved zeros) to every response, the esptool stub two.
 * Every run uses doReset=false, so no reset sleeps are taken; replies are immediate.
 */
private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

class EspFlasherTest {

    private class Packet(val command: Int, val checksum: Long, val data: ByteArray)

    /** Fake download-mode device: decodes the app's SLIP requests and queues one reply frame per read. */
    private class FakeEsp(
        private val stubGreets: Boolean = true,
        private val failInRom: Set<Int> = emptySet(),
        private val failInStub: Set<Int> = emptySet(),
        private val md5Override: ByteArray? = null,
    ) : EspFlasher.SerialIo {
        val packets = mutableListOf<Packet>()
        val lines = mutableListOf<String>()
        val flash = ByteArray(64 * 1024) { 0xFF.toByte() }
        var stubRunning = false
            private set
        private var flashOffset = 0
        private val inbound = SlipCodec.FrameReader()
        private val outbound = ArrayDeque<ByteArray>()

        override fun write(bytes: ByteArray) {
            for (b in bytes) inbound.offer(b.toInt() and 0xFF)?.let { handle(it) }
        }

        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            val chunk = outbound.removeFirstOrNull() ?: return 0
            chunk.copyInto(buf)
            return chunk.size
        }

        override fun setDtr(value: Boolean) { lines += "dtr=$value" }
        override fun setRts(value: Boolean) { lines += "rts=$value" }
        override fun setBaudRate(baud: Int) { lines += "baud=$baud" }

        private fun handle(frame: ByteArray) {
            assertEquals("request direction", 0x00, frame[0].toInt() and 0xFF)
            val command = frame[1].toInt() and 0xFF
            val size = EspProtocol.readLe16(frame, 2)
            val data = frame.copyOfRange(8, frame.size)
            assertEquals("declared size of command 0x%02X".format(command), size, data.size)
            packets += Packet(command, EspProtocol.readLe32(frame, 4), data)
            val failing = if (stubRunning) failInStub else failInRom
            if (command in failing) { reply(command, status = 1, error = 5); return }
            when (command) {
                EspProtocol.CMD_FLASH_BEGIN -> flashOffset = EspProtocol.readLe32(data, 12).toInt()
                EspProtocol.CMD_FLASH_DATA -> {
                    val len = EspProtocol.readLe32(data, 0).toInt()
                    val seq = EspProtocol.readLe32(data, 4).toInt()
                    data.copyInto(flash, flashOffset + seq * len, 16, 16 + len)
                }
                EspProtocol.CMD_SPI_FLASH_MD5 -> {
                    val at = EspProtocol.readLe32(data, 0).toInt()
                    val n = EspProtocol.readLe32(data, 4).toInt()
                    val digest = md5Override ?: MessageDigest.getInstance("MD5").digest(flash.copyOfRange(at, at + n))
                    // The stub answers 16 raw bytes, the ROM 32 ASCII hex characters.
                    val body = if (stubRunning) digest else hex(digest).toByteArray(Charsets.US_ASCII)
                    reply(command, body = body); return
                }
            }
            reply(command)
            if (command == EspProtocol.CMD_MEM_END && stubGreets) {
                stubRunning = true
                outbound += SlipCodec.encode("OHAI".toByteArray(Charsets.US_ASCII))
            }
        }

        private fun reply(command: Int, body: ByteArray = ByteArray(0), status: Int = 0, error: Int = 0) {
            val tail = if (stubRunning) byteArrayOf(status.toByte(), error.toByte())
                else byteArrayOf(status.toByte(), error.toByte(), 0, 0)
            val data = body + tail
            val head = byteArrayOf(0x01, command.toByte()) + EspProtocol.le16(data.size) + EspProtocol.le32(0)
            outbound += SlipCodec.encode(head + data)
        }

        fun sent(command: Int) = packets.filter { it.command == command }
    }

    private fun image(n: Int) = ByteArray(n) { (it * 7 + 3).toByte() }

    @Test fun stubFlashWritesEveryBlockVerifiesAndRebootsIntoTheApp() {
        val esp = FakeEsp()
        val img = image(EspProtocol.FLASH_WRITE_SIZE + 100) // two blocks, the second one padded
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = EspFlasher(esp).flash(img, flashSize = 16 * 1024 * 1024, doReset = false) { w, t -> progress += w to t }

        assertEquals(EspFlasher.Verify.MATCH, result)
        assertArrayEquals(img, esp.flash.copyOf(img.size))
        // The tail of the last block is padded with 0xFF, as esptool does.
        assertTrue(esp.flash.copyOfRange(img.size, 2 * EspProtocol.FLASH_WRITE_SIZE).all { it == 0xFF.toByte() })
        assertEquals(listOf(0 to img.size, EspProtocol.FLASH_WRITE_SIZE to img.size, img.size to img.size), progress)

        // Stub running: SPI_ATTACH carries only the 4-byte argument (the ROM form fails on the stub).
        assertEquals(4, esp.sent(EspProtocol.CMD_SPI_ATTACH).single().data.size)
        // SPI_SET_PARAMS: fl_id, total size, block 64K, sector 4K, page 256, status mask.
        assertArrayEquals(
            EspProtocol.le32(0) + EspProtocol.le32(16 * 1024 * 1024) + EspProtocol.le32(0x10000) +
                EspProtocol.le32(0x1000) + EspProtocol.le32(0x100) + EspProtocol.le32(0xFFFF),
            esp.sent(EspProtocol.CMD_SPI_SET_PARAMS).single().data,
        )
        // FLASH_BEGIN: erase size, block count, block size, offset, encryption flag 0.
        assertArrayEquals(
            EspProtocol.le32(img.size) + EspProtocol.le32(2) + EspProtocol.le32(EspProtocol.FLASH_WRITE_SIZE) +
                EspProtocol.le32(0) + EspProtocol.le32(0),
            esp.sent(EspProtocol.CMD_FLASH_BEGIN).single().data,
        )
        // FLASH_DATA: header (len, seq, 0, 0), checksum over the padded block only, in sequence.
        val blocks = esp.sent(EspProtocol.CMD_FLASH_DATA)
        assertEquals(listOf(0L, 1L), blocks.map { EspProtocol.readLe32(it.data, 4) })
        for (b in blocks) {
            assertEquals(EspProtocol.FLASH_WRITE_SIZE.toLong(), EspProtocol.readLe32(b.data, 0))
            assertEquals(0L, EspProtocol.readLe32(b.data, 8) or EspProtocol.readLe32(b.data, 12))
            assertEquals(EspProtocol.checksum(b.data.copyOfRange(16, b.data.size)).toLong(), b.checksum)
        }
        // FLASH_END stays in the loader (1); MD5 asks for exactly the image bytes at offset 0.
        assertArrayEquals(EspProtocol.le32(1), esp.sent(EspProtocol.CMD_FLASH_END).single().data)
        assertArrayEquals(EspProtocol.flashMd5Payload(0, img.size), esp.sent(EspProtocol.CMD_SPI_FLASH_MD5).single().data)
        // SYNC frame body is the fixed esptool pattern.
        assertArrayEquals(byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }, esp.sent(EspProtocol.CMD_SYNC).first().data)
        // Hard reset into the application: GPIO0 released, EN pulsed low then high.
        assertEquals(listOf("dtr=false", "rts=true", "rts=false"), esp.lines.takeLast(3))
    }

    @Test fun stubUploadUsesRamBlocksWithChecksumsAndStartsTheEntryPoint() {
        val esp = FakeEsp()
        EspFlasher(esp).flash(image(64), doReset = false)

        val begins = esp.sent(EspProtocol.CMD_MEM_BEGIN)
        assertEquals(2, begins.size) // text then data segment
        val data = esp.sent(EspProtocol.CMD_MEM_DATA)
        val announced = begins.sumOf { EspProtocol.readLe32(it.data, 4) }
        assertEquals(announced, data.size.toLong())
        for (d in data) {
            val len = EspProtocol.readLe32(d.data, 0).toInt()
            assertTrue(len in 1..EspProtocol.RAM_BLOCK)
            assertEquals(len, d.data.size - 16)
            assertEquals(EspProtocol.checksum(d.data.copyOfRange(16, d.data.size)).toLong(), d.checksum)
        }
        // MEM_END: "execute" flag 0 plus the stub entry point.
        val end = esp.sent(EspProtocol.CMD_MEM_END).single().data
        assertEquals(0L, EspProtocol.readLe32(end, 0))
        assertEquals(app.brucia.update.EspStub.ENTRY, EspProtocol.readLe32(end, 4))
    }

    @Test fun romErrorStatusIsNotMaskedByTheReservedBytes() {
        // ROM loader rejects the stub upload and FLASH_BEGIN with status 1 / error 5, followed by its
        // two reserved zero bytes. The failure must stop the flash before any FLASH_DATA is sent.
        val esp = FakeEsp(failInRom = setOf(EspProtocol.CMD_MEM_BEGIN, EspProtocol.CMD_FLASH_BEGIN))
        try {
            EspFlasher(esp).flash(image(1024), doReset = false)
            fail("ROM FLASH_BEGIN failure was reported as success")
        } catch (e: EspFlasher.FlashException) {
            assertEquals("Command 0x02 failed (status=1 error=5)", e.message)
        }
        assertFalse(esp.stubRunning)
        assertTrue(esp.sent(EspProtocol.CMD_MEM_DATA).isEmpty())
        assertTrue(esp.sent(EspProtocol.CMD_FLASH_DATA).isEmpty())
        // ROM form of SPI_ATTACH (extra zero word) was used because the stub never started.
        assertEquals(8, esp.sent(EspProtocol.CMD_SPI_ATTACH).single().data.size)
        // A failed flash still reboots the device out of the loader.
        assertEquals(listOf("dtr=false", "rts=true", "rts=false"), esp.lines.takeLast(3))
    }

    @Test fun romFallbackFlashVerifiesTheAsciiMd5() {
        val esp = FakeEsp(failInRom = setOf(EspProtocol.CMD_MEM_BEGIN))
        val img = image(300)
        assertEquals(EspFlasher.Verify.MATCH, EspFlasher(esp).flash(img, doReset = false))
        assertArrayEquals(img, esp.flash.copyOf(img.size))
    }

    @Test fun stubErrorStatusAbortsTheWriteAndResets() {
        val esp = FakeEsp(failInStub = setOf(EspProtocol.CMD_FLASH_DATA))
        try {
            EspFlasher(esp).flash(image(EspProtocol.FLASH_WRITE_SIZE * 3), doReset = false)
            fail("stub FLASH_DATA failure was reported as success")
        } catch (e: EspFlasher.FlashException) {
            assertEquals("Command 0x03 failed (status=1 error=5)", e.message)
        }
        assertEquals("no block after the failed one", 1, esp.sent(EspProtocol.CMD_FLASH_DATA).size)
        assertTrue(esp.sent(EspProtocol.CMD_FLASH_END).isEmpty())
        assertEquals(listOf("dtr=false", "rts=true", "rts=false"), esp.lines.takeLast(3))
    }

    @Test fun md5MismatchIsReportedAndMd5ErrorIsOnlyUnverified() {
        val mismatch = FakeEsp(md5Override = ByteArray(16) { 0x11 })
        assertEquals(EspFlasher.Verify.MISMATCH, EspFlasher(mismatch).flash(image(100), doReset = false))

        val refused = FakeEsp(failInStub = setOf(EspProtocol.CMD_SPI_FLASH_MD5))
        val img = image(100)
        assertEquals(EspFlasher.Verify.UNVERIFIED, EspFlasher(refused).flash(img, doReset = false))
        assertArrayEquals("the write itself completed", img, refused.flash.copyOf(img.size))
    }

    /**
     * Byte-exact replay of a successful ESP32-S3 session as the device sends it (esptool protocol):
     * 8 ROM SYNC replies with a non-zero value, ROM replies with 4 status bytes (00 00 00 00), the
     * stub's raw "OHAI" frame in a later USB read, then stub replies with 2 status bytes and the
     * stub MD5 as 16 raw bytes. Every reply is also accepted by the old fixed 2-byte reading, so this
     * pins that the ROM length change keeps the success path the hardware flash (0.1.5) took.
     */
    @Test fun byteExactS3RomThenStubSessionSucceedsAsBefore() {
        fun hex(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
        fun slip(body: ByteArray): ByteArray { // independent RFC 1055 escaper for the variable MD5 bytes
            val out = mutableListOf<Byte>(0xC0.toByte())
            for (b in body) when (b.toInt() and 0xFF) {
                0xC0 -> { out += 0xDB.toByte(); out += 0xDC.toByte() }
                0xDB -> { out += 0xDB.toByte(); out += 0xDD.toByte() }
                else -> out += b
            }
            out += 0xC0.toByte()
            return out.toByteArray()
        }
        // dir=01, cmd, size=0004, value, status 00 error 00 reserved 00 00
        fun rom(cmd: String, value: String = "00 00 00 00") = hex("C0 01 $cmd 04 00 $value 00 00 00 00 C0")
        // dir=01, cmd, size=0002, value 0, status 00 error 00
        fun stub(cmd: String) = hex("C0 01 $cmd 02 00 00 00 00 00 00 00 C0")
        val img = image(EspProtocol.FLASH_WRITE_SIZE + 77)
        val digest = MessageDigest.getInstance("MD5").digest(img)

        val requests = mutableListOf<Int>()
        val replies = mutableListOf<ByteArray>()
        val io = object : EspFlasher.SerialIo {
            private val inbound = SlipCodec.FrameReader()
            private val out = ArrayDeque<ByteArray>()
            private var stubUp = false
            override fun write(bytes: ByteArray) {
                for (b in bytes) {
                    val frame = inbound.offer(b.toInt() and 0xFF) ?: continue
                    val cmd = frame[1].toInt() and 0xFF
                    requests += cmd
                    val cmdHex = "%02X".format(cmd)
                    val queued = when {
                        cmd == EspProtocol.CMD_SYNC -> List(8) { rom("08", "07 07 12 20") }
                        cmd == EspProtocol.CMD_READ_REG -> listOf(rom("0A", "09 00 00 00"))
                        cmd == EspProtocol.CMD_MEM_END -> listOf(rom("06"), hex("C0 4F 48 41 49 C0")).also { stubUp = true }
                        cmd == EspProtocol.CMD_SPI_FLASH_MD5 ->
                            listOf(slip(hex("01 13 12 00 00 00 00 00") + digest + hex("00 00")))
                        stubUp -> listOf(stub(cmdHex))
                        else -> listOf(rom(cmdHex))
                    }
                    replies += queued
                    out += queued
                }
            }
            override fun read(buf: ByteArray, timeoutMs: Int): Int {
                val chunk = out.removeFirstOrNull() ?: return 0
                chunk.copyInto(buf); return chunk.size
            }
            override fun setDtr(value: Boolean) {}
            override fun setRts(value: Boolean) {}
            override fun setBaudRate(baud: Int) {}
        }

        assertEquals(EspFlasher.Verify.MATCH, EspFlasher(io).flash(img, flashSize = 16 * 1024 * 1024, doReset = false))
        val c = EspProtocol
        // ROM phase: SYNC, READ_REG, then the stub's text and data segments (runs collapsed).
        val romPhase = requests.takeWhile { it != c.CMD_MEM_END }
            .fold(mutableListOf<Int>()) { acc, x -> if (acc.lastOrNull() != x) acc += x; acc }
        assertEquals(listOf(c.CMD_SYNC, c.CMD_READ_REG, c.CMD_MEM_BEGIN, c.CMD_MEM_DATA, c.CMD_MEM_BEGIN, c.CMD_MEM_DATA), romPhase)
        assertEquals(
            listOf(c.CMD_MEM_END, c.CMD_SPI_ATTACH, c.CMD_SPI_SET_PARAMS, c.CMD_FLASH_BEGIN,
                c.CMD_FLASH_DATA, c.CMD_FLASH_DATA, c.CMD_FLASH_END, c.CMD_SPI_FLASH_MD5),
            requests.dropWhile { it != c.CMD_MEM_END },
        )
        // The old fixed 2-byte reading accepted every one of these replies too.
        val reader = SlipCodec.FrameReader()
        for (r in replies) for (b in r) reader.offer(b.toInt() and 0xFF)?.let { frame ->
            EspProtocol.parseResponse(frame, statusLen = 2)?.let { assertTrue("old reading of 0x%02X".format(it.command), it.ok) }
        }
    }

    @Test fun emptyImageIsRejectedBeforeTouchingTheLink() {
        val esp = FakeEsp()
        try { EspFlasher(esp).flash(ByteArray(0), doReset = false); fail("empty image accepted") }
        catch (e: EspFlasher.FlashException) { assertEquals("Empty firmware image", e.message) }
        assertTrue(esp.packets.isEmpty())
        assertTrue(esp.lines.isEmpty())
    }
}
