package app.brucia.subghz

import app.brucia.protocol.FrameBudget

/**
 * Pure planner that turns a De Bruijn B(2,n) sweep into an ordered list of `subghz_tx_raw`
 * commands the device can actually transmit as a continuous raw OOK waveform. No Android deps, so
 * it is fully unit-testable on the JVM.
 *
 * Why this exists: [DeBruijn.transmitRejection] still explains why the single-code `subghz tx`
 * path cannot carry a sweep (it sends one fixed code of at most 64 bits). The device DOES have a
 * raw-timing primitive, exposed to the companion as `subghz_tx_raw <freqKHz> <te> <csv durations>`
 * (src/companion/subghz.cpp runSubghzTxRaw, ~l.356), which drives the existing RMT raw path. This
 * planner encodes the sweep to that primitive's EXACT edge convention and splits it to fit the
 * companion frame cap, so a real sweep can be streamed app-side with no firmware change.
 *
 * Edge / sign convention (verified against the firmware, cite lines):
 *  - The companion builds `code.protocol = "RAW"`, `code.preset = "Ook270Async"`, `code.data =
 *    <csv>` and calls `sendRfCommand` (src/companion/subghz.cpp runSubghzTxRaw). The RAW branch of
 *    `sendRfCommand` (src/modules/rf/rf_send.cpp:438-467) parses the CSV left-to-right into a
 *    0-terminated `int` array and calls `rfTransmitRawTimings` (rf_send.cpp:527), which forwards it
 *    to `rf_tx_raw_timings` (src/modules/rf/protocols/rf_encoder.cpp:223-228): it reads the array
 *    FORWARD until the first 0 and feeds `rf_tx_durations`.
 *  - `rf_tx_durations` (rf_encoder.cpp:50-64): for each signed duration `d`, `if (d == 0) continue`
 *    (so 0 is a terminator/no-op and must never be emitted), `level = (d > 0) ? 1 : 0`, held for
 *    `abs(d)` µs. So: POSITIVE duration = HIGH (level 1); NEGATIVE duration = LOW (level 0); the
 *    durations play in CSV order (index 0 on air first).
 *  - Therefore each sweep bit becomes one half-pulse of [te] µs at the matching level: bit '1' ->
 *    `+te` (HIGH), bit '0' -> `-te` (LOW). `<te>` is accepted by the companion for API symmetry;
 *    RAW uses the explicit durations directly (subghz.cpp comment), so correctness rests on the
 *    signed durations, not on the `<te>` field.
 *
 * Cyclic coverage: `DeBruijn.sequence(n)` is the cyclic B(2,n) sequence of length 2^n in which
 * every n-bit code appears exactly once WHEN READ CYCLICALLY. Transmitted linearly it would miss
 * the n-1 codes that wrap past the end, so the planner appends the first n-1 bits
 * ("linearization"): `full = sequence + sequence[0 until n-1]`, length 2^n + n - 1, in which every
 * n-bit code appears exactly once as a plain substring `full[i .. i+n-1]`, i in 0 until 2^n.
 *
 * Chunking with overlap: one `subghz_tx_raw` frame must stay under the companion cap
 * ([FrameBudget.MAX_FRAME] = 8192 bytes), so a long sweep is split into several sequential calls.
 * Consecutive chunks OVERLAP by n-1 bits (each new chunk re-sends the previous chunk's last n-1
 * bits as its lead-in). That guarantees every n-bit window, including those straddling a cut,
 * appears intact inside some chunk: a window `[p, p+n)` that crosses boundary `b` has
 * `p >= b-n+1`, and the next chunk starts exactly at `b-(n-1) = b-n+1 <= p`, so it contains the
 * whole window. (Chunks within one frame are separate on-air bursts with a small gap between
 * them; coverage is a property of the bit windows, not of a single continuous transmission.)
 */
