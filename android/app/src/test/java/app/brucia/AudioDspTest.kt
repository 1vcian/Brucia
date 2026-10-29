package app.brucia

import app.brucia.audio.Dtmf
import app.brucia.audio.Fft
import app.brucia.audio.Goertzel
import app.brucia.audio.Oscilloscope
import app.brucia.audio.Sstv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * End-to-end verification of the phone-side audio DSP with no hardware: FFT peak location, tone +
 * DTMF detection, oscilloscope trigger/measurements, and a full SSTV encode -> decode round trip.
 */
class AudioDspTest {

    private val fs = 16000

    private fun sine(freq: Double, n: Int, amp: Double = 0.6): ShortArray =
        ShortArray(n) { (amp * Short.MAX_VALUE * sin(2.0 * PI * freq * it / fs)).roundToInt().toShort() }

    // --- FFT ---

    @Test fun fftPeaksAtExpectedBin() {
        val n = 2048
        val freq = 1000.0
        val mag = Fft.magnitude(sine(freq, n), n)
        val peak = Fft.peakBin(mag, 1)
        val expected = (freq * n / fs).roundToInt()
        assertTrue("peak bin $peak should be near $expected", abs(peak - expected) <= 1)
    }

    @Test fun dominantFreqMatchesSine() {
        val f = Fft.dominantFreq(sine(1234.0, 4096), fs, 4096)
        assertEquals(1234.0, f, 15.0)
    }

    // --- Goertzel ---

    @Test fun goertzelConcentratesAtTarget() {
        val samples = sine(941.0, 1024)
        val onTarget = Goertzel.power(samples, fs, 941.0)
        val offTarget = Goertzel.power(samples, fs, 1477.0)
        assertTrue("on-target power $onTarget should dominate off-target $offTarget", onTarget > offTarget * 20)
    }

    // --- Tone / DTMF ---

    @Test fun dtmfSynthDecodesBack() {
        for (d in "0123456789ABCD*#") {
            val tone = Dtmf.synth(d, fs, 80)
            val got = Dtmf.detect(tone, fs)
            assertEquals("digit $d", d, got)
        }
    }

    @Test fun dtmfSequenceDecodes() {
        val digits = "5309"
        val buf = ArrayList<Short>()
        for (d in digits) {
            Dtmf.synth(d, fs, 80).forEach { buf.add(it) }
            repeat(fs * 40 / 1000) { buf.add(0) } // silence gap
        }
        val decoded = Dtmf.decodeSequence(buf.toShortArray(), fs)
        assertEquals(digits, decoded)
    }

    @Test fun dtmfRejectsPureTone() {
        assertEquals(null, Dtmf.detect(sine(1000.0, 1024), fs))
    }

    // --- Oscilloscope ---

    @Test fun scopeTriggerIsStable() {
        val s = sine(500.0, 4000)
        val a = Oscilloscope.window(s, 800, level = 0, rising = true, auto = false)
        val b = Oscilloscope.window(s, 800, level = 0, rising = true, auto = false)
        assertEquals(a.size, b.size)
        // A rising-edge trigger at level 0 starts the window near an upward zero crossing.
        assertTrue("window should start near zero, was ${a[0]}", abs(a[0].toInt()) < 3000)
        assertTrue("window should rise after the trigger", a[10] > a[0])
    }

    @Test fun scopeMeasurements() {
        val amp = 0.5
        val s = sine(440.0, 4000, amp)
        val vpp = Oscilloscope.peakToPeak(s)
        val expectedVpp = (2 * amp * Short.MAX_VALUE)
        assertEquals(expectedVpp, vpp.toDouble(), expectedVpp * 0.05)
        val rms = Oscilloscope.rms(s)
        val expectedRms = amp * Short.MAX_VALUE / Math.sqrt(2.0)
        assertEquals(expectedRms, rms, expectedRms * 0.05)
        val f = Oscilloscope.fundamentalHz(s, fs)
        assertEquals(440.0, f, 5.0)
    }

    // --- SSTV round trip ---

    private fun testImage(w: Int, h: Int): Sstv.Image {
        val img = Sstv.Image(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val left = x < w / 2
            val top = y < h / 2
            val rgb = when {
                top && left -> Sstv.Image.rgb(220, 30, 30)       // red
                top && !left -> Sstv.Image.rgb(30, 200, 40)      // green
                !top && left -> Sstv.Image.rgb(40, 60, 220)      // blue
                else -> {
                    val g = (x * 255 / w)                         // gray ramp
                    Sstv.Image.rgb(g, g, g)
                }
            }
            img.set(x, y, rgb)
        }
        return img
    }

