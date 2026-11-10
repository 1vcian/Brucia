package app.brucia.wordlist

import app.brucia.BrandLinks
import app.brucia.update.AssetIntegrity

/**
 * Real wordlists the app can download to phone storage on demand - rockyou and the
 * common SecLists password lists. These are far too large to bundle in the APK, but
 * the phone has plenty of storage, which is the whole point of offloading to it.
 * Downloaded lists land in [WordlistStore] and are then usable like any import.
 */
/** Live progress of a wordlist download, keyed by file name in the ViewModel. */
data class DownloadProgress(
    val name: String,
    val downloaded: Long,
    val total: Long,
    val running: Boolean,
    val error: String? = null,
)

data class DownloadableWordlist(
    val name: String,      // local file name once downloaded
    val title: String,
    val url: String,
    val approxBytes: Long, // for display + a sanity bar; the real size wins
    val entries: String,   // human-readable count
    val description: String,
    /** Expected size/SHA-256 of the Brucia-hosted asset; null = not pinned (F42 seam). */
    val integrity: AssetIntegrity? = null,
)

object WordlistCatalog {
    val all: List<DownloadableWordlist> = listOf(
        DownloadableWordlist(
            name = "probable-wpa-4800.txt",
            title = "Probable WPA top 4,800",
            url = "${BrandLinks.DOWNLOAD_PREFIX}wordlists/probable-wpa-4800.txt",
            approxBytes = 45_276L,
            entries = "4.8k",
            description = "berzerk0 / Probable-Wordlists (CC BY-SA 4.0). Popularity-ordered, 8-40 characters; general passwords filtered by length, not Wi-Fi captures.",
            integrity = AssetIntegrity(45_276L, "5dc33214cad9a11eb926de00f5ded20b1a5fc12ba3332319e054713265a35c51"),
        ),
        DownloadableWordlist(
            name = "probable-wpa-204k.txt",
            title = "Probable WPA top 204k",
            url = "${BrandLinks.DOWNLOAD_PREFIX}wordlists/probable-wpa-204k.txt",
            approxBytes = 2_121_750L,
            entries = "204k",
            description = "berzerk0 / Probable-Wordlists (CC BY-SA 4.0). Includes the smaller list; no need to run both. Not a guarantee of recovery.",
            integrity = AssetIntegrity(2_121_750L, "e416890a4742481971ba6d85039e0072111c92cb46c8cb7f3f8f22b865f48bf4"),
        ),
        DownloadableWordlist(
            name = "rockyou.txt",
            title = "rockyou.txt",
            url = "${BrandLinks.DOWNLOAD_PREFIX}wordlists/rockyou.txt",
            approxBytes = 139_921_507L,
            entries = "14.3M",
            description = "General password corpus; not Wi-Fi-specific. No guaranteed match.",
        ),
        DownloadableWordlist(
            name = "top-1M.txt",
            title = "SecLists top 1M",
            url = "${BrandLinks.DOWNLOAD_PREFIX}wordlists/top-1M.txt",
            approxBytes = 8_755_171L,
            entries = "1M",
            description = "Common-password corpus; usefulness depends on the tested password.",
        ),
        DownloadableWordlist(
            name = "top-100k.txt",
            title = "SecLists top 100k",
            url = "${BrandLinks.DOWNLOAD_PREFIX}wordlists/top-100k.txt",
            approxBytes = 828_498L,
            entries = "100k",
            description = "Quick pass of the 100k most common passwords",
        ),
        DownloadableWordlist(
            name = "top-10k.txt",
            title = "Top 10k",
            url = "${BrandLinks.DOWNLOAD_PREFIX}wordlists/top-10k.txt",
            approxBytes = 80_456L,
            entries = "10k",
            description = "The 10k most common passwords for a very fast first try",
        ),
    )
}