object DeBruijnTx {
    /**
     * Largest order this planner will stream as raw bursts. A De Bruijn sweep is 2^n bits and each
     * bit is a [te]-µs half-pulse, so order 16 is already 65536 bits (~26 s on air at te=400 µs,
     * ~50 frames, a few hundred KB of command text). Beyond this a phone-streamed raw sweep is
     * impractical (on-air time grows 2x per order; order 20 is ~17 min, order 22 ~70 min, and the
     * materialized command list reaches tens of MB), so the planner REJECTS rather than build a
     * plan that cannot realistically run. The sequence itself stays computable via
     * [DeBruijn.sequence]; nothing is silently truncated. Raise this constant if a longer sweep is
     * ever wanted; the coverage math is unchanged.
     */
    const val MAX_TX_ORDER = 16

    /** Smallest order worth sweeping. */
    const val MIN_TX_ORDER = 1

    /** UI presets that this planner can actually transmit (the subset of [DeBruijn.OFFERED_ORDERS]). */
    val OFFERED_TX_ORDERS: List<Int> = DeBruijn.OFFERED_ORDERS.filter { it in MIN_TX_ORDER..MAX_TX_ORDER }

    /** Accepted pulse-width range (µs); matches the UI custom-te clamp. */
    val TE_RANGE = 50..2000

    /** How many times the whole sweep may be repeated back-to-back. */
    val REPEAT_RANGE = 1..8

    /**
     * Safety margin (bytes) kept free below the frame budget. [FrameBudget.cmdPayload] already
     * accounts for the JSON envelope exactly, so a fixed guard (rather than an arbitrary fraction
     * that would needlessly multiply frame count and on-air time) is enough to stay clear of the
     * hard cap against any id-width or formatting drift.
     */
    const val FRAME_SAFETY_MARGIN = 256

    /** Outcome of [plan]. */
    sealed interface Plan {
        /** The sweep cannot be transmitted; [reason] is user-facing and nothing must be sent. */
        data class Rejected(val reason: String) : Plan

        /**
         * A transmittable sweep. [commands] are sent in order through `subghz_tx_raw`; every n-bit
         * window is covered across the set (the test reconstructs and asserts this independently).
         */
        data class Ready(
            val commands: List<String>,
            val order: Int,
            val teUs: Int,
            val freqHz: Long,
            val freqKHz: Long,
            /** 2^n: codes in the cyclic sequence. */
            val sequenceBits: Int,
            /** 2^n + (n-1): bits actually transmitted per pass after linearization. */
            val linearizedBits: Int,
            /** n-1: bits shared between consecutive chunks. */
            val overlapBits: Int,
            /** Frames per sweep pass. */
            val chunksPerPass: Int,
            /** Number of full sweep passes ([REPEAT_RANGE]). */
            val passes: Int,
            /** Total frames = chunksPerPass * passes = commands.size. */
            val chunkCount: Int,
            /** Largest chunk's bit count (bounds the per-frame on-air time). */
            val maxChunkBits: Int,
            /** Per-frame byte budget used (frame limit minus [FRAME_SAFETY_MARGIN]). */
            val budgetBytes: Int,
            /** True by construction for a [Ready] plan; the test verifies it from [commands]. */
            val everyWindowCovered: Boolean = true,
        ) : Plan {
            /** On-air µs of the largest single frame, for a per-command timeout. */
            val maxChunkOnAirUs: Long get() = maxChunkBits.toLong() * teUs
        }
    }

