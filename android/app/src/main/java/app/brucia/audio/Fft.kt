package app.brucia.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Minimal, Android-free FFT and spectrum helpers used by the Audio tools (spectrum bars,
 * waterfall, tone detection). Everything here is pure Kotlin so it unit-tests on the JVM
 * without an Android context.
 *
 * The transform is an in-place iterative radix-2 Cooley-Tukey FFT; sizes must be powers of two.
 */
object Fft {

    /** True when [n] is a power of two (and positive). */
    fun isPow2(n: Int): Boolean = n > 0 && (n and (n - 1)) == 0

    /** Largest power of two <= [n] (at least 1). */
    fun floorPow2(n: Int): Int {
        if (n < 1) return 1
        var p = 1
        while (p * 2 <= n) p *= 2
        return p
    }

    /**
     * In-place complex FFT. [re] and [im] must be the same length, a power of two. When
     * [inverse] is true an inverse transform is computed (without the 1/N scaling).
     */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size) { "re/im length mismatch" }
        require(isPow2(n)) { "FFT size must be a power of two, got $n" }
        if (n == 1) return
        // Bit-reversal permutation.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // Butterflies.
        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2.0 else -2.0) * PI / len
            val wRe = cos(ang)
            val wIm = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + half] * curRe - im[i + k + half] * curIm
                    val bIm = re[i + k + half] * curIm + im[i + k + half] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + half] = aRe - bRe
                    im[i + k + half] = aIm - bIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** A Hann window of length [n]. */
    fun hann(n: Int): DoubleArray {
        if (n <= 1) return DoubleArray(n) { 1.0 }
        return DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1)) }
    }

    /**
     * Magnitude spectrum of [samples] over a window of [size] samples (power of two). The first
     * [size] samples are used (zero-padded if fewer); a Hann window is applied. Returns the
     * linear magnitudes for bins 0 until size/2.
     */
    fun magnitude(samples: ShortArray, size: Int, offset: Int = 0): DoubleArray {
        require(isPow2(size)) { "size must be a power of two" }
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        val win = hann(size)
        for (i in 0 until size) {
            val idx = offset + i
            val s = if (idx in samples.indices) samples[idx].toDouble() else 0.0
            re[i] = s * win[i]
        }
        fft(re, im)
        val out = DoubleArray(size / 2)
        for (k in out.indices) out[k] = hypot(re[k], im[k])
        return out
    }

    /** Frequency (Hz) of FFT bin [bin] for a transform of [size] samples at [sampleRate]. */
    fun binToFreq(bin: Int, size: Int, sampleRate: Int): Double =
        bin.toDouble() * sampleRate / size

    /**
     * Index of the strongest bin in [mag]. Bins below [minBin] are ignored (use it to skip DC).
     */
    fun peakBin(mag: DoubleArray, minBin: Int = 1): Int {
        var best = minBin.coerceIn(0, mag.size - 1)
        var bestV = -1.0
        for (k in minBin until mag.size) {
            if (mag[k] > bestV) { bestV = mag[k]; best = k }
        }
        return best
    }

    /**
     * Dominant frequency (Hz) in [samples], estimated from the FFT peak with parabolic
     * interpolation for sub-bin accuracy. [size] is the FFT window (power of two).
     */
    fun dominantFreq(samples: ShortArray, sampleRate: Int, size: Int = 2048): Double {
        val n = if (isPow2(size)) size else floorPow2(samples.size.coerceAtLeast(2))
        val mag = magnitude(samples, n)
        val k = peakBin(mag, minBin = 1)
        // Parabolic interpolation around the peak for a finer estimate.
        var delta = 0.0
        if (k > 0 && k < mag.size - 1) {
            val a = mag[k - 1]
            val b = mag[k]
            val c = mag[k + 1]
            val denom = (a - 2 * b + c)
            if (denom != 0.0) delta = 0.5 * (a - c) / denom
        }
        return (k + delta) * sampleRate / n
    }

    /** Convert a linear magnitude to a 0..1 value on a dB scale, clamped to [floorDb]..0 dB. */
    fun toDbNorm(mag: Double, ref: Double, floorDb: Double = -60.0): Float {
        if (mag <= 0.0 || ref <= 0.0) return 0f
        val db = 20.0 * ln(mag / ref) / ln(10.0)
        val n = (db - floorDb) / (0.0 - floorDb)
        return n.coerceIn(0.0, 1.0).toFloat()
    }

    /** RMS of [samples] (0..1 normalised to full-scale 16-bit). */
    fun rms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) { val v = s.toDouble(); sum += v * v }
        return (sqrt(sum / samples.size) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }
}
