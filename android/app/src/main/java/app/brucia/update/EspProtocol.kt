package app.brucia.update

/**
 * Pure (Android-free) building blocks of the ESP32 ROM serial bootloader protocol.
 *
 * References: Espressif "Serial protocol" (esp-serial-bootloader) and esptool.py.
 * Everything here is deterministic and side-effect free so it can be unit-tested
 * without hardware; the actual I/O and reset timing live in [EspFlasher].
 */
object EspProtocol {

    // --- Command opcodes (subset needed to write a merged image) -------------
    const val CMD_FLASH_BEGIN = 0x02
    const val CMD_FLASH_DATA = 0x03
    const val CMD_FLASH_END = 0x04
    const val CMD_SYNC = 0x08
    const val CMD_READ_REG = 0x0A
    const val CMD_SPI_SET_PARAMS = 0x0B
    const val CMD_SPI_ATTACH = 0x0D
    const val CMD_SPI_FLASH_MD5 = 0x13

    // RAM download (used to upload + run the esptool stub before flashing).
    const val CMD_MEM_BEGIN = 0x05
    const val CMD_MEM_END = 0x06
    const val CMD_MEM_DATA = 0x07
    const val RAM_BLOCK = 0x1800

    /** XOR seed for the data checksum carried in the packet header. */
    const val CHECKSUM_MAGIC = 0xEF

    /** Block size announced to the ROM in FLASH_BEGIN and used for each FLASH_DATA. */
    const val FLASH_WRITE_SIZE = 0x4000 // 16 KiB

    // --- Little-endian encoders ---------------------------------------------
    fun le16(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    fun le32(v: Int): ByteArray = le32(v.toLong() and 0xFFFFFFFFL)

    fun readLe16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    fun readLe32(b: ByteArray, off: Int): Long =
        ((b[off].toInt() and 0xFF).toLong()) or
            ((b[off + 1].toInt() and 0xFF).toLong() shl 8) or
            ((b[off + 2].toInt() and 0xFF).toLong() shl 16) or
            ((b[off + 3].toInt() and 0xFF).toLong() shl 24)

    /** Data checksum used for FLASH_DATA / MEM_DATA packets. */
    fun checksum(data: ByteArray): Int {
        var c = CHECKSUM_MAGIC
        for (b in data) c = c xor (b.toInt() and 0xFF)
        return c and 0xFF
    }

    /**
     * Build an un-framed request packet: direction(0x00), command, uint16 size,
     * uint32 checksum, then [data]. The caller SLIP-encodes the result before writing.
     */
    fun buildPacket(command: Int, data: ByteArray, checksum: Int = 0): ByteArray {
        val head = ByteArray(8)
        head[0] = 0x00 // request direction
        head[1] = command.toByte()
        le16(data.size).copyInto(head, 2)
        le32(checksum.toLong()).copyInto(head, 4)
        return head + data
    }

    // --- Command payload builders -------------------------------------------

    /** SYNC payload: 0x07 0x07 0x12 0x20 followed by 32 x 0x55. */
    fun syncPayload(): ByteArray = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }

    /**
     * SPI_ATTACH payload. [romLoader] appends the extra zero word the ROM loader
     * (no software stub) requires.
     */
    fun spiAttachPayload(arg: Int = 0, romLoader: Boolean = true): ByteArray =
        if (romLoader) le32(arg) + le32(0) else le32(arg)

    /** SPI_SET_PARAMS payload (fl_id, total_size, block, sector, page, status_mask). */
    fun spiSetParamsPayload(flashSize: Int): ByteArray =
        le32(0) + le32(flashSize) + le32(64 * 1024) + le32(4 * 1024) + le32(256) + le32(0xFFFF)

    /**
     * FLASH_BEGIN payload. For ESP32 and later ROM loaders a 5th word (the
     * encrypted flag) is appended; [romEsp32] controls that.
     */
    fun flashBeginPayload(
        eraseSize: Int,
        numBlocks: Int,
        blockSize: Int,
        offset: Int,
        romEsp32: Boolean = true,
    ): ByteArray {
        val base = le32(eraseSize) + le32(numBlocks) + le32(blockSize) + le32(offset)
        return if (romEsp32) base + le32(0) else base
    }

    /** FLASH_DATA header prefix (len, seq, 0, 0); the [block] bytes follow it. */
    fun flashDataHeader(blockLen: Int, seq: Int): ByteArray =
        le32(blockLen) + le32(seq) + le32(0) + le32(0)

    /** FLASH_END payload: 0 reboots the chip, 1 leaves it in the loader. */
    fun flashEndPayload(reboot: Boolean): ByteArray = le32(if (reboot) 0 else 1)

    /** SPI_FLASH_MD5 payload: address, size, then two reserved zero words. */
    fun flashMd5Payload(address: Int, size: Int): ByteArray =
        le32(address) + le32(size) + le32(0) + le32(0)

