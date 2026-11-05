package app.brucia.subghz

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Self-contained OOK (on-off keying) decoder for common sub-GHz remotes.
 *
 * The ESP firmware only has to sample the RF receiver output and stream the raw
 * pulse widths. Everything else - timing analysis, protocol matching, bit
 * slicing, frame assembly, repeat counting, fixed-vs-rolling classification -
 * runs here, on the phone.
 *
 * Input convention:
 *   timings[i] is a pulse duration in microseconds, alternating
 *   mark, space, mark, space, ... (mark = carrier on, space = carrier off).
 *   This is exactly what a cheap OOK receiver produces and what the firmware
 *   `subghz.raw` stream carries.
 *
 * Decoding is ratio-based and tolerant (default 35%), so it does not depend on
 * exact microsecond constants: the base time element TE is estimated from the
 * capture, then each candidate protocol is matched by its cell factor model
 * (see [OokProtocol]). Rolling codes (KeeLoq / HCS301) are recognised by their
 * long 64-67 bit frames and reported as analysis only - they are NOT replayable
 * and no jamming / RollJam logic exists here by design.
 */
object SubGhzDecoder {

    /**
     * Relative tolerance when matching a pulse to an expected factor * TE. Kept
     * below 1/3 so a 2*TE pulse can never be mistaken for a 3*TE one (which would
     * let a ratio-3 protocol wrongly decode a ratio-2 signal).
     */
    private const val TOLERANCE = 0.30

    /** Minimum number of bits for a frame to be considered a real decode. */
    private const val MIN_BITS = 8

    /** Plausible pulse window in microseconds; anything outside is noise/gap. */
    private const val MIN_PULSE_US = 60
    private const val MAX_PULSE_US = 200_000

    /** KeeLoq / HCS301 over-the-air data length window (fixed 32 + rolling 32). */
    private val KEELOQ_BITS = 60..68

    /**
     * Decode a raw OOK capture into zero or more best-effort [DecodedSignal]s,
     * most relevant first. Returns an empty list when nothing plausible decodes.
     */
    fun decode(timings: IntArray): List<DecodedSignal> {
        if (timings.size < MIN_BITS * 2) return emptyList()
        val te = estimateTe(timings) ?: return emptyList()
        val teInt = te.roundToInt()

        // 1. Try every known protocol; keep the frames each one explains.
        val candidates = ArrayList<Candidate>()
        for (p in OokProtocols.all) {
            val frames = attemptProtocol(timings, te, p)
            if (frames.isEmpty()) continue
            for ((key, info) in frames) {
                candidates.add(Candidate(p, info.bits, key.substringAfter(':'), info.repeats))
            }
        }

        // 2. Independently look for a rolling-code (KeeLoq) signature.
        val rolling = detectKeeLoq(timings, te)

        val out = ArrayList<DecodedSignal>()
        if (rolling != null) out.add(rolling)

        if (candidates.isNotEmpty()) {
            // Rank: exact fixed-length matches first, then nearest nominal TE,
            // then most repeats. This cleanly separates e.g. CAME (te~320) from
            // NICE_FLO (te~700) when both share the same cell factors.
            candidates.sortWith(
                compareByDescending<Candidate> { it.exactLen }
                    .thenBy { abs(it.proto.te - teInt) }
                    .thenByDescending { it.repeats }
            )
            // Emit distinct (protocol, code) results, best first, de-duplicated.
            val seen = HashSet<String>()
            for (c in candidates) {
                val id = "${c.proto.canonical}:${c.bits}:${c.codeHex}"
                if (!seen.add(id)) continue
                out.add(
                    DecodedSignal(
                        protocol = c.proto.canonical,
                        bits = c.bits,
                        codeHex = c.codeHex,
                        teMicros = teInt,
                        repeats = c.repeats,
                        classification = "fixed",
                        flipperProtocol = c.proto.flipper
                    )
                )
                if (out.size >= 5) break
            }
        }

        // 3. Generic tristate / learn fallback when nothing else matched.
        if (out.isEmpty()) {
            val fallback = tristateFallback(timings, te)
            if (fallback != null) out.add(fallback)
        }
        return out
    }

    private class Candidate(
        val proto: OokProtocol,
        val bits: Int,
        val codeHex: String,
        val repeats: Int
    ) {
        /** 1 when the decoded length equals the protocol's fixed length. */
        val exactLen: Int get() = if (proto.bits != 0 && bits == proto.bits) 1 else 0
    }

