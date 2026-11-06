package app.brucia.wordlist

import app.brucia.BrandLinks

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
)

object WordlistCatalog {
    val all: List<DownloadableWordlist> = listOf(
        DownloadableWordlist(
            name = "rockyou.txt",
            title = "rockyou.txt",
            url = "${BrandLinks.DOWNLOAD_PREFIX}wordlists/rockyou.txt",
            approxBytes = 139_921_507L,
            entries = "14.3M",
            description = "The classic real-world password dump - the standard WPA wordlist",
        ),
        DownloadableWordlist(
            name = "top-1M.txt",
            title = "SecLists top 1M",
            url = "${BrandLinks.DOWNLOAD_PREFIX}wordlists/top-1M.txt",
            approxBytes = 8_755_171L,
            entries = "1M",
            description = "One million most common passwords (fast, high hit rate)",
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
