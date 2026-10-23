package app.brucia.subghz

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Self-contained OOK (on-off keying) decoder for common fixed-code sub-GHz remotes.
 *
 * The ESP firmware only has to sample the RF receiver output and stream the raw
 * pulse widths. Everything else - timing analysis, bit slicing, frame assembly,
 * repeat counting - runs here, on the phone.
 *
 * Input convention:
 *   timings[i] is a pulse duration in microseconds, alternating
 *   mark, space, mark, space, ... (mark = carrier on, space = carrier off).
 *   This is exactly what a cheap OOK receiver produces.
 *
 * Supported family (Princeton PT2262 / EV1527 style, the vast majority of cheap
 * 433/315 MHz remotes):
 *   - TE is the base time element (short pulse), typically 250-500 us.
 *   - Each data bit is a (mark, space) pair:
 *         short mark + long  space  => bit 0
 *         long  mark + short space  => bit 1
 *     where short ~= 1*TE and long ~= 3*TE.
 *   - A long low gap (~31*TE) acts as the sync separator between frames.
 *
 * The decoder is ratio-based and tolerant (default 35%), so it does not depend
 * on exact microsecond constants, and it ignores implausibly short/long pulses
 * so stray noise does not corrupt a frame.
 */
object SubGhzDecoder {

    // --- Tunable, but ratio-based, thresholds -------------------------------

    /** Relative tolerance when classifying a pulse as short (1*TE) or long (3*TE). */
    private const val TOLERANCE = 0.35

    /** Ratio of a "long" pulse to TE for Princeton-family remotes. */
    private const val LONG_RATIO = 3.0

    /**
     * A space at least this many TE long is treated as the inter-frame sync gap.
     * Real remotes use ~31*TE; we accept anything clearly longer than a long
     * data pulse so the exact ratio does not matter.
     */
    private const val SYNC_RATIO = 7.0

    /** Minimum number of bits for a frame to be considered a real decode. */
    private const val MIN_BITS = 12

    /** Plausible pulse window in microseconds; anything outside is treated as noise. */
    private const val MIN_PULSE_US = 60
    private const val MAX_PULSE_US = 60_000

    /**
     * Decode a raw OOK capture into zero or more best-effort [DecodedSignal]s.
     * Returns an empty list when nothing plausible decodes.
     */
    fun decode(timings: IntArray): List<DecodedSignal> {
        if (timings.size < MIN_BITS * 2) return emptyList()

        // 1. Estimate the base time element TE from the pulse population.
        val te = estimateTe(timings) ?: return emptyList()

        // 2. Slice the stream into frames and decode each one.
        val frames = decodeFrames(timings, te)
        if (frames.isEmpty()) return emptyList()

        // 3. Group identical frames and count repeats.
        //    Key = "bitCount:codeHex" so frames of different widths stay distinct.
        val counts = LinkedHashMap<String, FrameInfo>()
        for (f in frames) {
            val key = "${f.bits}:${f.codeHex}"
            val existing = counts[key]
            if (existing == null) {
                counts[key] = FrameInfo(f.bits, f.codeHex, 1)
            } else {
                existing.repeats++
            }
        }

        // 4. Emit one DecodedSignal per distinct frame, most repeated first.
        val teInt = te.roundToInt()
        return counts.values
            .sortedByDescending { it.repeats }
            .map { info ->
                DecodedSignal(
                    protocol = protocolFor(info.bits),
                    bits = info.bits,
                    codeHex = info.codeHex,
                    teMicros = teInt,
                    repeats = info.repeats
                )
            }
    }

    private class FrameInfo(val bits: Int, val codeHex: String, var repeats: Int)

    private class RawFrame(val bits: Int, val codeHex: String)

    // ------------------------------------------------------------------------
    // Step 1: TE estimation
    // ------------------------------------------------------------------------