    private fun blockCheck(mode: Sstv.Mode, perChannelTol: Int, avgTol: Double) {
        val w = mode.mWidth
        val h = mode.mHeight
        val src = testImage(w, h)
        val pcm = Sstv.encode(src, mode)
        val out = Sstv.decode(pcm, mode)
        assertEquals(w, out.width)
        assertEquals(h, out.height)
        compareDecoded(mode, src, out, perChannelTol, avgTol)
    }

    /** Assert a decoded image matches its source within tolerance, skipping block seams/edges. */
    private fun compareDecoded(mode: Sstv.Mode, src: Sstv.Image, out: Sstv.Image, perChannelTol: Int, avgTol: Double) {
        val w = mode.mWidth
        val h = mode.mHeight
        // Average absolute per-channel error over interior pixels (skip block seams/edges).
        var sum = 0.0
        var cnt = 0
        for (y in 4 until h - 4) for (x in 4 until w - 4) {
            // skip a margin around the central block seams
            if (abs(x - w / 2) < 6 || abs(y - h / 2) < 6) continue
            val a = src.get(x, y)
            val b = out.get(x, y)
            sum += abs(Sstv.Image.r(a) - Sstv.Image.r(b))
            sum += abs(Sstv.Image.g(a) - Sstv.Image.g(b))
            sum += abs(Sstv.Image.b(a) - Sstv.Image.b(b))
            cnt += 3
        }
        val avg = sum / cnt
        assertTrue("$mode average channel error $avg should be < $avgTol", avg < avgTol)

        // A few representative interior points must match closely per channel.
        val probes = listOf(
            Triple(w / 4, h / 4, "red-quadrant"),
            Triple(3 * w / 4, h / 4, "green-quadrant"),
            Triple(w / 4, 3 * h / 4, "blue-quadrant"),
        )
        for ((px, py, label) in probes) {
            val a = src.get(px, py)
            val b = out.get(px, py)
            assertTrue("$mode $label R off by ${abs(Sstv.Image.r(a) - Sstv.Image.r(b))}", abs(Sstv.Image.r(a) - Sstv.Image.r(b)) <= perChannelTol)
            assertTrue("$mode $label G off by ${abs(Sstv.Image.g(a) - Sstv.Image.g(b))}", abs(Sstv.Image.g(a) - Sstv.Image.g(b)) <= perChannelTol)
            assertTrue("$mode $label B off by ${abs(Sstv.Image.b(a) - Sstv.Image.b(b))}", abs(Sstv.Image.b(a) - Sstv.Image.b(b)) <= perChannelTol)
        }
    }

    @Test fun robot36RoundTrip() {
        // The primary mode. Robot36 subsamples chroma vertically, so allow a modest tolerance.
        blockCheck(Sstv.Mode.ROBOT36, perChannelTol = 40, avgTol = 22.0)
    }

    @Test fun robot72RoundTrip() {
        blockCheck(Sstv.Mode.ROBOT72, perChannelTol = 40, avgTol = 20.0)
    }

    @Test fun martinM1RoundTrip() {
        blockCheck(Sstv.Mode.MARTIN_M1, perChannelTol = 30, avgTol = 16.0)
    }

    @Test fun martinM2RoundTrip() {
        blockCheck(Sstv.Mode.MARTIN_M2, perChannelTol = 30, avgTol = 16.0)
    }

    @Test fun scottieS1RoundTrip() {
        blockCheck(Sstv.Mode.SCOTTIE_S1, perChannelTol = 30, avgTol = 16.0)
    }

    @Test fun scottieS2RoundTrip() {
        blockCheck(Sstv.Mode.SCOTTIE_S2, perChannelTol = 30, avgTol = 16.0)
    }

    // --- Scottie broadcast accuracy ---
    //
    // The round-trip tests above only prove Brucia agrees with itself. These tests build a Scottie
    // waveform straight from the published spec (JL Barber "Proposal for SSTV Mode Specifications",
    // Dayton 2000) with an INDEPENDENT reference encoder, then let Sstv.decode() recover the image.
    // They prove the decoder locks to genuine Scottie timing - sync pulse mid-line, between blue and
    // red, with a leading starting sync - as a frame from another station/app would arrive. If the
    // decoder expected the sync at the line start (the old self-consistent framing), it would fail to
    // lock on this waveform and the image check would fail.

