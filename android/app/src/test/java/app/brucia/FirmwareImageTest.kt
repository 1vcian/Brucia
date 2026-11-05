package app.brucia

import app.brucia.update.FirmwareImage
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class FirmwareImageTest {
    private fun esp(): ByteArray {
        val bytes = ByteArray(48)
        bytes[0] = 0xE9.toByte(); bytes[1] = 1; bytes[12] = 9; bytes[23] = 1
        bytes[28] = 3 // segment length, little-endian
        bytes[32] = 1; bytes[33] = 2; bytes[34] = 3
        bytes[47] = 0xEF.toByte() // EF xor 1 xor 2 xor 3
        return bytes + MessageDigest.getInstance("SHA-256").digest(bytes)
    }
    private fun merged(): ByteArray {
        val image = ByteArray(0x10000 + 80)
        esp().copyInto(image)
        image[0x8000] = 0xAA.toByte(); image[0x8001] = 0x50
        image[0x8006] = 1 // factory offset = 0x10000
        image[0x800A] = 0x40 // factory size = 0x400000
        image[0x8020] = 0xEB.toByte(); image[0x8021] = 0xEB.toByte()
        MessageDigest.getInstance("MD5").digest(image.copyOfRange(0x8000, 0x8020)).copyInto(image, 0x8030)
        esp().copyInto(image, 0x10000)
        return image
    }
    @Test fun acceptsMergedS3ImageWithChecksumsAndDigests() { FirmwareImage.validateTEmbed(merged()) }

    @Test fun rejectsWrongChipCorruptionBadPartitionsAndTruncation() {
        val original = merged()
        val invalid = listOf(
            ByteArray(0), "<html>not firmware</html>".toByteArray(), original.copyOf(0x10000),
            original.copyOf().apply { this[12] = 0 },
            original.copyOf().apply { this[0x10000 + 12] = 0 },
            original.copyOf().apply { this[0x10000 + 32] = 7 },
            original.copyOf().apply { this[0x10000 + 4] = 7 }, // header changes only: SHA must catch it
            original.copyOf().apply { this[0x8000] = 0 },
            original.copyOf().apply { this[0x800B] = 0x7F },
            original.copyOf().apply { this[0x800C] = 1 }, // partition label changed: table MD5 must catch it
            original.copyOf().apply { this[0x10000 + 31] = 0x7F }, // huge segment length
        )
        for (image in invalid) {
            try { FirmwareImage.validateTEmbed(image); fail("Accepted invalid merged image") }
            catch (_: IllegalArgumentException) { }
        }
    }

    /** ESP32-S3 app image with one segment of [len] bytes, a valid checksum and optional SHA-256. */
    private fun espWithSegment(len: Int, digest: Boolean = true): ByteArray {
        val body = ByteArray(24 + 8 + len)
        body[0] = 0xE9.toByte(); body[1] = 1; body[12] = 9; body[23] = if (digest) 1 else 0
        for (i in 0..3) body[28 + i] = (len ushr (8 * i)).toByte()
        var checksum = 0xEF
        for (i in 0 until len) { body[32 + i] = (i * 13 + 1).toByte(); checksum = checksum xor (body[32 + i].toInt() and 0xFF) }
        val checksumAt = ((body.size + 16) and 15.inv()) - 1
        val image = body.copyOf(checksumAt + 1).also { it[checksumAt] = checksum.toByte() }
        return if (digest) image + MessageDigest.getInstance("SHA-256").digest(image) else image
    }

    private fun rejection(image: ByteArray): String? =
        try { FirmwareImage.validateTEmbed(image); null } catch (e: IllegalArgumentException) { e.message }

    private fun le32(image: ByteArray, at: Int, v: Long) { for (i in 0..3) image[at + i] = (v ushr (8 * i)).toByte() }

    /** Rewrite partition entry [index] and refresh the table MD5 so only the entry itself is wrong. */
    private fun withEntry(index: Int, type: Int, subtype: Int, start: Long, size: Long, entries: Int = 1): ByteArray {
        val image = merged()
        val at = 0x8000 + index * 32
        image[at] = 0xAA.toByte(); image[at + 1] = 0x50; image[at + 2] = type.toByte(); image[at + 3] = subtype.toByte()
        le32(image, at + 4, start); le32(image, at + 8, size)
        val md5At = 0x8000 + entries * 32
        for (i in 0 until 32) image[md5At + i] = 0
        image[md5At] = 0xEB.toByte(); image[md5At + 1] = 0xEB.toByte()
        MessageDigest.getInstance("MD5").digest(image.copyOfRange(0x8000, md5At)).copyInto(image, md5At + 16)
        return image
    }

    /** Each preflight rule rejects with its own reason, so a regression cannot hide behind another rule. */
    @Test fun eachRejectionNamesItsRule() {
        val ok = merged()
        assertEquals("Firmware exceeds target flash size", rejection(ByteArray(16 * 1024 * 1024 + 1)))
        assertEquals("Invalid ESP image header", rejection(ByteArray(0)))
        assertEquals("Firmware is not for ESP32-S3", rejection(ok.copyOf().apply { this[12] = 2 })) // ESP32-C3 chip id
        assertEquals("Invalid ESP segment count", rejection(ok.copyOf().apply { this[1] = 0 }))
        assertEquals("Invalid ESP segment count", rejection(ok.copyOf().apply { this[1] = 17 }))
        assertEquals("Truncated segment header", rejection(ok.copyOf(0x10000 + 28)))
        assertEquals("Truncated segment data", rejection(ok.copyOf().apply { this[0x10000 + 31] = 0x7F }))
        assertEquals("Invalid ESP digest flag", rejection(ok.copyOf().apply { this[23] = 2 }))
        assertEquals("ESP image checksum mismatch", rejection(ok.copyOf().apply { this[47] = 0 }))
        assertEquals("ESP image SHA-256 mismatch", rejection(ok.copyOf().apply { this[33] = 9; this[47] = (0xEF xor 1 xor 9 xor 3).toByte() }))
        assertEquals("Merged firmware is truncated", rejection(ok.copyOf(0x10000 + 24)))
        assertEquals("Missing partition table", rejection(ok.copyOf().apply { this[0x8001] = 0 }))
        assertEquals("Partition table MD5 mismatch", rejection(ok.copyOf().apply { this[0x8031] = (this[0x8031].toInt() xor 1).toByte() }))
        assertEquals("Missing partition table digest", rejection(ok.copyOf().apply { this[0x8020] = 0 }))
        assertEquals("Invalid application ESP image", "Invalid ESP image header", rejection(ok.copyOf().apply { this[0x10000] = 0 }))
        // A 4 KiB factory partition cannot hold a 5 KiB application image.
        val small = withEntry(0, 0, 0, 0x10000, 0x1000)
        val bigApp = espWithSegment(5000)
        assertEquals("Application exceeds factory partition",
            rejection(small.copyOf(0x10000 + bigApp.size).also { bigApp.copyInto(it, 0x10000) }))
        assertEquals("Truncated ESP digest",
            rejection(ok.copyOf(0x10000 + 60).also { espWithSegment(3).copyOf(60).copyInto(it, 0x10000) }))
        // Digest flag 0: no SHA-256 appended, the checksum alone closes the image.
        val noDigest = espWithSegment(3, digest = false)
        assertEquals(null, rejection(ok.copyOf(0x10000 + noDigest.size + 16).also { noDigest.copyInto(it, 0x10000) }))
    }

    @Test fun partitionTableBoundsAndLayoutRules() {
        assertEquals("Invalid partition bounds", rejection(withEntry(0, 0, 0, 0x8000, 0x1000))) // below 0x9000
        assertEquals("Invalid partition bounds", rejection(withEntry(0, 0, 0, 0x10800, 0x1000))) // unaligned start
        assertEquals("Invalid partition bounds", rejection(withEntry(0, 0, 0, 0x10000, 0x800))) // unaligned size
        assertEquals("Invalid partition bounds", rejection(withEntry(0, 0, 0, 0x10000, 0))) // empty
        assertEquals("Invalid partition bounds", rejection(withEntry(0, 0, 0, 0x10000, 0x1000000))) // past 16 MiB
        assertEquals("Unsupported factory partition offset", rejection(withEntry(0, 0, 0, 0x20000, 0x10000)))
        assertEquals("No factory application partition", rejection(withEntry(0, 1, 2, 0x9000, 0x5000))) // NVS only
        // Second entry overlapping the factory app.
        val overlap = withEntry(1, 1, 2, 0x3F0000, 0x20000, entries = 2)
        assertEquals("Overlapping partitions", rejection(overlap))
        // A second, disjoint entry is accepted.
        assertEquals(null, rejection(withEntry(1, 1, 2, 0x9000, 0x5000, entries = 2)))
    }
}
