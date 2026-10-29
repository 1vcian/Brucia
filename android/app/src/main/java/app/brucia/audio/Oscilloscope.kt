package app.brucia.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure, Android-free oscilloscope logic: edge-trigger search, time-base windowing and the scope
 * measurements (peak-to-peak, RMS, fundamental frequency). The Compose screen only draws what this
 * returns, so the signal processing unit-tests on the JVM.
 */
object Oscilloscope {

    /**
     * Index of the first trigger crossing in [samples] at or after [from]. [rising] selects a
     * rising (sample crosses up through [level]) or falling edge. Returns -1 if none is found.
     */
    fun triggerIndex(samples: ShortArray, level: Int, rising: Boolean, from: Int = 0): Int {
        var i = (from + 1).coerceAtLeast(1)
        while (i < samples.size) {
            val prev = samples[i - 1].toInt()
            val cur = samples[i].toInt()
            if (rising) { if (prev < level && cur >= level) return i }
            else { if (prev > level && cur <= level) return i }
            i++
        }
        return -1
    }

    /**
     * A stable window of [windowLen] samples to draw. In [auto] (free-run) mode the window starts
     * at [preferredStart]; otherwise it is aligned to the first trigger crossing so a periodic
     * signal appears stationary. When no trigger is found the preferred start is used (auto behaviour)
     * so the trace never blanks out. The returned window is clamped to the available samples.
     */
    fun window(
        samples: ShortArray,
        windowLen: Int,
        level: Int,
        rising: Boolean,
        auto: Boolean,
        preferredStart: Int = 0,
    ): ShortArray {
        if (samples.isEmpty() || windowLen <= 0) return ShortArray(0)
        val len = windowLen.coerceAtMost(samples.size)
        val start = if (auto) {
            preferredStart.coerceIn(0, samples.size - len)
        } else {
            val t = triggerIndex(samples, level, rising, from = 0)
            val s = if (t < 0) preferredStart else t
            s.coerceIn(0, samples.size - len)
        }
        return samples.copyOfRange(start, start + len)
    }

    /** Peak-to-peak amplitude (max - min) in raw 16-bit counts. */
    fun peakToPeak(samples: ShortArray): Int {
        if (samples.isEmpty()) return 0
        var mn = Int.MAX_VALUE
        var mx = Int.MIN_VALUE
        for (s in samples) { val v = s.toInt(); if (v < mn) mn = v; if (v > mx) mx = v }
        return mx - mn
    }

    /** RMS amplitude in raw 16-bit counts. */
    fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) { val v = s.toDouble(); sum += v * v }
        return sqrt(sum / samples.size)
    }

    /**
     * Estimated fundamental frequency (Hz) via zero-crossing spacing, which is cheap and stable for
     * a clean periodic input. Returns 0 if too few crossings are seen. A small hysteresis around
     * zero rejects noise-induced crossings.
     */
    fun fundamentalHz(samples: ShortArray, sampleRate: Int): Double {
        if (samples.size < 4) return 0.0
        val peak = samples.maxOf { abs(it.toInt()) }
        if (peak == 0) return 0.0
        val hyst = (peak * 0.1)
        var lastCross = -1.0
        var periods = 0
        var sumSpacing = 0.0
        var armed = false // true once the signal has dipped below -hyst since the last crossing
        var prev = samples[0].toDouble()
        for (i in 1 until samples.size) {
            val cur = samples[i].toDouble()
            if (cur < -hyst) armed = true
            // Rising zero-crossing, only after the signal was armed below -hyst (noise rejection).
            if (armed && prev < 0.0 && cur >= 0.0) {
                val frac = if (cur != prev) (0.0 - prev) / (cur - prev) else 0.0
                val cross = (i - 1) + frac
                if (lastCross >= 0.0) { sumSpacing += cross - lastCross; periods++ }
                lastCross = cross
                armed = false
            }
            prev = cur
        }
        if (periods == 0) return 0.0
        val avgSpacing = sumSpacing / periods
        if (avgSpacing <= 0.0) return 0.0
        return sampleRate / avgSpacing
    }

    // --- XY / vectorscope ("draw with sound") ---------------------------------

    /**
     * Mono phase plot (delay embedding) for the XY vectorscope. The ESP mic is a single channel, so a
     * true stereo X/Y plot is impossible from it; plotting X = sample[n] against Y = sample[n+[delay]]
     * is the standard mono way to "draw with sound" - a tonal input traces a Lissajous-like figure
     * whose shape depends on the delay relative to the period. Returns paired (x, y) sample arrays.
     */
    fun delayEmbed(samples: ShortArray, delay: Int): Pair<ShortArray, ShortArray> {
        val d = delay.coerceAtLeast(1)
        if (samples.size <= d) return Pair(ShortArray(0), ShortArray(0))
        val n = samples.size - d
        val x = ShortArray(n)
        val y = ShortArray(n)
        for (i in 0 until n) { x[i] = samples[i]; y[i] = samples[i + d] }
        return Pair(x, y)
    }

    /**
     * Pair two independent channels (ESP mic -> X, phone mic -> Y) for a true 2-channel vectorscope,
     * truncated to the shorter length. The two capture streams are free-running (not sample-locked),
     * so the figure may rotate/drift - that is expected for the dual-mic view.
     */
    fun pairChannels(x: ShortArray, y: ShortArray): Pair<ShortArray, ShortArray> {
        val n = minOf(x.size, y.size)
        if (n == 0) return Pair(ShortArray(0), ShortArray(0))
        return Pair(x.copyOf(n), y.copyOf(n))
    }

    /** Peak absolute sample value, for auto-scaling the vectorscope square (never below 1). */
    fun peakAbs(samples: ShortArray): Int {
        var p = 1
        for (s in samples) { val v = abs(s.toInt()); if (v > p) p = v }
        return p
    }
}
