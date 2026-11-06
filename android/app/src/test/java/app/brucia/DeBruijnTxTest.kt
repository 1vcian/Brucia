package app.brucia

import app.brucia.protocol.FrameBudget
import app.brucia.subghz.DeBruijn
import app.brucia.subghz.DeBruijnTx
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure-logic tests for the De Bruijn transmit planner. Proves it covers every n-bit window across
 * the chunk set (even when split), keeps each frame under budget, encodes the firmware's exact
 * edge/sign convention, and rejects (never truncates) infeasible input. Runs on the JVM and in the
 * host harness (tools/test_android_logic.py).
 */
class DeBruijnTxTest {
    private val freq = 433_920_000L
    private val freqKHz = 433_920L

    // --- reconstruction helpers: read the bits back out of the emitted commands ---

    /** Decode one command's durations back to a bitstring: +te -> '1' (HIGH), -te -> '0' (LOW). */
    private fun bitsOf(cmd: String, te: Int): String {
        val parts = cmd.split(' ')
        assertEquals("subghz_tx_raw", parts[0])
        assertEquals(freqKHz.toString(), parts[1])
        assertEquals(te.toString(), parts[2])
        return parts.drop(3).joinToString("") { tok ->
            when (tok) {
                te.toString() -> "1"
                "-$te" -> "0"
                else -> error("unexpected duration token: $tok")
            }
        }
    }

    /** Every distinct n-bit window present across the chunk set. */
    private fun coveredWindows(cmds: List<String>, n: Int, te: Int): Set<String> {
        val set = HashSet<String>()
        for (c in cmds) {
            val b = bitsOf(c, te)
            for (i in 0..b.length - n) set.add(b.substring(i, i + n))
        }
        return set
    }

    private fun allWindows(n: Int): Set<String> =
        (0 until (1 shl n)).map { it.toString(2).padStart(n, '0') }.toSet()

    private fun ready(order: Int, te: Int = 400, repeat: Int = 1, frameLimit: Int = FrameBudget.MAX_FRAME) =
        DeBruijnTx.plan(order, te, freq, repeat, frameLimit) as DeBruijnTx.Plan.Ready

    // --- coverage completeness ---

    @Test fun coversEveryWindowForSeveralOrders() {
        for (n in listOf(2, 3, 4, 8, 12)) {
            val p = ready(n)
            assertEquals("order $n", allWindows(n), coveredWindows(p.commands, n, 400))
            assertEquals("order $n window count", 1 shl n, p.sequenceBits)
            assertTrue(p.everyWindowCovered)
        }
    }

    @Test fun coversEveryWindowWhenSplitIntoManyChunks() {
        // A tight frame forces many overlapping chunks; coverage must still be complete.
        val p = ready(8, te = 2000, frameLimit = 600)
        assertTrue("expected many chunks, got ${p.chunksPerPass}", p.chunksPerPass > 4)
        assertEquals(allWindows(8), coveredWindows(p.commands, 8, 2000))
    }

    // --- overlap correctness ---

    @Test fun consecutiveChunksOverlapByExactlyNMinus1() {
        val n = 8
        val p = ready(n, te = 2000, frameLimit = 600)
        assertTrue(p.chunksPerPass > 1)
        assertEquals(n - 1, p.overlapBits)
        val bits = p.commands.map { bitsOf(it, 2000) }
        for (k in 0 until bits.size - 1) {
            val tail = bits[k].takeLast(n - 1)
            val head = bits[k + 1].take(n - 1)
            assertEquals("overlap at cut $k", tail, head)
        }
    }

    // --- budget ---

    @Test fun everyChunkStaysUnderBudget() {
        val frameLimit = 700
        val p = ready(8, te = 2000, frameLimit = frameLimit)
        assertEquals(frameLimit - DeBruijnTx.FRAME_SAFETY_MARGIN, p.budgetBytes)
        for (c in p.commands) assertTrue("cmd fits", FrameBudget.cmdPayload(c) <= p.budgetBytes)
        // And comfortably under the hard companion cap.
        for (c in p.commands) assertTrue(FrameBudget.cmdPayload(c) < FrameBudget.MAX_FRAME)
    }

    // --- exact edge/sign convention (hand-checked) ---

    @Test fun handCheckedVectorMatchesFirmwareConvention() {
        // sequence(2) = "0011"; linearized full = "00110" (append first n-1=1 bit).
        // '0' -> LOW -> -te, '1' -> HIGH -> +te, in sequence order (index 0 on air first).
        val p = ready(2, te = 100)
        assertEquals(listOf("subghz_tx_raw 433920 100 -100 -100 100 100 -100"), p.commands)
        assertEquals("0011", DeBruijn.sequence(2))
    }