    private class FrameInfo(val bits: Int, var repeats: Int)

    // ------------------------------------------------------------------------
    // TE estimation
    // ------------------------------------------------------------------------

    /**
     * Estimate TE as the centre of the shortest consistent cluster of pulses.
     * A low percentile of the sorted data pulses is a noise-robust reference for
     * "a short pulse"; every pulse within tolerance of it is then averaged.
     */
    private fun estimateTe(timings: IntArray): Double? {
        val plausible = timings.filter { it in MIN_PULSE_US..MAX_PULSE_US }.sorted()
        if (plausible.size < MIN_BITS) return null
        val reference = plausible[plausible.size / 10].toDouble()
        if (reference <= 0.0) return null
        val lo = (reference * (1.0 - TOLERANCE)).toInt()
        val hi = (reference * (1.0 + TOLERANCE)).toInt()
        val shortCluster = plausible.filter { it in lo..hi }
        if (shortCluster.isEmpty()) return null
        val te = shortCluster.average()
        return if (te in 80.0..3_000.0) te else null
    }

    // ------------------------------------------------------------------------
    // Per-protocol matching
    // ------------------------------------------------------------------------

    /**
     * Attempt to decode [timings] as protocol [p]. Returns a map of
     * "bits:codeHex" -> frame info (with repeat counts).
     */
    private fun attemptProtocol(
        timings: IntArray,
        te: Double,
        p: OokProtocol
    ): Map<String, FrameInfo> {
        // A separator is any pulse clearly longer than the longest data pulse.
        val syncThreshold = maxOf(5, p.maxDataFactor * 2) * te
        val frames = splitFrames(timings, syncThreshold)
        if (frames.isEmpty()) return emptyMap()

        val counts = LinkedHashMap<String, FrameInfo>()
        // Space-coded (inverted) frames begin with a short preamble pulse after
        // the sync gap, so their cells start at offset 1; high-first frames start
        // at offset 0. Use the polarity-correct offset first to avoid decoding a
        // misaligned (valid-length but wrong) frame, and only then fall back.
        val primary = if (p.inverted) 1 else 0
        val secondary = if (p.inverted) 0 else 1
        for (frame in frames) {
            val decoded = decodeCells(frame, primary, te, p)
                ?: decodeCells(frame, secondary, te, p)
                ?: continue
            if (decoded.isEmpty()) continue
            if (p.bits != 0 && decoded.length != p.bits) continue
            if (decoded.length < MIN_BITS) continue
            val hex = bitsToHex(decoded)
            val key = "${decoded.length}:$hex"
            val existing = counts[key]
            if (existing == null) counts[key] = FrameInfo(decoded.length, 1)
            else existing.repeats++
        }
        return counts
    }

    /** Split a raw stream into frames at every pulse >= [syncThreshold]. */
    private fun splitFrames(timings: IntArray, syncThreshold: Double): List<IntArray> {
        val frames = ArrayList<IntArray>()
        var start = 0
        for (i in timings.indices) {
            val v = timings[i]
            if (v >= syncThreshold || v < MIN_PULSE_US || v > MAX_PULSE_US) {
                if (i > start) frames.add(timings.copyOfRange(start, i))
                start = i + 1
            }
        }
        if (timings.size > start) frames.add(timings.copyOfRange(start, timings.size))
        return frames
    }

