package app.brucia

/**
 * Brucia's public links. The values come from the repo-root brand.json through BuildConfig
 * (android/app/build.gradle.kts), the same file the firmware header and the website are generated
 * from; every URL is derived here so a repository or site change is one edit (docs/LAUNCH.md).
 */
object BrandLinks {
    val NAME: String = BuildConfig.BRAND_NAME
    val GITHUB_OWNER: String = BuildConfig.BRAND_GITHUB_OWNER
    val GITHUB_REPO: String = BuildConfig.BRAND_GITHUB_REPO

    val REPO_URL: String = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
    /** Shown in text (no scheme), e.g. the About footer. */
    val REPO_DISPLAY: String = REPO_URL.removePrefix("https://")
    val RELEASES_URL: String = "$REPO_URL/releases"
    val DOWNLOAD_PREFIX: String = "$REPO_URL/releases/download/"
    /** GitHub API for the latest stable release; a renamed repository answers with a redirect. */
    val RELEASES_API: String = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /**
     * Release asset prefixes the updater accepts: the current repository plus any other names in
     * brand.json `accepted_release_repos` (used only across a repository rename).
     */
    val ACCEPTED_DOWNLOAD_PREFIXES: List<String> = (
        listOf(GITHUB_REPO) + BuildConfig.BRAND_ACCEPTED_RELEASE_REPOS.split(',').filter { it.isNotBlank() }
        ).distinct().map { "https://github.com/$GITHUB_OWNER/$it/releases/download/" }

    val SITE_URL: String = BuildConfig.BRAND_SITE_URL
    /** Site without scheme or trailing slash, e.g. a beacon SSID. */
    val SITE_DISPLAY: String = SITE_URL.removePrefix("https://").trimEnd('/')
    val WIKI_URL: String = "${SITE_URL}wiki/"
}
