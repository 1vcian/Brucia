package app.brucia

import app.brucia.audio.Sstv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * SSTV timing reference (backlog S09): a transcribed table of published timing values for every
 * mode Sstv supports, checked against the encoder's line layout, the decoder's sample offsets and
 * the encoder's actual audio output.
 *
 * SOURCES. Only numeric facts are transcribed; no document text or code is copied. Each value in
 * the table carries a citation key:
 *
 *  [B p.N]  JL Barber (N7CXI), Silicon Pixels, "Proposal for SSTV Mode Specifications", presented
 *           at the Dayton SSTV forum, 20 May 2000. PDF fetched 2026-11-05 from
 *           https://www.darc.de/fileadmin/filemounts/distrikte/q/ortsverbaende/01/images/SSTV/Dayton_Paper.pdf
 *           (SHA-256 b3747964d8b3599c7f2236717f3e6519b57a5dd86942b7f509478efcbf616d49). Terms: the
 *           author presents the specifications "for use or publication by anyone who finds them
 *           useful"; no formal licence. Data source stated as the Robot 1200C firmware. Pages:
 *           p.2 VIS header, p.3 Scottie, p.4 Martin, p.5 Robot 36, p.6 Robot 72.
 *  [H ch.N] Martin Bruchanov (OK2MNM), "Image Communication on Short Waves" (SSTV Handbook),
 *           (c) 2012, chapter PDFs version 2025-11-13 from https://sstv-handbook.com/download/
 *           (sstv_03.pdf Fig. 3.11 and Table 3.2; sstv_04.pdf Tables 4.3-4.5; sstv_05.pdf mode list
 *           with lines per minute). Terms: copyright notice, offered as a free download, no redistribution
 *           licence stated. Line times below marked [H] are 60000 / lpm.
 *  [M fn]   MMSSTV transmitter source, Makoto Mori (JE3HHT) and Nobuyuki Oba, (c) 2000-2013,
 *           LGPL-3.0-or-later, https://github.com/n5ac/mmsstv commit 8060b5f1 (2013-07-05), file
 *           Main.cpp, functions LineR36, LineR72, LineMRT, LineSCT and the VIS writer. An
 *           implementation, not a specification; used as the second source where the documents
 *           are silent or disagree.
 *
 * KNOWN SOURCE CONFLICT. Robot 72, porch before the B-Y scan: [B p.6] step (8) lists 1500 Hz;
 * [M LineR72] sends 1900 Hz, the same as the porch before R-Y in Robot 72 [B p.6 step (5)] and in
 * Robot 36 [B p.5 steps (5), (11)]. Sstv keeps 1900 Hz (two of three indications, and the dominant
 * transmitter). The porch carries no image data and the decoder does not read its frequency.
 *
 * Martin M2 and Scottie S2: [H ch.4] lists 160 pixels per line; [B p.3, p.4] give 320 pixels at
 * 0.2288 / 0.2752 ms per pixel. Scan durations agree; Sstv uses 320 pixels per [B].
 *
 * TOLERANCES. Durations and frequencies in Sstv's tables must equal the reference to 1 us / 1e-9 Hz
 * (the sources give 0.001 ms precision). Sample positions realised at 16 kHz must sit within half a
 * sample (31.25 us) of the reference time, at every line, with no accumulation; the encoded frame
 * length within one sample. Measured audio frequencies within +/- 50 Hz of the reference.
 *
 * WHAT THIS DOES NOT PROVE. The audio checks run Sstv's own FM discriminator on Sstv's own encoder
 * output. No independently encoded recording and no over-the-air reception is involved; see S09 in
 * the maintenance backlog.
 */
class SstvTimingReferenceTest {

    private val fs = 16000

    private data class RefSeg(val kind: String, val freqHz: Double?, val ms: Double, val channel: String?, val cite: String)

    private data class RefMode(
        val mode: Sstv.Mode,
        val vis: Int, val visCite: String,
        val width: Int, val lines: Int, val sizeCite: String,
        val startingSyncMs: Double?,
        val evenLine: List<RefSeg>,
        val oddLine: List<RefSeg>,
        val lineMs: Double, val lineCite: String,
        val pixelMs: Double?,
    )

