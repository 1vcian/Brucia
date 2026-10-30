package app.brucia

import app.brucia.update.FirmwareImage
import java.security.MessageDigest
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
}
