package app.brucia.audio

import kotlin.math.PI
import kotlin.math.cos

/**
 * Goertzel single-frequency power estimator - cheaper than a full FFT when only a handful of
 * target frequencies matter (DTMF, a known tone). Pure Kotlin, JVM-testable.
 */
object Goertzel {

    /**
     * Normalised power of [targetFreq] within [samples] (first [count] samples). The result is
     * magnitude-squared divided by N*N so it is comparable across block sizes and roughly 0..1
     * for a full-scale tone at the target frequency.
     */
    fun power(samples: ShortArray, sampleRate: Int, targetFreq: Double, count: Int = samples.size): Double {
        val n = count.coerceIn(0, samples.size)
        if (n == 0) return 0.0
        val k = (0.5 + n * targetFreq / sampleRate).toInt()
        val w = 2.0 * PI * k / n
        val coeff = 2.0 * cos(w)
        var s0: Double
        var s1 = 0.0
        var s2 = 0.0
        val norm = 1.0 / Short.MAX_VALUE
        for (i in 0 until n) {
            s0 = coeff * s1 - s2 + samples[i].toDouble() * norm
            s2 = s1
            s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return power / (n.toDouble() * n.toDouble()) * 4.0
    }
}
