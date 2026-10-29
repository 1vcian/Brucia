package app.brucia.wardrive

import android.content.Context

/**
 * Backwards-compatible facade over [OuiLookup]. Kept so existing call sites keep compiling;
 * all logic and the single in-memory table now live in [OuiLookup].
 */
object OuiResolver {
    fun ensureLoaded(context: Context) = OuiLookup.ensureLoaded(context)

    fun vendor(bssid: String): String? = OuiLookup.vendor(bssid)

    val isLoaded: Boolean get() = OuiLookup.isLoaded
}
