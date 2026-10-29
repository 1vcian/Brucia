package app.brucia.wordlist

import android.content.Context

/** A wordlist shipped inside the app (an `assets/` file), offered by default. */
data class BundledWordlist(val asset: String, val title: String, val description: String)

/** A bundled wordlist with its line count resolved, for display in the UI. */
data class BundledWordlistUi(
    val asset: String,
    val title: String,
    val description: String,
    val lines: Int,
)

/**
 * The dictionaries Brucia ships out of the box, so a crack works with no import.
 * The phone can still import rockyou-scale lists on top of these (see [WordlistStore]).
 */
object BundledWordlists {
    // A single tiny offline list for a no-network sanity check. Real cracking uses the
    // downloadable wordlists (rockyou, SecLists) in [WordlistCatalog].
    val all: List<BundledWordlist> = listOf(
        BundledWordlist("common_wpa.txt", "Common WPA (offline quick list)", "Small built-in list for a fast offline check"),
    )

    /** Count the non-blank lines of a bundled asset (0 if it is missing). */
    fun lines(ctx: Context, asset: String): Int = try {
        ctx.assets.open(asset).bufferedReader().useLines { seq -> seq.count { it.isNotBlank() } }
    } catch (_: Exception) {
        0
    }
}