    /**
     * Plan the sweep, or reject it with a clear reason. [order] is the De Bruijn order n, [teUs]
     * the half-pulse width, [freqHz] the carrier, [repeat] the number of full passes, and
     * [frameLimit] the companion frame cap to size chunks against (default [FrameBudget.MAX_FRAME]).
     * Deterministic: the same inputs always produce the same plan.
     */
    fun plan(
        order: Int,
        teUs: Int,
        freqHz: Long,
        repeat: Int = 1,
        frameLimit: Int = FrameBudget.MAX_FRAME,
    ): Plan {
        if (order < MIN_TX_ORDER || order > MAX_TX_ORDER) {
            val why = if (order > MAX_TX_ORDER)
                "order $order is too large to stream as raw bursts (max $MAX_TX_ORDER; a 2^$order-bit " +
                    "sweep would take far too long and too much memory to send from the phone)"
            else "order must be $MIN_TX_ORDER..$MAX_TX_ORDER; $order is out of range"
            return Plan.Rejected("De Bruijn transmit $why. Nothing was transmitted.")
        }
        if (teUs !in TE_RANGE) {
            return Plan.Rejected(
                "De Bruijn transmit pulse width must be ${TE_RANGE.first}..${TE_RANGE.last} us; " +
                    "$teUs is out of range. Nothing was transmitted."
            )
        }
        if (freqHz <= 0L) {
            return Plan.Rejected("De Bruijn transmit needs a positive frequency. Nothing was transmitted.")
        }
        val passes = repeat.coerceIn(REPEAT_RANGE.first, REPEAT_RANGE.last)
        val freqKHz = freqHz / 1000L
        val budget = frameLimit - FRAME_SAFETY_MARGIN

        val token1 = teUs.toString()        // '1' -> HIGH -> +te
        val token0 = "-$token1"             // '0' -> LOW  -> -te
        val maxTokenLen = token0.length     // the longer of the two tokens
        val prefix = "subghz_tx_raw $freqKHz $teUs"
        val prefixCost = FrameBudget.cmdPayload(prefix)

        // Worst-case bits a single frame can hold: every token is the longer one, each preceded by
        // one separator space. A frame must hold at least n bits or a straddling window cannot be
        // re-covered after a cut (and the overlapped scan could not advance).
        val worstBitsPerFrame = (budget - prefixCost) / (1 + maxTokenLen)
        if (worstBitsPerFrame < order) {
            return Plan.Rejected(
                "De Bruijn transmit frame budget ($budget bytes) is too small for order $order at " +
                    "te=$teUs us (a frame holds at most $worstBitsPerFrame bits, need $order). " +
                    "Nothing was transmitted."
            )
        }

        val n = order
        val sequence = DeBruijn.sequence(n)
        val sequenceBits = sequence.length                 // 2^n
        val overlap = n - 1
        // Linearize for cyclic coverage: append the first n-1 bits.
        val full = if (overlap > 0) sequence + sequence.substring(0, overlap) else sequence
        val linearizedBits = full.length                   // 2^n + n - 1

        val onePass = ArrayList<String>()
        var maxChunkBits = 0
        var start = 0
        while (true) {
            val sb = StringBuilder(prefix)
            var cost = prefixCost
            var i = start
            while (i < full.length) {
                val tok = if (full[i] == '1') token1 else token0
                val add = 1 + tok.length                    // separator + token
                if (cost + add > budget) break
                sb.append(' ').append(tok)
                cost += add
                i++
            }
            val chunkBits = i - start
            maxChunkBits = maxOf(maxChunkBits, chunkBits)
            onePass.add(sb.toString())
            if (i >= full.length) break
            // Overlap by n-1 bits so the window crossing this cut is whole in the next chunk.
            // chunkBits >= n here (budget holds >= n bits), so start strictly advances.
            start = i - overlap
        }

        val commands = if (passes == 1) onePass else ArrayList<String>(onePass.size * passes).apply {
            repeat(passes) { addAll(onePass) }
        }

        return Plan.Ready(
            commands = commands,
            order = n,
            teUs = teUs,
            freqHz = freqHz,
            freqKHz = freqKHz,
            sequenceBits = sequenceBits,
            linearizedBits = linearizedBits,
            overlapBits = overlap,
            chunksPerPass = onePass.size,
            passes = passes,
            chunkCount = commands.size,
            maxChunkBits = maxChunkBits,
            budgetBytes = budget,
        )
    }
}