    /** Grow-on-demand PCM buffer, so the reference encoder does not box ~1.8M samples. */
    private class PcmBuf {
        private var a = ShortArray(1 shl 16)
        private var n = 0
        fun add(s: Short) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = s }
        fun toArray(): ShortArray = a.copyOf(n)
    }

    /**
     * Reference Scottie encoder written directly from the spec constants (no shared code with Sstv):
     * sync 9 ms @ 1200 Hz, separator/porch 1.5 ms @ 1500 Hz, luma 1500-2300 Hz, colour order G,B,R,
     * and - crucially - the sync pulse placed BETWEEN blue and red, after a single starting sync.
     */
    private fun referenceScottie(img: Sstv.Image, pixelMs: Double, vis: Int): ShortArray {
        val buf = PcmBuf()
        var phase = 0.0
        fun sf(ms: Double) = (ms * fs / 1000.0).roundToInt()
        fun tone(freq: Double, n: Int) {
            val dp = 2.0 * PI * freq / fs
            repeat(n) {
                buf.add((0.55 * Short.MAX_VALUE * sin(phase)).roundToInt().toShort())
                phase += dp
                if (phase > 2.0 * PI) phase -= 2.0 * PI
            }
        }
        val freqSync = 1200.0
        val freqBlack = 1500.0
        val freqWhite = 2300.0
        val sep = 1500.0
        val scanSamples = sf(320 * pixelMs)
        fun lumaFreq(v: Int) = freqBlack + v.coerceIn(0, 255) / 255.0 * (freqWhite - freqBlack)
        fun scan(ch: Int, y: Int) {
            for (x in 0 until img.width) {
                val n = ((x + 1).toLong() * scanSamples / img.width).toInt() - (x.toLong() * scanSamples / img.width).toInt()
                val p = img.get(x, y)
                val v = when (ch) { 0 -> Sstv.Image.g(p); 1 -> Sstv.Image.b(p); else -> Sstv.Image.r(p) }
                tone(lumaFreq(v), n)
            }
        }

        // VIS header (leader / break / leader, start bit, 7 data bits LSB-first, even parity, stop).
        tone(1900.0, sf(300.0)); tone(freqSync, sf(10.0)); tone(1900.0, sf(300.0))
        tone(freqSync, sf(30.0))
        var ones = 0
        for (bit in 0 until 7) {
            val b = (vis ushr bit) and 1
            if (b == 1) ones++
            tone(if (b == 1) 1100.0 else 1300.0, sf(30.0))
        }
        tone(if (ones % 2 == 1) 1100.0 else 1300.0, sf(30.0))
        tone(freqSync, sf(30.0))

        // Starting sync pulse, then per line: separator, green, separator, blue, sync, porch, red.
        tone(freqSync, sf(9.0))
        for (y in 0 until img.height) {
            tone(sep, sf(1.5)); scan(0, y)
            tone(sep, sf(1.5)); scan(1, y)
            tone(freqSync, sf(9.0))
            tone(sep, sf(1.5)); scan(2, y)
        }
        return buf.toArray()
    }

    private fun scottieBroadcast(mode: Sstv.Mode, pixelMs: Double) {
        val src = testImage(mode.mWidth, mode.mHeight)
        val pcm = referenceScottie(src, pixelMs, mode.vis)
        val out = Sstv.decode(pcm, mode)
        assertEquals(mode.mWidth, out.width)
        assertEquals(mode.mHeight, out.height)
        compareDecoded(mode, src, out, perChannelTol = 30, avgTol = 16.0)
    }

    @Test fun scottieS1BroadcastDecodes() {
        // 0.4320 ms/pixel (Scottie 1), spec-exact waveform from referenceScottie.
        scottieBroadcast(Sstv.Mode.SCOTTIE_S1, pixelMs = 0.4320)
    }

    @Test fun scottieS2BroadcastDecodes() {
        // 0.2752 ms/pixel (Scottie 2).
        scottieBroadcast(Sstv.Mode.SCOTTIE_S2, pixelMs = 0.2752)
    }

    /** Grayscale frame: black left half, white right half, with a sharp vertical edge at x = w/2. */
    private fun edgeImage(w: Int, h: Int): Sstv.Image {
        val img = Sstv.Image(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = if (x < w / 2) 0 else 255
            img.set(x, y, Sstv.Image.rgb(v, v, v))
        }
        return img
    }

    private fun broadcastEdgeCheck(mode: Sstv.Mode, pixelMs: Double) {
        val w = mode.mWidth
        val h = mode.mHeight
        val src = edgeImage(w, h)
        val out = Sstv.decode(referenceScottie(src, pixelMs, mode.vis), mode)
        val y = h / 2
        // Scottie's sync falls between blue and red, so a decoder that assumed the sync at the line
        // start would read green and blue ~9 ms late - tens of pixels - relative to red. On this
        // sharp edge that shift turns the pixel just left of the edge cyan (green+blue, no red) and
        // the one just right of it yellow/red. Correct mid-line-sync decoding keeps all three
        // channels aligned, so both samples stay neutral grey.
        val pL = out.get(w / 2 - 10, y) // left of edge: black in every channel
        assertTrue("left-of-edge should be black, was ${pL.toString(16)}",
            Sstv.Image.r(pL) < 50 && Sstv.Image.g(pL) < 50 && Sstv.Image.b(pL) < 50)
        val pR = out.get(w / 2 + 10, y) // right of edge: white in every channel
        assertTrue("right-of-edge should be white, was ${pR.toString(16)}",
            Sstv.Image.r(pR) > 205 && Sstv.Image.g(pR) > 205 && Sstv.Image.b(pR) > 205)
    }

    @Test fun scottieS1BroadcastAlignsChannels() {
        broadcastEdgeCheck(Sstv.Mode.SCOTTIE_S1, pixelMs = 0.4320)
    }

    @Test fun scottieS2BroadcastAlignsChannels() {
        broadcastEdgeCheck(Sstv.Mode.SCOTTIE_S2, pixelMs = 0.2752)
    }

    @Test fun scottieEncoderPlacesSyncMidLine() {
        // Prove Sstv.encode() itself emits a real Scottie waveform: the 9 ms sync sits mid-line
        // (between blue and red), not at the line start, and a starting sync precedes line 0.
        val mode = Sstv.Mode.SCOTTIE_S1
        val pixelMs = 0.4320
        val src = testImage(mode.mWidth, mode.mHeight)
        val inst = Sstv.fmDemod(Sstv.encode(src, mode))
        val sf = { ms: Double -> (ms * fs / 1000.0).roundToInt() }
        // VIS length matches writeVis: two 300 ms leaders, one 10 ms break, ten 30 ms bit cells.
        val visLen = 2 * sf(300.0) + sf(10.0) + 10 * sf(30.0)
        val lineStart0 = visLen + sf(9.0)                      // line 0 begins after the starting sync
        val scanSamples = sf(320 * pixelMs)
        val syncOffset = sf(1.5) + scanSamples + sf(1.5) + scanSamples
        fun avg(at: Int, n: Int): Double { var s = 0.0; for (k in at until at + n) s += inst[k]; return s / n }

        // A genuine 9 ms starting sync precedes line 0 (sampled at its core to avoid edge settling).
        assertTrue("starting sync should be ~1200 Hz", avg(visLen + sf(2.0), sf(5.0)) < 1300.0)
        // Line 0 starts with a 1500 Hz separator then the green scan - NOT a sync pulse.
        assertTrue("line start should be a separator (>=1400 Hz), not a sync", avg(lineStart0, sf(3.0)) > 1400.0)
        // The sync pulse sits mid-line, between the blue and red scans.
        val midSync = lineStart0 + syncOffset
        assertTrue("mid-line window should be the 1200 Hz sync", avg(midSync + sf(2.0), sf(5.0)) < 1300.0)
        // Just before that sync is the tail of the blue scan (luma, >=1500 Hz), not another sync.
        assertTrue("region before mid-line sync should be a scan, not a sync", avg(midSync - sf(3.0), sf(2.0)) > 1450.0)
    }

    @Test fun decodeEmptyIsSafe() {
        val out = Sstv.decodeRobot36(ShortArray(100))
        assertEquals(320, out.width)
        assertFalse(out.pixels.isEmpty())
    }

    // --- Vectorscope (XY) ---

    @Test fun delayEmbedQuarterPeriodIsOrthogonal() {
        // A sine delayed by a quarter period is its 90-degree partner, so the delay embedding (X=s[n],
        // Y=s[n+d]) traces a circle, not a line: the two are decorrelated. This is the mono "draw with
        // sound" phase plot.
        val freq = 500.0
        val quarter = (fs / freq / 4.0).roundToInt()
        val s = sine(freq, 4096)
        val (x, y) = Oscilloscope.delayEmbed(s, quarter)
        assertEquals(s.size - quarter, x.size)
        assertEquals(x.size, y.size)
        var dot = 0.0; var ex = 0.0; var ey = 0.0
        for (i in x.indices) { dot += x[i] * y[i]; ex += x[i].toDouble() * x[i]; ey += y[i].toDouble() * y[i] }
        val corr = dot / (Math.sqrt(ex) * Math.sqrt(ey))
        assertTrue("quarter-period embedding should be near-orthogonal, corr=$corr", abs(corr) < 0.1)
        assertTrue("peakAbs should track amplitude", Oscilloscope.peakAbs(s) > 15000)
    }

    @Test fun pairChannelsTruncatesToShorter() {
        val (x, y) = Oscilloscope.pairChannels(ShortArray(100), ShortArray(60))
        assertEquals(60, x.size)
        assertEquals(60, y.size)
    }

    // --- SSTV streaming (continuous cascade) round trip ---

    /** Feed [pcm] to a [Sstv.StreamDecoder] in small chunks and assemble the cascaded lines. */
    private fun streamDecode(mode: Sstv.Mode, pcm: ShortArray, chunk: Int = 1000): Sstv.Image {
        val dec = Sstv.StreamDecoder(mode)
        val lines = ArrayList<IntArray>()
        var i = 0
        while (i < pcm.size) {
            val n = min(chunk, pcm.size - i)
            dec.feed(pcm.copyOfRange(i, i + n))
            lines.addAll(dec.drainLines())
            i += n
        }
        val out = Sstv.Image(mode.mWidth, mode.mHeight)
        for (y in 0 until min(mode.mHeight, lines.size)) {
            val ln = lines[y]
            for (x in 0 until mode.mWidth) out.set(x, y, ln[x])
        }
        return out
    }

    private fun streamCheck(mode: Sstv.Mode, perChannelTol: Int, avgTol: Double) {
        val src = testImage(mode.mWidth, mode.mHeight)
        // Trailing silence so the final lines have the margin the streaming decoder needs to emit them.
        val pcm = Sstv.encode(src, mode) + ShortArray(fs * 400 / 1000)
        val out = streamDecode(mode, pcm)
        compareDecoded(mode, src, out, perChannelTol, avgTol)
    }

    @Test fun sstvStreamRobot36() = streamCheck(Sstv.Mode.ROBOT36, perChannelTol = 40, avgTol = 22.0)
    @Test fun sstvStreamRobot72() = streamCheck(Sstv.Mode.ROBOT72, perChannelTol = 40, avgTol = 20.0)
    @Test fun sstvStreamMartinM1() = streamCheck(Sstv.Mode.MARTIN_M1, perChannelTol = 30, avgTol = 16.0)
    @Test fun sstvStreamScottieS1() = streamCheck(Sstv.Mode.SCOTTIE_S1, perChannelTol = 30, avgTol = 16.0)

    @Test fun sstvStreamLatchesRegardlessOfStart() {
        // The continuous decoder must lock onto a transmission that begins well AFTER listening started,
        // not only when the capture happens to align with the signal. Prepend ~800 ms of an unrelated
        // tone, then the real Robot36 frame: the stream must still find the VIS/sync and decode it.
        val mode = Sstv.Mode.ROBOT36
        val src = testImage(mode.mWidth, mode.mHeight)
        val lead = ShortArray(fs * 800 / 1000) { (0.5 * Short.MAX_VALUE * sin(2.0 * PI * 2000.0 * it / fs)).roundToInt().toShort() }
        val pcm = lead + Sstv.encode(src, mode) + ShortArray(fs * 400 / 1000)
        val out = streamDecode(mode, pcm)
        compareDecoded(mode, src, out, perChannelTol = 40, avgTol = 22.0)
    }
}
