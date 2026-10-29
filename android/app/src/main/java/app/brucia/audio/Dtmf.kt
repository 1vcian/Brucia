package app.brucia.audio

/**
 * DTMF (touch-tone) detection and synthesis, plus a simple dominant-tone detector. Pure Kotlin
 * so it unit-tests without an Android context.
 *
 * DTMF encodes each key as the sum of one low-group and one high-group tone:
 *
 *            1209  1336  1477  1633
 *     697      1     2     3     A
 *     770      4     5     6     B
 *     852      7     8     9     C
 *     941      *     0     #     D
 */
object Dtmf {

    val LOW = doubleArrayOf(697.0, 770.0, 852.0, 941.0)
    val HIGH = doubleArrayOf(1209.0, 1336.0, 1477.0, 1633.0)

    private val KEYS = arrayOf(
        charArrayOf('1', '2', '3', 'A'),
        charArrayOf('4', '5', '6', 'B'),
        charArrayOf('7', '8', '9', 'C'),
        charArrayOf('*', '0', '#', 'D'),
    )

    /**
     * Detect a single DTMF digit in [samples], or null if none is clearly present. Uses the
     * Goertzel power of all eight tones: the strongest low and strongest high must each dominate
     * the rest of their group by [ratio] and carry real energy.
     */
    fun detect(samples: ShortArray, sampleRate: Int, ratio: Double = 4.0, minPower: Double = 1e-4): Char? {
        if (samples.size < 160) return null
        val lowP = DoubleArray(LOW.size) { Goertzel.power(samples, sampleRate, LOW[it]) }
        val highP = DoubleArray(HIGH.size) { Goertzel.power(samples, sampleRate, HIGH[it]) }

        val li = argmax(lowP)
        val hi = argmax(highP)
        val lowPeak = lowP[li]
        val highPeak = highP[hi]
        // Energy floor. Acoustically captured tones are far quieter than a synthesized full-scale
        // tone, so the live decoder passes a much lower [minPower] than the default.
        if (lowPeak < minPower || highPeak < minPower) return null

        // Each winner must dominate the others in its own group.
        val lowSecond = secondMax(lowP, li)
        val highSecond = secondMax(highP, hi)
        if (lowPeak < lowSecond * ratio || highPeak < highSecond * ratio) return null
        // Low and high energies should be roughly balanced (a valid twist), not one stray tone.
        val twist = lowPeak / highPeak
        if (twist < 1.0 / 16.0 || twist > 16.0) return null

        return KEYS[li][hi]
    }

    /**
     * Decode a run of DTMF digits from a longer recording. The audio is scanned in [blockMs]
     * blocks; consecutive identical detections collapse to one digit (so a held tone yields one
     * character), and a gap (no detection) separates repeats of the same digit.
     */
    fun decodeSequence(samples: ShortArray, sampleRate: Int, blockMs: Int = 40): String {
        val block = (sampleRate * blockMs / 1000).coerceAtLeast(1)
        val sb = StringBuilder()
        var last: Char? = null
        var pos = 0
        while (pos + block <= samples.size) {
            val chunk = samples.copyOfRange(pos, pos + block)
            val d = detect(chunk, sampleRate)
            if (d != null && d != last) sb.append(d)
            last = d
            pos += block
        }
        return sb.toString()
    }

    /** Synthesise [ms] of the two-tone audio for DTMF [key] at [sampleRate] (16-bit mono). */
    fun synth(key: Char, sampleRate: Int, ms: Int, amplitude: Double = 0.4): ShortArray {
        var row = -1
        var col = -1
        outer@ for (r in KEYS.indices) for (c in KEYS[r].indices) if (KEYS[r][c] == key) { row = r; col = c; break@outer }
        if (row < 0) return ShortArray(0)
        val n = (sampleRate.toLong() * ms / 1000).toInt().coerceAtLeast(0)
        val out = ShortArray(n)
        val amp = amplitude.coerceIn(0.0, 0.5) * Short.MAX_VALUE
        val wl = 2.0 * Math.PI * LOW[row] / sampleRate
        val wh = 2.0 * Math.PI * HIGH[col] / sampleRate
        for (i in 0 until n) {
            val v = amp * (Math.sin(wl * i) + Math.sin(wh * i))
            out[i] = v.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun argmax(a: DoubleArray): Int {
        var bi = 0
        for (i in a.indices) if (a[i] > a[bi]) bi = i
        return bi
    }

    private fun secondMax(a: DoubleArray, exclude: Int): Double {
        var m = 0.0
        for (i in a.indices) if (i != exclude && a[i] > m) m = a[i]
        return m
    }
}
