package app.brucia.files

/** One entry from the device's `storage list` output. */
data class FileEntry(val name: String, val isDir: Boolean, val size: Long)

/**
 * Parse Bruce's `storage list <path>` output. Each entry is a line `name\t<DIR>` for a
 * directory or `name\t<size>` for a file. Lines without a tab (echoes, blanks) are skipped.
 */
object FileList {
    fun parse(output: String): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        for (raw in output.split("\n")) {
            val line = raw.trimEnd('\r')
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val name = line.substring(0, tab)
            val rest = line.substring(tab + 1).trim()
            if (name.isBlank() || name == "." || name == "..") continue
            if (rest == "<DIR>") out.add(FileEntry(name, true, 0))
            else rest.toLongOrNull()?.let { out.add(FileEntry(name, false, it)) }
        }
        return out.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }
}