    /**
     * Decode a single frame into a big-endian bit string using [p]'s cell model,
     * starting at [offset]. Returns null if the frame does not cleanly decode.
     */
    private fun decodeCells(frame: IntArray, offset: Int, te: Double, p: OokProtocol): String? {
        if (offset >= frame.size) return null
        val sb = StringBuilder()
        var i = offset
        while (i + 1 < frame.size) {
            val a = frame[i]
            val b = frame[i + 1]
            i += 2
            val bit = when {
                matches(a, p.zero.first, te) && matches(b, p.zero.second, te) -> '0'
                matches(a, p.one.first, te) && matches(b, p.one.second, te) -> '1'
                else -> {
                    // Tolerate a stray trailing pulse once we have a real frame.
                    return if (sb.length >= MIN_BITS) sb.toString() else null
                }
            }
            sb.append(bit)
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    private fun matches(value: Int, factor: Int, te: Double): Boolean {
        val center = factor * te
        return abs(value - center) <= center * TOLERANCE
    }

    // ------------------------------------------------------------------------
    // Rolling-code (KeeLoq / HCS301) detection - analysis only
    // ------------------------------------------------------------------------

    /**
     * Recognise a KeeLoq / HCS301-style transmission: a long 64-67 bit PWM frame
     * carrying a 32-bit fixed part (28-bit serial + 4-bit button) and a 32-bit
     * encrypted rolling part. We do NOT decrypt or replay it - rolling codes
     * advance a counter on every press, so a captured frame is already stale.
     */
    private fun detectKeeLoq(timings: IntArray, te: Double): DecodedSignal? {
        // KeeLoq bits are PWM at ratio 2 (short+long / long+short), te ~ 400 us.
        val pwm = OokProtocol("KeeLoq", "KeeLoq", te.roundToInt(), 1 to 2, 2 to 1, 0, false)
        val syncThreshold = maxOf(5, pwm.maxDataFactor * 2) * te
        val frames = splitFrames(timings, syncThreshold)
        var best: String? = null
        for (frame in frames) {
            val decoded = decodeCells(frame, 0, te, pwm) ?: decodeCells(frame, 1, te, pwm) ?: continue
            if (decoded.length in KEELOQ_BITS) {
                val current = best
                if (current == null || decoded.length > current.length) best = decoded
            }
        }
        val bits = best ?: return null

        // Flipper/HCS301 layout: the fixed part is the trailing 32 bits
        // (serial[27:0] + button[3:0]); the leading 32 bits are the cipher hop.
        val fixed = if (bits.length >= 32) bits.takeLast(32) else bits
        val fixedVal = fixed.toULongOrZero()
        val button = ((fixedVal shr 28) and 0xFuL).toInt()
        val serial = (fixedVal and 0x0FFFFFFFuL)
        return DecodedSignal(
            protocol = "KeeLoq",
            bits = bits.length,
            codeHex = bitsToHex(bits),
            teMicros = te.roundToInt(),
            repeats = 1,
            classification = "rolling",
            flipperProtocol = "KeeLoq",
            serialHex = serial.toString(16).uppercase().padStart(7, '0'),
            button = button,
            note = "Rolling code (KeeLoq/HCS301): counter advances every press - a " +
                "captured frame cannot simply be replayed. Analysis only."
        )
    }

    // ------------------------------------------------------------------------
    // Generic tristate / learn fallback
    // ------------------------------------------------------------------------

    /**
     * Last-resort decode for unrecognised fixed-code remotes: assume a Princeton
     * grammar (short/long = 1:3 or 1:2) and report the raw bits. Marked "unknown"
     * so the UI makes clear the protocol identity is a guess.
     */
    private fun tristateFallback(timings: IntArray, te: Double): DecodedSignal? {
        for (ratio in intArrayOf(3, 2)) {
            val gen = OokProtocol("Unknown", "Princeton", te.roundToInt(), 1 to ratio, ratio to 1, 0, false)
            val frames = attemptProtocol(timings, te, gen)
            val best = frames.values.maxByOrNull { it.repeats } ?: continue
            val key = frames.entries.first { it.value === best }.key
            val hex = key.substringAfter(':')
            return DecodedSignal(
                protocol = "Unknown",
                bits = best.bits,
                codeHex = hex,
                teMicros = te.roundToInt(),
                repeats = best.repeats,
                classification = "unknown",
                flipperProtocol = "Princeton",
                note = "Unrecognised fixed-code remote; decoded with a generic " +
                    "tristate/learn (1:$ratio) grammar - verify before transmitting."
            )
        }
        return null
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** Render a big-endian bit string as zero-padded uppercase hex. */
    private fun bitsToHex(bits: String): String {
        val hexDigits = (bits.length + 3) / 4
        val sb = StringBuilder()
        var i = 0
        // Pad on the left to a whole nibble so the MSB keeps its place.
        val pad = (4 - bits.length % 4) % 4
        val padded = "0".repeat(pad) + bits
        while (i < padded.length) {
            sb.append(padded.substring(i, i + 4).toInt(2).toString(16))
            i += 4
        }
        return sb.toString().uppercase().padStart(hexDigits, '0')
    }

    private fun String.toULongOrZero(): ULong {
        var v = 0uL
        for (c in this) {
            v = (v shl 1) or (if (c == '1') 1uL else 0uL)
        }
        return v
    }
}
