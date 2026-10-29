package app.brucia.wardrive

import android.content.Context
import java.io.BufferedReader
import java.util.zip.GZIPInputStream

/**
 * OSINT / OUI vendor enrichment. Resolves a MAC/BSSID to its hardware vendor using the
 * bundled IEEE OUI database (assets/oui.tsv.gz, ~40k entries, gzip-compressed to keep the
 * APK small). This is a clear offload win: the table is far too large to keep on the ESP,
 * so the phone does the lookup once and keeps it in memory.
 *
 * The parsing and lookup logic is kept as pure functions so it can be unit-tested without
 * an Android context; [vendor] resolves against the lazily-loaded table.
 */
object OuiLookup {
    @Volatile private var map: Map<String, String>? = null

    /** Load the OUI table once, off the main thread. Safe to call repeatedly / concurrently. */
    fun ensureLoaded(context: Context) {
        if (map != null) return
        synchronized(this) {
            if (map != null) return
            map = try {
                GZIPInputStream(context.assets.open("oui.tsv.gz")).bufferedReader().use { parse(it) }
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    val isLoaded: Boolean get() = map != null

    /**
     * Vendor for a MAC/BSSID like "AA:BB:CC:DD:EE:FF" (first three octets), or null when the
     * prefix is unknown or the table is not loaded yet. Pure except for the loaded table.
     */
    fun vendor(mac: String): String? {
        val p = prefix(mac) ?: return null
        return map?.get(p)
    }

    /**
     * Normalise a MAC/BSSID to its 6-hex-digit OUI prefix (first three octets, uppercase),
     * or null when fewer than three octets are present. Pure.
     */
    fun prefix(mac: String): String? {
        val hex = buildString {
            for (c in mac) {
                if (length == 6) break
                when (c) {
                    in '0'..'9', in 'a'..'f', in 'A'..'F' -> append(c.uppercaseChar())
                    ':', '-', '.', ' ' -> {}
                    else -> return null
                }
            }
        }
        return if (hex.length == 6) hex else null
    }

    /**
     * Parse an OUI table: tab-separated lines "AABBCC\tVendor Name". Pure; ignores malformed
     * lines. Visible for testing with a plain reader (the shipped asset is gzip-wrapped).
     */
    fun parse(reader: BufferedReader): Map<String, String> {
        val m = HashMap<String, String>(45_000)
        reader.useLines { lines ->
            lines.forEach { line ->
                val tab = line.indexOf('\t')
                if (tab == 6) m[line.substring(0, 6).uppercase()] = line.substring(tab + 1)
            }
        }
        return m
    }

    /** Test hook: install a table directly so [vendor] can be exercised without assets. */
    internal fun installForTest(entries: Map<String, String>) { map = entries }
}
