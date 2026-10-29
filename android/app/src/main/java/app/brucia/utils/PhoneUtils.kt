package app.brucia.utils

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Self-contained, phone-only utility helpers that need no device connection. Kept as pure
 * functions (no Android context) so they can be unit-tested directly. The Utilities tool page
 * in MainActivity is a thin Compose wrapper over these.
 */
object PhoneUtils {

    // --- Password generator ---------------------------------------------------
    const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val DIGITS = "0123456789"
    const val SYMBOLS = "!@#\$%^&*()-_=+[]{};:,.?/"

    /** Build the character pool from the enabled sets. Pure. */
    fun charset(lower: Boolean, upper: Boolean, digits: Boolean, symbols: Boolean): String =
        buildString {
            if (lower) append(LOWER)
            if (upper) append(UPPER)
            if (digits) append(DIGITS)
            if (symbols) append(SYMBOLS)
        }

    /**
     * Generate a password of [length] characters drawn uniformly from [charset]. Returns "" when
     * [charset] is empty or [length] <= 0. A [rng] can be injected for deterministic tests; the
     * default is a cryptographically strong [SecureRandom].
     */
    fun generatePassword(length: Int, charset: String, rng: java.util.Random = SecureRandom()): String {
        if (length <= 0 || charset.isEmpty()) return ""
        val sb = StringBuilder(length)
        repeat(length) { sb.append(charset[rng.nextInt(charset.length)]) }
        return sb.toString()
    }

    // --- Hash calculator ------------------------------------------------------
    /** Lower-case hex digest of [text] (UTF-8) under [algorithm] ("MD5", "SHA-1", "SHA-256"). Pure. */
    fun hash(algorithm: String, text: String): String {
        val bytes = MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun md5(text: String): String = hash("MD5", text)
    fun sha1(text: String): String = hash("SHA-1", text)
    fun sha256(text: String): String = hash("SHA-256", text)

    // --- Base64 ---------------------------------------------------------------
    fun base64Encode(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    /** Decode [text] as base64 to a UTF-8 string. Throws [IllegalArgumentException] on bad input. */
    fun base64Decode(text: String): String =
        String(Base64.getDecoder().decode(text.trim()), Charsets.UTF_8)

    // --- Wi-Fi QR -------------------------------------------------------------
    /**
     * Build the standard "WIFI:" payload a camera app reads to join a network, escaping the
     * special characters (\ ; , : ") per the de-facto MeCard-style spec. [auth] is "WPA",
     * "WEP" or "nopass". Pure.
     */
    fun wifiQrPayload(ssid: String, password: String, auth: String, hidden: Boolean = false): String {
        val t = if (auth == "nopass") "nopass" else auth
        val sb = StringBuilder("WIFI:T:").append(t).append(";S:").append(escapeQr(ssid)).append(";")
        if (t != "nopass") sb.append("P:").append(escapeQr(password)).append(";")
        if (hidden) sb.append("H:true;")
        sb.append(";")
        return sb.toString()
    }

    private fun escapeQr(s: String): String = buildString {
        for (c in s) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') append('\\')
            append(c)
        }
    }
}