    @Test fun firstTokenFollowsFirstBitHighIsPositive() {
        val n = 3
        val p = ready(n, te = 250)
        val full = DeBruijn.sequence(n) + DeBruijn.sequence(n).substring(0, n - 1)
        val firstTok = p.commands.first().split(' ')[3]
        assertEquals(if (full[0] == '1') "250" else "-250", firstTok)
        assertEquals("roundtrip", full, bitsOf(p.commands.first(), 250)) // single chunk at this budget
    }

    // --- facts ---

    @Test fun factsAreConsistent() {
        val p = ready(10)
        assertEquals(1 shl 10, p.sequenceBits)
        assertEquals((1 shl 10) + 9, p.linearizedBits)
        assertEquals(9, p.overlapBits)
        assertEquals(p.chunksPerPass, p.commands.size)
        assertEquals(p.chunksPerPass, p.chunkCount) // 1 pass
        assertTrue(p.maxChunkBits >= p.order)
        assertEquals(p.maxChunkBits.toLong() * p.teUs, p.maxChunkOnAirUs)
    }

    // --- repeat ---

    @Test fun repeatDuplicatesPassesAndIsClamped() {
        val one = ready(6, repeat = 1)
        val three = ready(6, repeat = 3)
        assertEquals(3, three.passes)
        assertEquals(one.chunksPerPass * 3, three.chunkCount)
        assertEquals(List(3) { one.commands }.flatten(), three.commands)
        assertEquals(DeBruijnTx.REPEAT_RANGE.first, ready(6, repeat = 0).passes)
        assertEquals(DeBruijnTx.REPEAT_RANGE.last, ready(6, repeat = 99).passes)
    }

    // --- rejections (never truncate) ---

    @Test fun rejectsInfeasibleInput() {
        val cases = listOf(
            DeBruijnTx.plan(0, 400, freq),
            DeBruijnTx.plan(DeBruijnTx.MAX_TX_ORDER + 1, 400, freq),
            DeBruijnTx.plan(DeBruijn.MAX_ORDER, 400, freq), // 22 > MAX_TX_ORDER
            DeBruijnTx.plan(8, 49, freq),
            DeBruijnTx.plan(8, 2001, freq),
            DeBruijnTx.plan(8, 400, 0L),
            DeBruijnTx.plan(8, 2000, freq, frameLimit = 300), // frame too small to hold n bits
        )
        for (p in cases) {
            assertTrue("expected Rejected: $p", p is DeBruijnTx.Plan.Rejected)
            assertTrue((p as DeBruijnTx.Plan.Rejected).reason.endsWith("Nothing was transmitted."))
        }
    }

    @Test fun offeredOrdersAreAllFeasible() {
        assertTrue(DeBruijnTx.OFFERED_TX_ORDERS.isNotEmpty())
        for (n in DeBruijnTx.OFFERED_TX_ORDERS) {
            assertTrue(n in 1..DeBruijnTx.MAX_TX_ORDER)
            assertTrue(DeBruijnTx.plan(n, 400, freq) is DeBruijnTx.Plan.Ready)
        }
    }

    // --- determinism ---

    @Test fun planIsDeterministic() {
        assertEquals(ready(10).commands, ready(10).commands)
        assertEquals(ready(8, te = 2000, frameLimit = 600).commands, ready(8, te = 2000, frameLimit = 600).commands)
    }

    // --- mutation check: removing the overlap must break coverage ---

    /** The planner WITHOUT overlap (each chunk starts where the previous ended). */
    private fun planNoOverlap(n: Int, te: Int, frameLimit: Int): List<String> {
        val budget = frameLimit - DeBruijnTx.FRAME_SAFETY_MARGIN
        val full = DeBruijn.sequence(n) + DeBruijn.sequence(n).substring(0, n - 1)
        val prefix = "subghz_tx_raw $freqKHz $te"
        val cmds = ArrayList<String>()
        var i = 0
        while (i < full.length) {
            val sb = StringBuilder(prefix); var cost = FrameBudget.cmdPayload(prefix)
            while (i < full.length) {
                val tok = if (full[i] == '1') "$te" else "-$te"
                val add = 1 + tok.length
                if (cost + add > budget) break
                sb.append(' ').append(tok); cost += add; i++
            }
            cmds.add(sb.toString())
        }
        return cmds
    }

    @Test fun overlapIsLoadBearing() {
        val n = 8; val te = 2000; val frameLimit = 600
        val withOverlap = ready(n, te = te, frameLimit = frameLimit)
        assertTrue(withOverlap.chunksPerPass > 1)
        // Real planner covers everything...
        assertEquals(allWindows(n), coveredWindows(withOverlap.commands, n, te))
        // ...but dropping the overlap (the mutation) misses the windows straddling each cut.
        val mutated = coveredWindows(planNoOverlap(n, te, frameLimit), n, te)
        assertNotEquals(allWindows(n), mutated)
        assertTrue(mutated.size < allWindows(n).size)
    }
}