    // Channel names follow Sstv: CR = R-Y, CB = B-Y.
    private fun sync(ms: Double, cite: String) = RefSeg("sync", 1200.0, ms, null, cite)
    private fun tone(hz: Double, ms: Double, cite: String) = RefSeg("tone", hz, ms, null, cite)
    private fun scan(ch: String, ms: Double, cite: String) = RefSeg("scan", null, ms, ch, cite)

    private val robot36Even = listOf(
        sync(9.0, "B p.5 (1)"), tone(1500.0, 3.0, "B p.5 (2)"), scan("Y", 88.0, "B p.5 (3)"),
        tone(1500.0, 4.5, "B p.5 (4) even separator"), tone(1900.0, 1.5, "B p.5 (5); M LineR36"),
        scan("CR", 44.0, "B p.5 (6) R-Y on even lines"),
    )
    private val robot36Odd = listOf(
        sync(9.0, "B p.5 (7)"), tone(1500.0, 3.0, "B p.5 (8)"), scan("Y", 88.0, "B p.5 (9)"),
        tone(2300.0, 4.5, "B p.5 (10) odd separator"), tone(1900.0, 1.5, "B p.5 (11); M LineR36"),
        scan("CB", 44.0, "B p.5 (12) B-Y on odd lines"),
    )
    private val robot72 = listOf(
        sync(9.0, "B p.6 (1)"), tone(1500.0, 3.0, "B p.6 (2)"), scan("Y", 138.0, "B p.6 (3)"),
        tone(1500.0, 4.5, "B p.6 (4)"), tone(1900.0, 1.5, "B p.6 (5); M LineR72"),
        scan("CR", 69.0, "B p.6 (6) R-Y"),
        tone(2300.0, 4.5, "B p.6 (7)"),
        tone(1900.0, 1.5, "M LineR72; B p.6 (8) says 1500 Hz - see KNOWN SOURCE CONFLICT"),
        scan("CB", 69.0, "B p.6 (9) B-Y"),
    )

    private fun martin(scanMs: Double, cite: String) = listOf(
        sync(4.862, "B p.4 (1); H ch.4 Table 4.4"), tone(1500.0, 0.572, "B p.4 (2); M LineMRT"),
        scan("G", scanMs, cite), tone(1500.0, 0.572, "B p.4 (4)"),
        scan("B", scanMs, cite), tone(1500.0, 0.572, "B p.4 (6)"),
        scan("R", scanMs, cite), tone(1500.0, 0.572, "B p.4 (8)"),
    )

    private fun scottie(scanMs: Double, cite: String) = listOf(
        tone(1500.0, 1.5, "B p.3 (2)"), scan("G", scanMs, cite),
        tone(1500.0, 1.5, "B p.3 (4)"), scan("B", scanMs, cite),
        sync(9.0, "B p.3 (6); H ch.4 Table 4.5"), tone(1500.0, 1.5, "B p.3 (7)"),
        scan("R", scanMs, cite),
    )

    private val reference = listOf(
        RefMode(Sstv.Mode.ROBOT36, 8, "B p.5; H ch.3 Table 3.2", 320, 240, "B p.5; H ch.5",
            null, robot36Even, robot36Odd, 150.0, "H ch.5 400.000 lpm; B p.5 36 s / 240 lines", null),
        RefMode(Sstv.Mode.ROBOT72, 12, "B p.6; H ch.3 Table 3.2", 320, 240, "B p.6; H ch.5",
            null, robot72, robot72, 300.0, "H ch.5 200.000 lpm; B p.6 72 s / 240 lines", null),
        RefMode(Sstv.Mode.MARTIN_M1, 44, "B p.4; H ch.3 Table 3.2", 320, 256, "B p.4",
            null, martin(146.432, "B p.4 colour scan time"), martin(146.432, "B p.4"),
            60000.0 / 134.3947532, "H ch.4 Table 4.4 134.3947532 lpm", 0.4576),
        RefMode(Sstv.Mode.MARTIN_M2, 40, "B p.4; H ch.3 Table 3.2", 320, 256, "B p.4",
            null, martin(73.216, "B p.4 colour scan time"), martin(73.216, "B p.4"),
            60000.0 / 264.5525975, "H ch.4 Table 4.4 264.5525975 lpm", 0.2288),
        RefMode(Sstv.Mode.SCOTTIE_S1, 60, "B p.3; H ch.3 Table 3.2", 320, 256, "B p.3",
            9.0, scottie(138.240, "B p.3 colour scan time"), scottie(138.240, "B p.3"),
            60000.0 / 140.1148942, "H ch.4 Table 4.5 140.1148942 lpm", 0.4320),
        RefMode(Sstv.Mode.SCOTTIE_S2, 56, "B p.3; H ch.3 Table 3.2", 320, 256, "B p.3",
            9.0, scottie(88.064, "B p.3 colour scan time"), scottie(88.064, "B p.3"),
            60000.0 / 216.0667214, "H ch.4 Table 4.5 216.0667214 lpm", 0.2752),
    )

