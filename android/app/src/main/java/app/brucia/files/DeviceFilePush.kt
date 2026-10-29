package app.brucia.files

/**
 * Pure logic for pushing a file to the device with the firmware's write grammar:
 *   fs_write <path> <base64>         -> truncate + write (first chunk)
 *   fs_write_append <path> <base64>  -> append (subsequent chunks)
 *
 * Large files are streamed as several chunks so each command's base64 argument stays well under the
 * companion max frame (~8192 bytes). A raw chunk of [MAX_RAW_CHUNK] bytes base64-encodes to about
 * 4 KB of text, which with the JSON envelope + path is comfortably below that limit.
 *
 * This object is deliberately Android-free (it does not base64-encode here) so the chunking can be
 * unit-tested on the JVM; the ViewModel supplies the encoder (android.util.Base64).
 */
object DeviceFilePush {
    /** Raw bytes per chunk. 3072 * 4/3 = 4096 base64 chars. */
    const val MAX_RAW_CHUNK = 3072

    /**
     * Split [bytes] into raw chunks of at most [maxRawChunk] bytes. An empty input yields a single
     * empty chunk so an empty file is still created on the device (a truncating fs_write with no
     * data). [maxRawChunk] is coerced to at least 1.
     */
    fun chunk(bytes: ByteArray, maxRawChunk: Int = MAX_RAW_CHUNK): List<ByteArray> {
        val size = maxRawChunk.coerceAtLeast(1)
        if (bytes.isEmpty()) return listOf(ByteArray(0))
        val out = ArrayList<ByteArray>((bytes.size + size - 1) / size)
        var i = 0
        while (i < bytes.size) {
            val end = minOf(i + size, bytes.size)
            out.add(bytes.copyOfRange(i, end))
            i = end
        }
        return out
    }

    /**
     * The device command for chunk [index]: the first chunk truncates (fs_write), later chunks
     * append (fs_write_append). [b64] is the base64 of that chunk.
     */
    fun command(path: String, index: Int, b64: String): String =
        if (index == 0) "fs_write $path $b64" else "fs_write_append $path $b64"
}
