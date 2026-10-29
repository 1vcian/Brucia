package app.brucia.subghz

/**
 * De Bruijn B(2,n) sequence generator: the shortest bit string that contains every
 * n-bit code exactly once (cyclically). Used to brute-force fixed-code OOK remotes
 * in minimal time. Computed on the phone; the device transmits it.
 */
object DeBruijn {
    /** Lexicographically-smallest de Bruijn sequence over {0,1} of order [n] (length 2^n). */
    fun sequence(n: Int): String {
        require(n in 1..22) { "n out of range" }
        val k = 2
        val a = IntArray(k * n)
        val sb = StringBuilder()
        fun db(t: Int, p: Int) {
            if (t > n) {
                if (n % p == 0) for (j in 1..p) sb.append(a[j])
            } else {
                a[t] = a[t - p]
                db(t + 1, p)
                for (j in a[t - p] + 1 until k) {
                    a[t] = j
                    db(t + 1, t)
                }
            }
        }
        db(1, 1)
        return sb.toString()
    }

    /**
     * The de Bruijn sequence for [n]-bit codes as a hex key ready for the device's
     * `subghz tx <key> <freq> <te> <repeat>` command, plus the bit length. The bits
     * are left-padded to a whole number of hex nibbles.
     */
    fun hexKey(n: Int): Pair<String, Int> {
        val bits = sequence(n)
        val pad = (4 - bits.length % 4) % 4
        val padded = bits + "0".repeat(pad)
        val sb = StringBuilder()
        var i = 0
        while (i < padded.length) {
            sb.append(padded.substring(i, i + 4).toInt(2).toString(16))
            i += 4
        }
        return sb.toString().uppercase() to bits.length
    }
}