    /** Calibration header [B p.2; H ch.3 Fig. 3.11; M VIS writer]: (Hz, ms). */
    private fun referenceHeader(vis: Int): List<Pair<Double, Double>> {
        val out = mutableListOf(1900.0 to 300.0, 1200.0 to 10.0, 1900.0 to 300.0, 1200.0 to 30.0)
        var ones = 0
        for (bit in 0 until 7) { // LSB first, 1100 Hz = 1, 1300 Hz = 0
            val b = (vis ushr bit) and 1
            ones += b
            out += (if (b == 1) 1100.0 else 1300.0) to 30.0
        }
        out += (if (ones % 2 == 0) 1300.0 else 1100.0) to 30.0 // even parity: even = 1300, odd = 1100
        out += 1200.0 to 30.0 // stop bit; the mode begins immediately after it [B p.2]
        return out
    }

    private fun refLine(r: RefMode, line: Int) = if (line % 2 == 0) r.evenLine else r.oddLine
    private fun headerMs(r: RefMode) = referenceHeader(r.vis).sumOf { it.second }

    // --- the reference is internally consistent -----------------------------------

    @Test fun referenceSegmentsSumToPublishedLineTimes() {
        for (r in reference) for (line in 0..1) {
            val sum = refLine(r, line).sumOf { it.ms }
            assertEquals("${r.mode} line $line: segments vs ${r.lineCite}", r.lineMs, sum, 0.001)
        }
    }

    @Test fun referenceScanTimesMatchPublishedPixelTimes() {
        for (r in reference) {
            val px = r.pixelMs ?: continue
            for (s in r.evenLine.filter { it.kind == "scan" }) {
                assertEquals("${r.mode} ${s.channel}: ${r.width} x $px ms", px * r.width, s.ms, 1e-6)
            }
        }
    }

    // --- Sstv constants match the reference ----------------------------------------

    @Test fun visCodesAndFrameSizesMatchReference() {
        assertEquals(reference.map { it.mode }.toSet(), Sstv.Mode.values().toSet())
        for (r in reference) {
            assertEquals("${r.mode} VIS (${r.visCite})", r.vis, r.mode.vis)
            assertEquals("${r.mode} width (${r.sizeCite})", r.width, r.mode.mWidth)
            assertEquals("${r.mode} lines (${r.sizeCite})", r.lines, r.mode.mHeight)
        }
    }

    @Test fun visHeaderMatchesReference() {
        for (r in reference) {
            val app = Sstv.visTones(r.mode.vis)
            val ref = referenceHeader(r.vis)
            assertEquals("${r.mode} header length", ref.size, app.size)
            for (i in ref.indices) {
                assertEquals("${r.mode} header tone $i Hz", ref[i].first, app[i].first, 1e-9)
                assertEquals("${r.mode} header tone $i ms", ref[i].second, app[i].second, 1e-6)
            }
        }
        // The decoder places line 0 this far after the start of the VIS start bit.
        val startToImage = referenceHeader(0).drop(3).sumOf { it.second }
        assertEquals(startToImage, Sstv.VIS_START_TO_IMAGE_MS, 1e-9)
    }

    @Test fun lineLayoutMatchesReference() {
        for (r in reference) {
            for (line in 0..1) {
                val app = Sstv.lineLayout(r.mode, line)
                val ref = refLine(r, line)
                assertEquals("${r.mode} line $line segment count", ref.size, app.size)
                for (i in ref.indices) {
                    val a = app[i]; val e = ref[i]
                    val label = "${r.mode} line $line segment $i (${e.cite})"
                    assertEquals("$label kind", e.kind, a.kind)
                    assertEquals("$label channel", e.channel, a.channel)
                    assertEquals("$label ms", e.ms, a.ms, 1e-6)
                    if (e.freqHz == null) assertNull(label, a.freqHz)
                    else assertEquals("$label Hz", e.freqHz, a.freqHz!!, 1e-9)
                }
            }
            val start = Sstv.startingSyncMs(r.mode)
            if (r.startingSyncMs == null) assertNull("${r.mode} has no starting sync", start)
            else {
                assertNotNull("${r.mode} starting sync (B p.3 (1))", start)
                assertEquals(r.startingSyncMs, start!!, 1e-6)
            }
        }
    }