    /**
     * Extract the MD5 digest from a SPI_FLASH_MD5 response as a lowercase 32-char hex
     * string. The ROM loader returns the digest as 32 ASCII hex characters; the software
     * stub returns it as 16 raw bytes. The trailing [statusLen] status bytes are dropped
     * first. Returns null if the payload is too short to hold a digest.
     */
    fun parseMd5(resp: Response, statusLen: Int = 2): String? {
        val n = resp.data.size - statusLen
        if (n <= 0) return null
        val d = resp.data.copyOfRange(0, n)
        if (d.size >= 32) {
            val hex = String(d, 0, 32, Charsets.US_ASCII)
            if (hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return hex.lowercase()
        }
        if (d.size >= 16) {
            return d.copyOfRange(0, 16).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
        return null
    }

    /** Number of [FLASH_WRITE_SIZE] blocks needed for [totalBytes]. */
    fun numBlocks(totalBytes: Int, blockSize: Int = FLASH_WRITE_SIZE): Int =
        (totalBytes + blockSize - 1) / blockSize

    /** Pad [block] up to [blockSize] with 0xFF, matching esptool's behaviour. */
    fun padBlock(block: ByteArray, blockSize: Int = FLASH_WRITE_SIZE): ByteArray {
        if (block.size >= blockSize) return block
        val out = ByteArray(blockSize) { 0xFF.toByte() }
        block.copyInto(out)
        return out
    }

    /** Parsed response packet (direction 0x01). */
    data class Response(
        val command: Int,
        val value: Long,
        val data: ByteArray,
        val status: Int,
        val error: Int,
    ) {
        val ok: Boolean get() = status == 0
    }

    /**
     * Parse a de-SLIP'd response frame. [statusLen] is 2 for the ROM loader and 4
     * for the software stub; the status/error bytes are the tail of the data field.
     */
    fun parseResponse(frame: ByteArray, statusLen: Int = 2): Response? {
        if (frame.size < 8) return null
        if ((frame[0].toInt() and 0xFF) != 0x01) return null
        val command = frame[1].toInt() and 0xFF
        val size = readLe16(frame, 2)
        val value = readLe32(frame, 4)
        val end = minOf(frame.size, 8 + size)
        val data = frame.copyOfRange(8, end)
        var status = 0
        var error = 0
        if (data.size >= statusLen) {
            status = data[data.size - statusLen].toInt() and 0xFF
            error = data[data.size - statusLen + 1].toInt() and 0xFF
        }
        return Response(command, value, data, status, error)
    }
}

/**
 * SLIP framing codec (RFC 1055 flavour used by the ESP ROM):
 *  - 0xC0 delimits frames,
 *  - 0xDB is the escape byte,
 *  - a literal 0xC0 is sent as 0xDB 0xDC,
 *  - a literal 0xDB is sent as 0xDB 0xDD.
 */
object SlipCodec {

    private const val END = 0xC0
    private const val ESC = 0xDB
    private const val ESC_END = 0xDC
    private const val ESC_ESC = 0xDD

    /** Wrap [payload] in a complete SLIP frame (END .. escaped bytes .. END). */
    fun encode(payload: ByteArray): ByteArray {
        val out = ArrayList<Byte>(payload.size + 2)
        out.add(END.toByte())
        for (b in payload) {
            when (b.toInt() and 0xFF) {
                END -> { out.add(ESC.toByte()); out.add(ESC_END.toByte()) }
                ESC -> { out.add(ESC.toByte()); out.add(ESC_ESC.toByte()) }
                else -> out.add(b)
            }
        }
        out.add(END.toByte())
        return out.toByteArray()
    }

    /** Un-escape the body of a single frame (the bytes between the END delimiters). */
    fun decode(escaped: ByteArray): ByteArray {
        val out = ArrayList<Byte>(escaped.size)
        var i = 0
        while (i < escaped.size) {
            val v = escaped[i].toInt() and 0xFF
            if (v == ESC && i + 1 < escaped.size) {
                val n = escaped[i + 1].toInt() and 0xFF
                when (n) {
                    ESC_END -> out.add(END.toByte())
                    ESC_ESC -> out.add(ESC.toByte())
                    else -> out.add(n.toByte())
                }
                i += 2
            } else {
                out.add(v.toByte())
                i++
            }
        }
        return out.toByteArray()
    }

    /**
     * Incremental SLIP de-framer: feed raw bytes one at a time and collect complete
     * frame payloads (already un-escaped). Tolerates back-to-back delimiters.
     */
    class FrameReader {
        private val buf = ArrayList<Byte>()
        private var inFrame = false
        private var escaping = false

        fun reset() { buf.clear(); inFrame = false; escaping = false }

        /** Offer one byte; returns a completed frame payload, or null. */
        fun offer(byte: Int): ByteArray? {
            val v = byte and 0xFF
            if (!inFrame) {
                if (v == END) { inFrame = true; escaping = false; buf.clear() }
                return null
            }
            if (escaping) {
                when (v) {
                    ESC_END -> buf.add(END.toByte())
                    ESC_ESC -> buf.add(ESC.toByte())
                    else -> buf.add(v.toByte())
                }
                escaping = false
                return null
            }
            return when (v) {
                ESC -> { escaping = true; null }
                END -> {
                    // An END with an empty buffer is a redundant delimiter (back-to-back
                    // 0xC0); treat it as the start of the next frame rather than a frame.
                    if (buf.isEmpty()) { null } else { inFrame = false; buf.toByteArray().also { buf.clear() } }
                }
                else -> { buf.add(v.toByte()); null }
            }
        }
    }
}