    /**
     * Estimate TE as the centre of the shortest consistent cluster of pulses.
     *
     * We use a low percentile of the sorted durations as a noise-robust
     * reference for "a short pulse", then average every pulse that falls within
     * tolerance of that reference. This avoids both single tiny noise spikes
     * (which would fool a plain minimum) and the long pulses / sync gaps.
     */
    private fun estimateTe(timings: IntArray): Double? {
        val plausible = timings.filter { it in MIN_PULSE_US..MAX_PULSE_US }.sorted()
        if (plausible.size < MIN_BITS) return null

        // 10th percentile is comfortably inside the "short pulse" cluster while
        // skipping a handful of abnormally small readings.
        val reference = plausible[plausible.size / 10].toDouble()
        if (reference <= 0.0) return null

        val lo = reference * (1.0 - TOLERANCE)
        val hi = reference * (1.0 + TOLERANCE)
        val shortCluster = plausible.filter { it in lo.toInt()..hi.toInt() }
        if (shortCluster.isEmpty()) return null

        val te = shortCluster.average()
        // Sanity: TE for these remotes lives in a realistic window.
        return if (te in 100.0..2_000.0) te else null
    }

    // ------------------------------------------------------------------------
    // Step 2: framing + bit slicing
    // ------------------------------------------------------------------------

    private fun decodeFrames(timings: IntArray, te: Double): List<RawFrame> {
        val shortLo = te * (1.0 - TOLERANCE)
        val shortHi = te * (1.0 + TOLERANCE)
        val longCenter = te * LONG_RATIO
        val longLo = longCenter * (1.0 - TOLERANCE)
        val longHi = longCenter * (1.0 + TOLERANCE)
        val syncGap = te * SYNC_RATIO

        val frames = ArrayList<RawFrame>()

        // Accumulate a frame as a big-endian bit sequence.
        var acc = 0L
        var bitCount = 0

        fun flush() {
            if (bitCount in MIN_BITS..64) {
                frames.add(RawFrame(bitCount, toHex(acc, bitCount)))
            }
            acc = 0L
            bitCount = 0
        }

        // Walk the stream pair by pair (mark, space).
        var i = 0
        while (i + 1 < timings.size) {
            val mark = timings[i]
            val space = timings[i + 1]
            i += 2

            // Drop impossible pulses as noise and break the current frame so a
            // glitch cannot silently merge two transmissions.
            if (mark !in MIN_PULSE_US..MAX_PULSE_US || space < MIN_PULSE_US) {
                flush()
                continue
            }

            // A very long low gap is the sync separator: the preceding mark is
            // the sync bit, not data, so just finalize the accumulated frame.
            if (space >= syncGap) {
                flush()
                continue
            }

            val markShort = mark.toDouble() in shortLo..shortHi
            val markLong = mark.toDouble() in longLo..longHi
            val spaceShort = space.toDouble() in shortLo..shortHi
            val spaceLong = space.toDouble() in longLo..longHi

            val bit: Int = when {
                markShort && spaceLong -> 0            // short mark + long space
                markLong && spaceShort -> 1            // long mark + short space
                else -> {
                    // Unrecognised pair: treat as corruption, end the frame.
                    flush()
                    continue
                }
            }

            if (bitCount >= 64) {
                // Overlong run without a sync - most likely noise; restart.
                flush()
            }
            acc = (acc shl 1) or bit.toLong()
            bitCount++
        }

        // Capture a trailing frame that was not terminated by a sync gap.
        flush()

        return frames
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** Render [bitCount] bits of [value] as zero-padded uppercase hex. */
    private fun toHex(value: Long, bitCount: Int): String {
        val hexDigits = (bitCount + 3) / 4
        val mask = if (bitCount >= 64) -1L else (1L shl bitCount) - 1L
        val masked = value and mask
        val raw = masked.toString(16).uppercase()
        return raw.padStart(hexDigits, '0')
    }

    /**
     * Map a bit width to a likely protocol family name. These cheap remotes all
     * share the Princeton pulse grammar, so width is the main discriminator we
     * can offer without manufacturer-specific framing.
     */
    private fun protocolFor(bits: Int): String = when (bits) {
        24 -> "PT2262/EV1527"
        12 -> "Princeton"
        else -> "Princeton-like"
    }

    // Kept for readability of intent; small numeric helpers.
    @Suppress("unused")
    private fun withinRatio(value: Int, center: Double, tol: Double): Boolean {
        val d = abs(value - center)
        return d <= center * tol
    }

    @Suppress("unused")
    private fun clampRange(lo: Int, hi: Int, v: Int): Int = max(lo, min(hi, v))
}
