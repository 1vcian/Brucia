package app.brucia.files

/** One entry from the device's `storage list` output. */
data class FileEntry(val name: String, val isDir: Boolean, val size: Long)

/** Parsed `storage list` reply; [truncated] when the device's reply limit may have cut it. */
data class FileListing(val entries: List<FileEntry>, val truncated: Boolean)

/**
 * Parse Bruce's `storage list <path>` output. Each entry is a line `name\t<DIR>` for a
 * directory or `name\t<size>` for a file. Lines without a tab (echoes, blanks) are skipped.
 */
object FileList {
    /**
     * The companion captures a routed CLI command's output into one ACK of at most this many bytes
     * (src/companion/commands.cpp CapturingSerialDevice::CAP) and silently drops the rest.
     */
    const val REPLY_LIMIT_BYTES = app.brucia.protocol.CliReplyLimit.BYTES

    fun parse(output: String): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        for (raw in output.split("\n")) {
            val line = raw.trimEnd('\r')
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val name = line.substring(0, tab)
            val rest = line.substring(tab + 1).trim()
            if (!safeName(name)) continue
            if (rest == "<DIR>") out.add(FileEntry(name, true, 0))
            else rest.toLongOrNull()?.let { out.add(FileEntry(name, false, it)) }
        }
        return out.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /**
     * [parse] for the file browser. Every complete entry line ends with a newline (println), so a
     * final segment without one is partial (cut by the reply limit, e.g. "name\t40" of
     * "name\t4096") and is dropped instead of being shown with a wrong size. [FileListing.truncated]
     * is set when the reply reaches the limit (3 bytes of slack: a multi-byte character cut by the
     * device decodes to one replacement character).
     */
    fun parseListing(output: String): FileListing {
        val (raw, marked) = app.brucia.protocol.CliReplyLimit.unmark(output)
        val complete = if (raw.endsWith("\n")) raw else raw.substring(0, raw.lastIndexOf('\n') + 1)
        val truncated = marked || raw.toByteArray(Charsets.UTF_8).size >= REPLY_LIMIT_BYTES - 3
        return FileListing(parse(complete), truncated)
    }

    /**
     * A name the device listing may contain: Bruce prints the part after the last '/', so a '/'
     * (or a control character) can only come from a corrupt or hostile reply. Such a name would
     * build a wrong device path and, used as a local file name ("Open with"), escape its folder.
     */
    private fun safeName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it < ' ' || it == '\u007f' }
}
