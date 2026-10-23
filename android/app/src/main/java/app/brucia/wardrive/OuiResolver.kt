package app.brucia.wardrive

import android.content.Context

/**
 * Resolves a MAC/BSSID to its vendor using the bundled IEEE OUI database
 * (assets/oui.tsv, ~40k entries). This is a clear offload win: the dataset is far
 * too large to keep on the ESP, so the phone does the lookup.
 */
object OuiResolver {
    @Volatile private var map: Map<String, String>? = null

    /** Load the OUI table once, off the main thread. Safe to call repeatedly. */
    fun ensureLoaded(context: Context) {
        if (map != null) return
        synchronized(this) {
            if (map != null) return
            val m = HashMap<String, String>(45_000)
            try {
                context.assets.open("oui.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab == 6) m[line.substring(0, 6)] = line.substring(tab + 1)
                    }
                }
            } catch (_: Exception) {
            }
            map = m
        }
    }

    /** Vendor for a BSSID like "AA:BB:CC:...", or null if unknown / not loaded. */
    fun vendor(bssid: String): String? {
        val m = map ?: return null
        val hex = bssid.replace(":", "").uppercase()
        if (hex.length < 6) return null
        return m[hex.substring(0, 6)]
    }

    val isLoaded: Boolean get() = map != null
}
