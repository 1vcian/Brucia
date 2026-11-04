package app.brucia.files

/**
 * Path/chunk bounds and legacy write helpers. Managed transfers use SessionFileLink's
 * transactional protocol; command() retains the legacy firmware grammar:
 *   fs_write <path> <base64>         -> truncate + write (first chunk)
 *   fs_write_append <path> <base64>  -> append (subsequent chunks)
 *
 * Large files are streamed as several chunks so each command's base64 argument stays well under the
 * companion max frame (~8192 bytes). A raw chunk of [MAX_RAW_CHUNK] bytes base64-encodes to about
 * 4 KB of text, which with the JSON envelope + path is comfortably below that limit.
 *
 * This object is Android-free so path/chunk rules can be tested on the JVM.
 */
object DeviceFilePush {
    /** Raw bytes per chunk. 3072 * 4/3 = 4096 base64 chars. */
    const val MAX_RAW_CHUNK = 3072

    /** Canonical destination for the transactional protocol, shared with firmware path rules. */
    fun uploadPath(path: String): String {
        require(pathError(path) == null) { pathError(path).orEmpty() }
        val absolute = if (path.startsWith('/')) path else "/$path"
        require(absolute.toByteArray(Charsets.UTF_8).size <= 192 &&
            !absolute.lowercase(java.util.Locale.ROOT).startsWith("/.brucia-upload.") &&
            absolute.none { it.code < 32 || it.code == 127 || it in "\\:*?\"<>|" } &&
            absolute.substring(1).split('/').all {
                it.isNotEmpty() && it != "." && it != ".." && !it.startsWith(' ') &&
                    !it.endsWith(' ') && !it.endsWith('.')
            }) { "Unsupported upload path or reserved transaction filename" }
        return absolute
    }

    /**
     * Split [bytes] into raw chunks of at most [maxRawChunk] bytes. An empty input yields a single
     * empty chunk so an empty file is still created on the device (a truncating fs_write with no
     * data). [maxRawChunk] is coerced to at least 1.
     */
    fun chunk(bytes: ByteArray, maxRawChunk: Int = MAX_RAW_CHUNK): List<ByteArray> {
        val size = maxRawChunk.coerceAtLeast(1)
        if (bytes.isEmpty()) return listOf(ByteArray(0))
        val out = ArrayList<ByteArray>((bytes.size - 1) / size + 1)
        var i = 0
        while (i < bytes.size) {
            val end = i + minOf(size, bytes.size - i)
            out.add(bytes.copyOfRange(i, end))
            i = end
        }
        return out
    }

    /**
     * The device command for chunk [index]: the first chunk truncates (fs_write), later chunks
     * append (fs_write_append). [b64] is the base64 of that chunk.
     */
    fun pathError(path: String): String? =
        if (path.isBlank() || path != path.trim() || path.any { it == '\u0000' || it == '\r' || it == '\n' })
            "File path is empty or contains unsupported control/edge whitespace" else null

    fun command(path: String, index: Int, b64: String): String {
        require(pathError(path) == null) { pathError(path).orEmpty() }
        require(index >= 0) { "Negative chunk index" }
        return if (index == 0) "fs_write $path $b64" else "fs_write_append $path $b64"
    }
}
