package app.brucia.wifi

import org.json.JSONObject
import java.util.zip.CRC32

/**
 * The capture artifact descriptor a `features.capture_descriptor` firmware attaches to the terminal
 * ACK of wifi_handshake / wifi_pmkid (see proto/PROTOCOL.md). It names the single file the device
 * actually wrote this run plus a size/CRC fingerprint, so the app imports exactly that path and
 * verifies it after transfer instead of inferring the new file from directory-listing diffs.
 *
 * The descriptor is device-supplied data, not a command: every field is validated here. The device
 * cannot know a wall-clock capture time, so [timeSource] is at most "device_uptime_ms" and the app
 * never presents the descriptor as a trusted timestamp; [uptimeMs] is only the device's monotonic
 * uptime. [crc32] is the same unsigned IEEE CRC-32 the fs_read transfer reports, which is what makes
 * [verify] meaningful: the fingerprint the device computed over the file it wrote must equal the one
 * the app computes over the bytes it pulled.
 */
data class CaptureDescriptor(
    val version: Int,
    val path: String,
    val size: Long,
    val crc32: Long,
    val frames: Long?,
    val handshake: Boolean?,
    val complete: Boolean,
    val stop: String,
    val timeSource: String,
    val uptimeMs: Long?,
    // The named file pre-existed and this run appended to it; size/crc32 then describe the whole
    // accumulated file (which the app reads and verifies in full), not just this run's bytes.
    val appended: Boolean,
    val priorSize: Long?,
) {
    /** The file's basename (segment after the last '/'). */
    val fileName: String get() = path.substringAfterLast('/')

    /**
     * Confirm the transferred bytes are the file the descriptor described: exact size and a matching
     * unsigned IEEE CRC-32. Returns null when they match, or a short reason when they do not (the
     * caller rejects the import on any non-null result).
     */
    fun verify(bytes: ByteArray): String? {
        if (bytes.size.toLong() != size) return "size mismatch (device said $size, got ${bytes.size})"
        val crc = CRC32().apply { update(bytes) }.value // unsigned, 0..0xFFFFFFFF
        if (crc != crc32) return "CRC mismatch (device said $crc32, got $crc)"
        return null
    }

    /** Human-readable reason a non-complete capture did not import, from the stop code. */
    fun partialReason(pmkid: Boolean): String {
        val what = if (pmkid) "PMKID" else "handshake"
        return when (stop) {
            "timeout" -> "No $what captured before the time ran out."
            "aborted" -> "Capture stopped on the device before a $what was captured."
            "no_capture" -> "No $what captured (no clients or no EAPOL frames seen)."
            "not_written" -> "Device detected a $what but wrote no new capture file this run (a stale file may exist); not importing it."
            else -> "No $what captured (device reported '$stop')."
        }
    }

    companion object {
        private const val MAX_SIZE = 16L * 1024 * 1024
        private const val U32_MAX = 0xFFFFFFFFL

        /**
         * Parse the descriptor from a capture ACK's data object. Returns null when the object carries
         * no descriptor or an unsupported/malformed one (unknown version, missing/invalid path, out-of-
         * range size/crc); callers treat null from a descriptor-advertising device as a hard failure
         * rather than silently importing an unverified file.
         */
        fun fromJson(d: JSONObject?): CaptureDescriptor? {
            val desc = d?.optJSONObject("desc") ?: return null
            if (desc.optInt("v", -1) != 1) return null
            val path = (desc.opt("path") as? String)?.trim().orEmpty()
            if (!pathLooksSafe(path)) return null
            val size = desc.optLong("size", -1L)
            if (size < 0L || size > MAX_SIZE) return null
            val crc = desc.optLong("crc32", -1L)
            if (crc < 0L || crc > U32_MAX) return null
            val stop = (desc.opt("stop") as? String)?.trim().orEmpty()
            if (stop.isEmpty()) return null
            val timeSource = (desc.opt("time_src") as? String)?.trim().orEmpty()
            val frames = if (desc.has("frames")) desc.optLong("frames", -1L).takeIf { it >= 0L } else null
            val handshake = if (desc.has("handshake")) desc.opt("handshake") as? Boolean else null
            val uptime = if (desc.has("uptime_ms")) desc.optLong("uptime_ms", -1L).takeIf { it >= 0L } else null
            val appended = desc.opt("appended") == true
            val priorSize = if (desc.has("prior_size")) desc.optLong("prior_size", -1L).takeIf { it in 0L..MAX_SIZE } else null
            return CaptureDescriptor(
                version = 1, path = path, size = size, crc32 = crc, frames = frames,
                handshake = handshake, complete = desc.opt("complete") == true,
                stop = stop, timeSource = timeSource, uptimeMs = uptime,
                appended = appended, priorSize = priorSize,
            )
        }

        /** Reject empty, relative, traversal and separator-bearing paths before trusting one. */
        private fun pathLooksSafe(path: String): Boolean {
            if (path.isEmpty() || path.length > 192 || !path.startsWith("/")) return false
            if (path.endsWith("/")) return false
            if (path.contains('\\') || path.contains('\u0000')) return false
            val segments = path.split('/')
            return segments.drop(1).all { it.isNotEmpty() && it != "." && it != ".." }
        }
    }
}
