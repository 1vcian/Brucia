package app.brucia.update

import java.security.MessageDigest

/** Preflight for the currently supported 16 MiB T-Embed CC1101 merged ESP32-S3 image. */
object FirmwareImage {
    fun validateTEmbed(image: ByteArray) {
        require(image.size <= 16 * 1024 * 1024) { "Firmware exceeds target flash size" }
        require(validateEspImage(image, 0) <= 0x8000) { "Bootloader overlaps partition table" }
        require(image.size > 0x10000 + 24) { "Merged firmware is truncated" }
        require(u8(image, 0x8000) == 0xAA && u8(image, 0x8001) == 0x50) { "Missing partition table" }
        var factory = false
        var factoryEnd = 0L
        var tableDigest = false
        val ranges = mutableListOf<Pair<Long, Long>>()
        for (offset in 0x8000 until 0x8C00 step 32) {
            if (u8(image, offset) == 0xEB && u8(image, offset + 1) == 0xEB) {
                val digest = MessageDigest.getInstance("MD5").digest(image.copyOfRange(0x8000, offset))
                require(MessageDigest.isEqual(digest, image.copyOfRange(offset + 16, offset + 32))) { "Partition table MD5 mismatch" }
                tableDigest = true
                break
            }
            if (u8(image, offset) != 0xAA || u8(image, offset + 1) != 0x50) break
            val start = le32(image, offset + 4)
            val size = le32(image, offset + 8)
            require(start >= 0x9000 && start % 4096 == 0L && size > 0 && size % 4096 == 0L &&
                start + size <= 16L * 1024 * 1024) { "Invalid partition bounds" }
            require(ranges.none { (from, to) -> start < to && start + size > from }) { "Overlapping partitions" }
            ranges.add(start to start + size)
            if (u8(image, offset + 2) == 0 && u8(image, offset + 3) == 0) {
                require(start == 0x10000L) { "Unsupported factory partition offset" }
                factory = true
                factoryEnd = start + size
            }
        }
        require(factory) { "No factory application partition" }
        require(tableDigest) { "Missing partition table digest" }
        require(validateEspImage(image, 0x10000) <= factoryEnd) { "Application exceeds factory partition" }
    }

    private fun validateEspImage(image: ByteArray, start: Int): Int {
        require(start + 24 <= image.size && u8(image, start) == 0xE9) { "Invalid ESP image header" }
        require(u8(image, start + 12) == 9 && u8(image, start + 13) == 0) { "Firmware is not for ESP32-S3" }
        val segments = u8(image, start + 1)
        require(segments in 1..16) { "Invalid ESP segment count" }
        var cursor = start + 24
        var checksum = 0xEF
        repeat(segments) {
            require(cursor + 8 <= image.size) { "Truncated segment header" }
            val length = le32(image, cursor + 4)
            cursor += 8
            require(length <= image.size.toLong() - cursor) { "Truncated segment data" }
            val end = cursor + length.toInt()
            while (cursor < end) checksum = checksum xor u8(image, cursor++)
        }
        val checksumAt = ((cursor - start + 16) and 15.inv()) - 1 + start
        require(checksumAt < image.size && u8(image, checksumAt) == checksum) { "ESP image checksum mismatch" }
        val appended = u8(image, start + 23)
        require(appended in 0..1) { "Invalid ESP digest flag" }
        if (appended == 1) {
            val end = checksumAt + 1
            require(end + 32 <= image.size) { "Truncated ESP digest" }
            val digest = MessageDigest.getInstance("SHA-256").digest(image.copyOfRange(start, end))
            require(MessageDigest.isEqual(digest, image.copyOfRange(end, end + 32))) { "ESP image SHA-256 mismatch" }
            return end + 32
        }
        return checksumAt + 1
    }

    private fun u8(bytes: ByteArray, offset: Int) = bytes[offset].toInt() and 0xFF
    private fun le32(bytes: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { value, i -> value or (u8(bytes, offset + i).toLong() shl (8 * i)) }
}