    // --- realised sample positions at 16 kHz ---------------------------------------

    @Test fun decoderOffsetsSitWithinHalfASampleOfReference() {
        for (r in reference) for (line in 0..1) {
            val ref = refLine(r, line)
            val starts = Sstv.decoderSegmentStarts(r.mode, line)
            var t = 0.0
            var syncAt = -1.0
            for (i in ref.indices) {
                val want = t * fs / 1000.0
                assertTrue("${r.mode} line $line segment $i starts at ${starts[i]}, reference $want samples",
                    abs(starts[i] - want) <= 0.5)
                if (ref[i].kind == "sync" && syncAt < 0) syncAt = want
                t += ref[i].ms
            }
            val lineWant = r.lineMs * fs / 1000.0
            val lineGot = Sstv.decoderLineSamples(r.mode, line)
            assertTrue("${r.mode} decoder line $lineGot samples, reference $lineWant", abs(lineGot - lineWant) <= 0.5)
            val syncGot = Sstv.decoderSyncOffsetSamples(r.mode)
            assertTrue("${r.mode} decoder sync offset $syncGot, reference $syncAt", abs(syncGot - syncAt) <= 0.5)
        }
    }

    private fun grey(mode: Sstv.Mode) = Sstv.Image(mode.mWidth, mode.mHeight).also { img ->
        for (y in 0 until mode.mHeight) for (x in 0 until mode.mWidth) img.set(x, y, Sstv.Image.rgb(128, 128, 128))
    }

    /** Reference time (ms from the first header sample) at which [line] begins. */
    private fun lineStartMs(r: RefMode, line: Int): Double {
        var t = headerMs(r) + (r.startingSyncMs ?: 0.0)
        for (l in 0 until line) t += refLine(r, l).sumOf { it.ms }
        return t
    }

    /** Mean demodulated frequency over [lenMs] centred on reference time [centreMs]. */
    private fun meanFreqAt(pcm: ShortArray, centreMs: Double, lenMs: Double): Double {
        val pad = 400
        val c = (centreMs * fs / 1000.0).roundToInt()
        val half = (lenMs * fs / 2000.0).roundToInt()
        val from = (c - half - pad).coerceAtLeast(0)
        val to = (c + half + pad).coerceAtMost(pcm.size)
        val inst = Sstv.fmDemod(pcm.copyOfRange(from, to))
        var sum = 0.0
        for (k in (c - half) until (c + half)) sum += inst[k - from]
        return sum / (2 * half)
    }

    @Test fun encodedAudioFollowsReferenceTimeline() {
        for (r in reference) {
            val pcm = Sstv.encode(grey(r.mode), r.mode)
            val frameMs = lineStartMs(r, r.lines)
            val want = frameMs * fs / 1000.0
            assertTrue("${r.mode} encoded ${pcm.size} samples, reference ${String.format(Locale.ROOT, "%.2f", want)} " +
                "(${String.format(Locale.ROOT, "%.3f", (pcm.size - want) / fs * 1000.0)} ms off over ${r.lines} lines)",
                abs(pcm.size - want) <= 1.0)

            // Every long constant tone of the first, middle and last line, probed at its reference
            // time in the actual encoder output. Tones under 3 ms are too short for the 63-tap
            // discriminator to resolve and are covered by the layout checks above.
            for (line in listOf(0, 1, r.lines / 2, r.lines - 1)) {
                var t = lineStartMs(r, line)
                for (s in refLine(r, line)) {
                    if (s.freqHz != null && s.ms >= 3.0) {
                        val f = meanFreqAt(pcm, t + s.ms / 2, 1.0)
                        assertEquals("${r.mode} line $line ${s.kind} at ${String.format(Locale.ROOT, "%.3f", t)} ms (${s.cite})",
                            s.freqHz, f, 50.0)
                    }
                    t += s.ms
                }
            }
            r.startingSyncMs?.let { ms ->
                assertEquals("${r.mode} starting sync", 1200.0, meanFreqAt(pcm, headerMs(r) + ms / 2, 1.0), 50.0)
            }
        }
    }
}
