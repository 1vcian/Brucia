package app.brucia.update

import java.io.IOException
import java.security.MessageDigest

/** A downloaded release asset did not match its published size or SHA-256 digest. */
class IntegrityException(message: String) : IOException(message)

/**
 * Size and SHA-256 published by GitHub for one release asset. Rules shared with
 * docs/release-catalog.js:
 * - `size` / `digest` absent or JSON null: allowed (releases uploaded before GitHub
 *   published digests); the download is reported as unverified.
 * - present but malformed (size not an integer in 1..limit; digest not exactly
 *   `sha256:` + 64 lowercase hex): the asset is rejected, never downgraded to unverified.
 * - mismatch after download: hard failure before any install or flash.
 */
data class AssetIntegrity(val size: Long?, val sha256: String?) {
    val verified: Boolean get() = sha256 != null

    /** Short status for logs/UI. */
    val label: String get() = if (verified) "SHA-256 verified" else "unverified (release lists no SHA-256 digest)"

    /** Throws [IntegrityException] when [count] bytes with [digest] do not match this record. */
    fun check(count: Long, digest: ByteArray) {
        if (size != null && count != size)
            throw IntegrityException("Size mismatch: downloaded $count bytes, release lists $size")
        if (sha256 != null) {
            val actual = hex(digest)
            if (actual != sha256) throw IntegrityException("SHA-256 mismatch: release lists $sha256, downloaded $actual")
        }
    }

    fun check(bytes: ByteArray) = check(bytes.size.toLong(), sha256Of(bytes))

    companion object {
        val NONE = AssetIntegrity(null, null)
        private val DIGEST = Regex("sha256:[0-9a-f]{64}")

        /** Returns null when a present value is malformed (asset must be rejected). */
        fun parse(size: Any?, digest: Any?, limit: Long): AssetIntegrity? {
            val parsedSize = when (size) {
                null -> null
                is Number -> {
                    val d = size.toDouble()
                    if (!d.isFinite() || d != Math.floor(d) || d < 1 || d > limit) return null
                    d.toLong()
                }
                else -> return null
            }
            val parsedDigest = when (digest) {
                null -> null
                is String -> if (DIGEST.matches(digest)) digest.removePrefix("sha256:") else return null
                else -> return null
            }
            return AssetIntegrity(parsedSize, parsedDigest)
        }

        fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")
        fun sha256Of(bytes: ByteArray): ByteArray = sha256().digest(bytes)
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
