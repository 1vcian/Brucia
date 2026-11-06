package app.brucia.utils

import java.io.File

/** Bounded local leftovers (exports, pulled copies, update downloads). Pure java.io, never throws. */
object FileRetention {
    /**
     * Delete all but the newest [keep] regular files directly in [dir] whose name starts with one
     * of [prefixes] (newest by modification time, then by name). Returns how many were deleted.
     */
    fun prune(dir: File, prefixes: List<String>, keep: Int): Int = try {
        val matching = dir.listFiles { f -> f.isFile && prefixes.any { f.name.startsWith(it) } }.orEmpty()
        matching.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .drop(keep.coerceAtLeast(0))
            .count { it.delete() }
    } catch (_: Exception) { 0 }
}
