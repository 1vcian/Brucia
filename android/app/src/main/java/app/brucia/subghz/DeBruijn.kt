package app.brucia.subghz

/**
 * De Bruijn B(2,n) sequence generator: the shortest bit string that contains every
 * n-bit code exactly once (cyclically). Used to brute-force fixed-code OOK remotes
 * in minimal time. Computed on the phone; the device transmits it.
 */
object DeBruijn {
    /** Largest order the generator supports (sequence length 2^22). */
    const val MAX_ORDER = 22

    /**
     * Bits a single device `subghz tx` key can carry: one fixed code of at most 64 bits (16 hex
     * digits). A De Bruijn B(2,n) sweep is 2^n bits and is not a single fixed code, so this path
     * cannot carry it. Named here so the UI, the transmit guard and the tests agree.
     */
    const val DEVICE_TX_KEY_BITS = 64

    /** Orders the UI offers as presets. Every entry must be in 1..[MAX_ORDER] (asserted by tests). */
    val OFFERED_ORDERS = listOf(8, 10, 12, 16, 20, 22)

    /**
     * Why the device cannot transmit a B(2,[n]) De Bruijn sweep over `subghz tx`, phrased for the
     * user. Always a rejection today: `subghz tx` sends one fixed code, not a sweep, so there is no
     * order the phone could hand it without silently truncating or sending the wrong bits. The
     * caller must NOT dispatch any command when this returns; nothing is transmitted.
     */
    fun transmitRejection(n: Int): String {
        if (n < 1 || n > MAX_ORDER) {
            return "De Bruijn order must be 1..$MAX_ORDER; $n is out of range. Nothing was transmitted."
        }
        val bits = 1L shl n
        return "the device subghz tx sends a single fixed code (max $DEVICE_TX_KEY_BITS bits); " +
            "it cannot carry a $n-bit De Bruijn sweep ($bits bits). Nothing was transmitted."
    }

    /** Lexicographically-smallest de Bruijn sequence over {0,1} of order [n] (length 2^n). */
    fun sequence(n: Int): String {
        require(n in 1..MAX_ORDER) { "n out of range" }
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
