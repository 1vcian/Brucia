package app.brucia

import app.brucia.audio.Sstv
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Scottie synchronization against a fractional-time synthetic transmitter.
 *
 * PROVENANCE: synthetic, written for this test; NOT an external known-answer fixture. Segment
 * durations are the nominal Scottie values quoted in Sstv.kt / AudioDspTest (attributed there to
 * JL Barber, Dayton 2000; checked against it in SstvTimingReferenceTest). Unlike
 * AudioDspTest.referenceScottie, which rounds every segment to whole samples (S1: 6852 samples/line
 * instead of the nominal 6851.52), this transmitter places every boundary at round(t * FS) on an
 * exact cumulative time axis, as a sound-card transmitter with a fractional line period does. A
 * decoder that predicts line N at t0 + N * roundedLineLen drifts ~0.5 sample/line off such a signal.
 */
class SstvInteropTest {

    private val fs = 16000

    /** Edge image: black left of x = w/2, white from it, in every channel. */
    private fun edgeImage(w: Int, h: Int) = Sstv.Image(w, h).also { img ->
        for (y in 0 until h) for (x in 0 until w) {
            val v = if (x < w / 2) 0 else 255
            img.set(x, y, Sstv.Image.rgb(v, v, v))
        }
    }

    private fun fractionalScottie(img: Sstv.Image, pixelMs: Double, vis: Int): ShortArray {
        val out = ArrayList<Short>(1 shl 21)
        var phase = 0.0
        var tMs = 0.0
        fun tone(freq: Double, ms: Double) {
            val from = (tMs * fs / 1000.0).roundToInt()
            tMs += ms
            val to = (tMs * fs / 1000.0).roundToInt()
            val dp = 2.0 * PI * freq / fs
            repeat(to - from) {
                out.add((0.55 * Short.MAX_VALUE * sin(phase)).roundToInt().toShort())
                phase += dp
                if (phase > 2.0 * PI) phase -= 2.0 * PI
            }
        }
        fun scan(y: Int, ch: Int) {
            for (x in 0 until img.width) {
                val p = img.get(x, y)
                val v = when (ch) { 0 -> Sstv.Image.g(p); 1 -> Sstv.Image.b(p); else -> Sstv.Image.r(p) }
                tone(1500.0 + v / 255.0 * 800.0, pixelMs)
            }
        }
        tone(1900.0, 300.0); tone(1200.0, 10.0); tone(1900.0, 300.0); tone(1200.0, 30.0)
        var ones = 0
        for (bit in 0 until 7) {
            val b = (vis ushr bit) and 1
            ones += b
            tone(if (b == 1) 1100.0 else 1300.0, 30.0)
        }
        tone(if (ones % 2 == 1) 1100.0 else 1300.0, 30.0)
        tone(1200.0, 30.0)
        tone(1200.0, 9.0) // starting sync
        for (y in 0 until img.height) {
            tone(1500.0, 1.5); scan(y, 0)
            tone(1500.0, 1.5); scan(y, 1)
            tone(1200.0, 9.0)
            tone(1500.0, 1.5); scan(y, 2)
        }
        tone(1500.0, 400.0) // tail so the stream decoder can emit the last lines
        return ShortArray(out.size) { out[it] }
    }

    /** First x where [ch] of row [y] crosses mid-grey; the source edge is at w/2. */
    private fun edgeAt(row: (Int) -> Int, w: Int, ch: (Int) -> Int): Int =
        (0 until w).firstOrNull { ch(row(it)) >= 128 } ?: w

    private fun assertAligned(label: String, w: Int, rows: List<Int>, row: (Int, Int) -> Int) {
        for (y in rows) {
            for ((name, ch) in listOf<Pair<String, (Int) -> Int>>(
                "R" to Sstv.Image::r, "G" to Sstv.Image::g, "B" to Sstv.Image::b)) {
                val e = edgeAt({ x -> row(x, y) }, w, ch)
                assertTrue("$label line $y channel $name edge at $e, expected ${w / 2} +/- 1",
                    abs(e - w / 2) <= 1)
            }
        }
    }

    private fun rowsToCheck(h: Int) = listOf(0, 1, h / 2, h - 2, h - 1)

    private fun batch(mode: Sstv.Mode, pixelMs: Double) {
        val src = edgeImage(mode.mWidth, mode.mHeight)
        val img = Sstv.decode(fractionalScottie(src, pixelMs, mode.vis), mode)
        assertAligned("$mode batch", mode.mWidth, rowsToCheck(mode.mHeight)) { x, y -> img.get(x, y) }
    }

    private fun stream(mode: Sstv.Mode, pixelMs: Double) {
        val src = edgeImage(mode.mWidth, mode.mHeight)
        val pcm = fractionalScottie(src, pixelMs, mode.vis)
        val dec = Sstv.StreamDecoder(mode)
        val lines = ArrayList<IntArray>()
        var i = 0
        while (i < pcm.size) {
            val n = min(1000, pcm.size - i)
            dec.feed(pcm.copyOfRange(i, i + n))
            lines.addAll(dec.drainLines())
            i += n
        }
        assertTrue("$mode stream emitted ${lines.size} lines", lines.size >= mode.mHeight)
        assertAligned("$mode stream", mode.mWidth, rowsToCheck(mode.mHeight)) { x, y -> lines[y][x] }
    }

    @Test fun scottieS1BatchHoldsSyncToLastLine() = batch(Sstv.Mode.SCOTTIE_S1, 0.4320)
    @Test fun scottieS2BatchHoldsSyncToLastLine() = batch(Sstv.Mode.SCOTTIE_S2, 0.2752)
    @Test fun scottieS1StreamHoldsSyncToLastLine() = stream(Sstv.Mode.SCOTTIE_S1, 0.4320)
    @Test fun scottieS2StreamHoldsSyncToLastLine() = stream(Sstv.Mode.SCOTTIE_S2, 0.2752)
}
