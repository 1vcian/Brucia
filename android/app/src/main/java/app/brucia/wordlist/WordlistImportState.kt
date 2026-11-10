package app.brucia.wordlist

/** Outcome of the latest manual wordlist import, shown on the Crack screen. */
data class WordlistImportState(
    val name: String,
    val running: Boolean,
    val message: String? = null,
    val failed: Boolean = false,
)
