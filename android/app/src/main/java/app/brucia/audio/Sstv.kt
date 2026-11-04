package app.brucia.audio

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * SSTV (Slow-Scan TV) encoder and decoder, pure Kotlin so the whole pipeline unit-tests on the
 * JVM with no hardware or Android context. All audio is 16 kHz mono 16-bit PCM (the format the
 * ESP mic/speaker carry); every bit of DSP runs here on the phone.
 *
 * Supported modes: Robot36 (the primary), Robot72, Martin M1/M2 and Scottie S1/S2.
 * Intended timing reference: JL Barber "Proposal for SSTV Mode Specifications", Dayton 2000.
 * Current regression fixtures are synthetic; external interoperability remains to be verified.
 * Robot36/72 and Martin place their 1200 Hz sync pulse at the start of each line.
 * Scottie is different: its 9 ms sync pulse falls BETWEEN the blue and red scans (its defining
 * quirk), so each line runs separator, green, separator, blue, sync, porch, red, with one extra
 * 9 ms "starting" sync pulse before the first line.
 *
 * Decoding is sync-relative: the instantaneous audio frequency (from a quadrature FM discriminator)
 * is measured per sample, each line's 1200 Hz sync pulse is located, and pixels are sampled at fixed
 * offsets from that pulse - so the FIR group delay cancels and timing stays locked line to line.
 * For Scottie the sync sits mid-line, so its known in-line offset is subtracted to recover the line
 * origin before the scans are sampled.
 */
object Sstv {

    const val FS = 16000

    // SSTV signalling frequencies.
    private const val SYNC_FREQ = 1200.0
    private const val PORCH_FREQ = 1500.0
    private const val SEP_LOW = 1500.0
    private const val SEP_HIGH = 2300.0
    private const val MID_PORCH = 1900.0
    private const val BLACK_FREQ = 1500.0
    private const val WHITE_FREQ = 2300.0

    /** A decoded / source image: packed 0xRRGGBB pixels, row-major. */
    class Image(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
        fun get(x: Int, y: Int): Int = pixels[y * width + x]
        fun set(x: Int, y: Int, rgb: Int) { pixels[y * width + x] = rgb and 0xFFFFFF }
        companion object {
            fun r(rgb: Int) = (rgb ushr 16) and 0xFF
            fun g(rgb: Int) = (rgb ushr 8) and 0xFF
            fun b(rgb: Int) = rgb and 0xFF
            fun rgb(r: Int, g: Int, b: Int) =
                ((r.coerceIn(0, 255)) shl 16) or ((g.coerceIn(0, 255)) shl 8) or b.coerceIn(0, 255)
        }
    }

    enum class Mode(val mWidth: Int, val mHeight: Int, val vis: Int) {
        ROBOT36(320, 240, 8),
        ROBOT72(320, 240, 12),
        MARTIN_M1(320, 256, 44),
        MARTIN_M2(320, 256, 40),
        SCOTTIE_S1(320, 256, 60),
        SCOTTIE_S2(320, 256, 56),
    }

    private enum class Ch { R, G, B, Y, CR, CB }

    private sealed class Seg {
        class Sync(val ms: Double) : Seg()
        class Const(val freq: Double, val ms: Double) : Seg()
        class Scan(val ch: Ch, val ms: Double) : Seg()
    }

    private fun samplesFor(ms: Double): Int = (ms * FS / 1000.0).roundToInt()

    private fun valueToFreq(v: Int): Double = BLACK_FREQ + (v.coerceIn(0, 255) / 255.0) * (WHITE_FREQ - BLACK_FREQ)
    private fun freqToValue(f: Double): Int = (((f - BLACK_FREQ) / (WHITE_FREQ - BLACK_FREQ)) * 255.0).roundToInt().coerceIn(0, 255)

    // --- per-mode line layout -------------------------------------------------

    /** The ordered segments of line [line] for [mode]. Robot36 alternates chroma by parity. */
    private fun segments(mode: Mode, line: Int): List<Seg> = when (mode) {
        Mode.ROBOT36 -> {
            val even = line % 2 == 0
            listOf(
                Seg.Sync(9.0),
                Seg.Const(PORCH_FREQ, 3.0),
                Seg.Scan(Ch.Y, 88.0),
                Seg.Const(if (even) SEP_LOW else SEP_HIGH, 4.5),
                Seg.Const(MID_PORCH, 1.5),
                Seg.Scan(if (even) Ch.CR else Ch.CB, 44.0),
            )
        }
        Mode.ROBOT72 -> listOf(
            Seg.Sync(9.0),
            Seg.Const(PORCH_FREQ, 3.0),
            Seg.Scan(Ch.Y, 138.0),
            Seg.Const(SEP_LOW, 4.5), Seg.Const(MID_PORCH, 1.5), Seg.Scan(Ch.CR, 69.0),
            Seg.Const(SEP_HIGH, 4.5), Seg.Const(MID_PORCH, 1.5), Seg.Scan(Ch.CB, 69.0),
        )
        Mode.MARTIN_M1 -> martin(146.432)
        Mode.MARTIN_M2 -> martin(73.216)
        Mode.SCOTTIE_S1 -> scottie(138.240)
        Mode.SCOTTIE_S2 -> scottie(88.064)
    }

    private fun martin(scan: Double): List<Seg> = listOf(
        Seg.Sync(4.862),
        Seg.Const(PORCH_FREQ, 0.572),
        Seg.Scan(Ch.G, scan), Seg.Const(SEP_LOW, 0.572),
        Seg.Scan(Ch.B, scan), Seg.Const(SEP_LOW, 0.572),
        Seg.Scan(Ch.R, scan), Seg.Const(SEP_LOW, 0.572),
    )

    // Broadcast Scottie (JL Barber spec): colour order G, B, R, with the 9 ms sync pulse BETWEEN
    // the blue and red scans. Per line: separator, green, separator, blue, sync, porch, red. A single
    // 9 ms starting sync pulse precedes line 0 (emitted once in encode(); see hasStartingSync).
    // S1 uses 0.432 ms/pixel (scan = 138.240 ms), S2 uses 0.2752 ms/pixel (scan = 88.064 ms).
    private fun scottie(scan: Double): List<Seg> = listOf(
        Seg.Const(SEP_LOW, 1.5),               // separator before green
        Seg.Scan(Ch.G, scan),
        Seg.Const(SEP_LOW, 1.5),               // separator before blue
        Seg.Scan(Ch.B, scan),
        Seg.Sync(9.0),                         // sync pulse (between blue and red)
        Seg.Const(PORCH_FREQ, 1.5),            // sync porch before red
        Seg.Scan(Ch.R, scan),
    )

    /** Modes that emit a single 9 ms sync pulse before line 0 (Scottie's mid-line sync framing). */
    private fun hasStartingSync(mode: Mode): Boolean =
        mode == Mode.SCOTTIE_S1 || mode == Mode.SCOTTIE_S2

    private fun lineSamples(mode: Mode, line: Int): Int = segments(mode, line).sumOf {
        when (it) { is Seg.Sync -> samplesFor(it.ms); is Seg.Const -> samplesFor(it.ms); is Seg.Scan -> samplesFor(it.ms) }
    }

    private fun syncSamples(mode: Mode): Int =
        samplesFor(segments(mode, 0).filterIsInstance<Seg.Sync>().first().ms)

    /**
     * Samples from the start of a line to the start of its sync pulse. Zero for Robot/Martin (sync
     * at line start); for Scottie it is separator + green + separator + blue, i.e. the sync sits
     * mid-line. The decoder subtracts this to turn a located sync pulse back into the line origin.
     */
    private fun syncOffsetSamples(mode: Mode): Int {
        var off = 0
        for (seg in segments(mode, 0)) {
            when (seg) {
                is Seg.Sync -> return off
                is Seg.Const -> off += samplesFor(seg.ms)
                is Seg.Scan -> off += samplesFor(seg.ms)
            }
        }
        return off
    }

    // --- colour conversion ----------------------------------------------------

    private fun yOf(r: Int, g: Int, b: Int) = (16.0 + (65.738 * r + 129.057 * g + 25.064 * b) / 256.0).roundToInt().coerceIn(0, 255)
    private fun cbOf(r: Int, g: Int, b: Int) = (128.0 + (-37.945 * r - 74.494 * g + 112.439 * b) / 256.0).roundToInt().coerceIn(0, 255)
    private fun crOf(r: Int, g: Int, b: Int) = (128.0 + (112.439 * r - 94.154 * g - 18.285 * b) / 256.0).roundToInt().coerceIn(0, 255)

    private fun yuvToRgb(y: Int, cb: Int, cr: Int): Int {
        val r = ((298.082 * y + 408.583 * cr) / 256.0 - 222.921).roundToInt()
        val g = ((298.082 * y - 100.291 * cb - 208.120 * cr) / 256.0 + 135.576).roundToInt()
        val b = ((298.082 * y + 516.412 * cb) / 256.0 - 276.836).roundToInt()
        return Image.rgb(r, g, b)
    }

    // --- encoding -------------------------------------------------------------

    fun encodeRobot36(img: Image): ShortArray = encode(img, Mode.ROBOT36)

    /** Encode [img] (scaled as needed to the mode resolution) to SSTV audio with a VIS header. */
    fun encode(img: Image, mode: Mode, checkActive: () -> Unit = {}): ShortArray {
        checkActive()
        val w = mode.mWidth
        val h = mode.mHeight
        val src = scale(img, w, h)
        val buf = ShortBuf()
        val osc = Osc()

        writeVis(buf, osc, mode)

        // Scottie's mid-line sync means line 0's green/blue precede its sync pulse; a single 9 ms
        // starting sync pulse is sent first so the first line lines up like every later one.
        if (hasStartingSync(mode)) osc.write(buf, SYNC_FREQ, syncSamples(mode))

        for (line in 0 until h) {
            checkActive()
            for (seg in segments(mode, line)) {
                when (seg) {
                    is Seg.Sync -> osc.write(buf, SYNC_FREQ, samplesFor(seg.ms))
                    is Seg.Const -> osc.write(buf, seg.freq, samplesFor(seg.ms))
                    is Seg.Scan -> writeScan(buf, osc, src, w, line, seg.ch, samplesFor(seg.ms))
                }
            }
        }
        return buf.toArray()
    }

    private fun writeScan(buf: ShortBuf, osc: Osc, img: Image, w: Int, line: Int, ch: Ch, scanSamples: Int) {
        for (x in 0 until w) {
            val n = ((x + 1).toLong() * scanSamples / w).toInt() - (x.toLong() * scanSamples / w).toInt()
            osc.write(buf, valueToFreq(channelValue(img, x, line, ch)), n)
        }
    }

    private fun channelValue(img: Image, x: Int, y: Int, ch: Ch): Int {
        val p = img.get(x, y)
        val r = Image.r(p); val g = Image.g(p); val b = Image.b(p)
        return when (ch) {
            Ch.R -> r; Ch.G -> g; Ch.B -> b
            Ch.Y -> yOf(r, g, b); Ch.CR -> crOf(r, g, b); Ch.CB -> cbOf(r, g, b)
        }
    }

    private fun writeVis(buf: ShortBuf, osc: Osc, mode: Mode) {
        osc.write(buf, MID_PORCH, samplesFor(300.0))   // leader
        osc.write(buf, SYNC_FREQ, samplesFor(10.0))    // break
        osc.write(buf, MID_PORCH, samplesFor(300.0))   // leader
        osc.write(buf, SYNC_FREQ, samplesFor(30.0))    // start bit
        var ones = 0
        for (bit in 0 until 7) {
            val b = (mode.vis ushr bit) and 1
            if (b == 1) ones++
            osc.write(buf, if (b == 1) 1100.0 else 1300.0, samplesFor(30.0))
        }
        osc.write(buf, if (ones % 2 == 1) 1100.0 else 1300.0, samplesFor(30.0)) // even parity
        osc.write(buf, SYNC_FREQ, samplesFor(30.0))    // stop bit
    }

    // --- decoding -------------------------------------------------------------

    fun decodeRobot36(pcm: ShortArray): Image = decode(pcm, Mode.ROBOT36)

    /** Decode [pcm] as [mode]; lines with no audio yet stay black (supports partial buffers). */
    fun decode(pcm: ShortArray, mode: Mode): Image {
        val w = mode.mWidth
        val h = mode.mHeight
        val img = Image(w, h)
        if (pcm.size < FS / 10) return img
        val inst = fmDemod(pcm)
        val lineLen = lineSamples(mode, 0)
        val syncLen = syncSamples(mode)
        val syncOffset = syncOffsetSamples(mode)
        val firstSync = findFirstLine(inst, lineLen, syncLen) ?: return img
        // findFirstLine locks onto the first 1200 Hz pulse. For Scottie that is the starting sync, so
        // line 0 begins one sync length later; for the others it already is the line origin.
        val t0 = if (hasStartingSync(mode)) firstSync + syncLen else firstSync

        // Decoded channel planes, per line.
        val planes = HashMap<Ch, Array<IntArray?>>()
        fun plane(ch: Ch): Array<IntArray?> = planes.getOrPut(ch) { arrayOfNulls(h) }

        val searchW = samplesFor(6.0)
        var decodedLines = 0
        // Predict each line from the previous measured origin, not t0 + line * lineLen: per-segment
        // sample rounding (Scottie S1: 6852 vs a nominal 6851.52 samples/line) otherwise accumulates
        // past the sync search window by the end of a frame from a fractional-timing transmitter.
        var expected = t0.toLong()
        for (line in 0 until h) {
            if (expected + lineSamples(mode, line) >= inst.size) break
            // Lock onto this line's sync pulse (which may sit mid-line) and recover the line origin.
            val syncPos = refineSync(inst, (expected + syncOffset).toInt(), searchW, syncLen)
            val start = syncPos - syncOffset
            expected = start.toLong() + lineSamples(mode, line)
            var pos = start
            for (seg in segments(mode, line)) {
                when (seg) {
                    is Seg.Sync -> pos += samplesFor(seg.ms)
                    is Seg.Const -> pos += samplesFor(seg.ms)
                    is Seg.Scan -> {
                        val n = samplesFor(seg.ms)
                        plane(seg.ch)[line] = sampleScan(inst, pos, n, w)
                        pos += n
                    }
                }
            }
            decodedLines = line + 1
        }
        assemble(img, planes, mode, decodedLines)
        return img
    }

    private fun sampleScan(inst: DoubleArray, pos: Int, scanSamples: Int, w: Int): IntArray {
        val out = IntArray(w)
        val pxW = scanSamples.toDouble() / w
        val halfWin = (pxW / 2.0).toInt().coerceIn(0, 4)
        for (x in 0 until w) {
            val center = pos + ((x + 0.5) * pxW)
            val idx = center.roundToInt()
            var sum = 0.0; var cnt = 0
            for (j in -halfWin..halfWin) {
                val k = idx + j
                if (k in inst.indices) { sum += inst[k]; cnt++ }
            }
            out[x] = if (cnt > 0) freqToValue(sum / cnt) else 0
        }
        return out
    }

    private fun assemble(img: Image, planes: HashMap<Ch, Array<IntArray?>>, mode: Mode, decodedLines: Int) {
        val w = mode.mWidth
        val h = mode.mHeight
        when (mode) {
            Mode.MARTIN_M1, Mode.MARTIN_M2, Mode.SCOTTIE_S1, Mode.SCOTTIE_S2 -> {
                val R = planes[Ch.R]; val G = planes[Ch.G]; val B = planes[Ch.B]
                for (y in 0 until decodedLines) {
                    val r = R?.get(y); val g = G?.get(y); val b = B?.get(y)
                    for (x in 0 until w) {
                        img.set(x, y, Image.rgb(r?.get(x) ?: 0, g?.get(x) ?: 0, b?.get(x) ?: 0))
                    }
                }
            }
            Mode.ROBOT72 -> {
                val Y = planes[Ch.Y]; val CR = planes[Ch.CR]; val CB = planes[Ch.CB]
                for (y in 0 until decodedLines) {
                    val yl = Y?.get(y); val cr = CR?.get(y); val cb = CB?.get(y)
                    for (x in 0 until w) {
                        img.set(x, y, yuvToRgb(yl?.get(x) ?: 0, cb?.get(x) ?: 128, cr?.get(x) ?: 128))
                    }
                }
            }
            Mode.ROBOT36 -> {
                val Y = planes[Ch.Y]; val CR = planes[Ch.CR]; val CB = planes[Ch.CB]
                for (y in 0 until decodedLines) {
                    val yl = Y?.get(y) ?: continue
                    // Even lines carry Cr; odd lines carry Cb. Share across the line pair.
                    val crLine = if (y % 2 == 0) y else y - 1
                    val cbLine = if (y % 2 == 0) (if (y + 1 < h) y + 1 else y - 1) else y
                    val cr = CR?.getOrNull(crLine)
                    val cb = CB?.getOrNull(cbLine)
                    for (x in 0 until w) {
                        img.set(x, y, yuvToRgb(yl[x], cb?.get(x) ?: 128, cr?.get(x) ?: 128))
                    }
                }
            }
        }
    }

    // --- streaming decode (continuous cascade) --------------------------------

    /**
     * Continuous, always-listening SSTV receiver. Feed arbitrary PCM chunks with [feed]; the decoder
     * keeps a small rolling buffer, scans for the VIS header / first sync on the fly, then locks and
     * emits one decoded line at a time (via [drainLines]) as the audio arrives - so it latches onto a
     * transmission whenever it appears, regardless of when listening started, and keeps going. When
     * the 1200 Hz line sync disappears (a gap/silence) it drops back to searching for the next frame.
     *
     * This drives the same per-mode line layout and DSP as [decode], but from a streaming state
     * machine instead of a fixed-length buffer, so lines can be pushed into a scrolling cascade. Pure
     * Kotlin, so it unit-tests on the JVM with no hardware (see AudioDspTest).
     */
    class StreamDecoder(val mode: Mode) {
        private enum class State { SEARCH, LOCKED }

        private val w = mode.mWidth
        private val h = mode.mHeight
        private val syncLen = syncSamples(mode)
        private val syncOffset = syncOffsetSamples(mode)
        private val searchW = samplesFor(6.0)
        private val lead = samplesFor(12.0)   // samples kept before a line origin (demod warm-up)
        private val trail = samplesFor(12.0)  // samples required after a line before decoding it
        private val searchCap = samplesFor(1800.0)
        private val searchMin = samplesFor(1200.0)

        private var buf = ShortArray(0)
        private var absBase = 0L               // absolute index of buf[0]
        private var state = State.SEARCH
        private var nextLineAbs = 0L           // absolute index of the next line origin
        private var frameLine = 0              // line index within the current frame (segment parity)

        // Robot36 pairs two lines: the even line carries Y+Cr, the odd line Y+Cb, and chroma is shared
        // across the pair - so an even line is held until its odd partner arrives, then both emit.
        private var pendingY: IntArray? = null
        private var pendingCr: IntArray? = null

        private val out = ArrayList<IntArray>()

        /** Append a PCM chunk (16 kHz mono S16) and advance the state machine. */
        fun feed(pcm: ShortArray) {
            if (pcm.isEmpty()) return
            buf = buf + pcm
            process()
        }

        /** Remove and return the lines decoded since the last call; each is [w] packed 0xRRGGBB. */
        fun drainLines(): List<IntArray> {
            if (out.isEmpty()) return emptyList()
            val copy = ArrayList(out)
            out.clear()
            return copy
        }

        private fun dropBefore(absIndex: Long) {
            val drop = (absIndex - absBase).toInt()
            if (drop <= 0) return
            val d = drop.coerceAtMost(buf.size)
            buf = buf.copyOfRange(d, buf.size)
            absBase += d
        }

        private fun capSearch() {
            if (buf.size > searchCap) {
                val d = buf.size - searchCap
                buf = buf.copyOfRange(d, buf.size)
                absBase += d
            }
        }

        private fun process() {
            while (true) {
                when (state) {
                    State.SEARCH -> {
                        if (buf.size < searchMin) { capSearch(); return }
                        val inst = fmDemod(buf)
                        val t0 = findFirstLine(inst, lineSamples(mode, 0), syncLen)
                        if (t0 == null) { capSearch(); return }
                        var origin = t0.toLong()
                        if (hasStartingSync(mode)) origin += syncLen
                        nextLineAbs = absBase + origin
                        frameLine = 0
                        pendingY = null; pendingCr = null
                        state = State.LOCKED
                        dropBefore(nextLineAbs - lead)
                    }
                    State.LOCKED -> {
                        val lineLen = lineSamples(mode, frameLine)
                        val rel = (nextLineAbs - absBase).toInt()
                        if (rel < 0) { state = State.SEARCH; continue }
                        if (rel + lineLen + searchW + trail > buf.size) return
                        val inst = fmDemod(buf)
                        val syncPos = refineSync(inst, rel + syncOffset, searchW, syncLen)
                        if (avgFreq(inst, syncPos, syncLen) > 1350.0) {
                            // No genuine 1200 Hz sync here: the transmission ended or dropped out.
                            // Hunt for the next frame instead of decoding noise.
                            state = State.SEARCH
                            dropBefore(nextLineAbs + lineLen)
                            continue
                        }
                        emitLine(inst, syncPos - syncOffset, frameLine)
                        frameLine++
                        if (frameLine >= h) frameLine = 0
                        // Follow the measured sync so rounding drift cannot accumulate (see decode).
                        nextLineAbs = absBase + (syncPos - syncOffset) + lineLen
                        dropBefore(nextLineAbs - lead)
                    }
                }
            }
        }

        /** Sample one scan channel [ch] of [line] starting at line origin [start]; null if absent. */
        private fun channel(inst: DoubleArray, start: Int, ch: Ch, line: Int): IntArray? {
            var pos = start
            for (seg in segments(mode, line)) {
                when (seg) {
                    is Seg.Sync -> pos += samplesFor(seg.ms)
                    is Seg.Const -> pos += samplesFor(seg.ms)
                    is Seg.Scan -> {
                        val n = samplesFor(seg.ms)
                        if (seg.ch == ch) return sampleScan(inst, pos, n, w)
                        pos += n
                    }
                }
            }
            return null
        }

        private fun emitLine(inst: DoubleArray, start: Int, line: Int) {
            when (mode) {
                Mode.MARTIN_M1, Mode.MARTIN_M2, Mode.SCOTTIE_S1, Mode.SCOTTIE_S2 -> {
                    val r = channel(inst, start, Ch.R, line)
                    val g = channel(inst, start, Ch.G, line)
                    val b = channel(inst, start, Ch.B, line)
                    out.add(IntArray(w) { x -> Image.rgb(r?.get(x) ?: 0, g?.get(x) ?: 0, b?.get(x) ?: 0) })
                }
                Mode.ROBOT72 -> {
                    val y = channel(inst, start, Ch.Y, line)
                    val cr = channel(inst, start, Ch.CR, line)
                    val cb = channel(inst, start, Ch.CB, line)
                    out.add(IntArray(w) { x -> yuvToRgb(y?.get(x) ?: 0, cb?.get(x) ?: 128, cr?.get(x) ?: 128) })
                }
                Mode.ROBOT36 -> {
                    val y = channel(inst, start, Ch.Y, line) ?: IntArray(w)
                    if (line % 2 == 0) {
                        pendingY = y
                        pendingCr = channel(inst, start, Ch.CR, line)
                    } else {
                        val cb = channel(inst, start, Ch.CB, line)
                        val cr = pendingCr
                        val yEven = pendingY
                        if (yEven != null) out.add(IntArray(w) { x -> yuvToRgb(yEven[x], cb?.get(x) ?: 128, cr?.get(x) ?: 128) })
                        out.add(IntArray(w) { x -> yuvToRgb(y[x], cb?.get(x) ?: 128, cr?.get(x) ?: 128) })
                        pendingY = null; pendingCr = null
                    }
                }
            }
        }
    }

    // --- sync search ----------------------------------------------------------

    /** Average instantaneous frequency over [len] samples starting at [at] (clamped). */
    private fun avgFreq(inst: DoubleArray, at: Int, len: Int): Double {
        var sum = 0.0; var cnt = 0
        var k = at.coerceAtLeast(0)
        val end = (at + len).coerceAtMost(inst.size)
        while (k < end) { sum += inst[k]; k++; cnt++ }
        return if (cnt > 0) sum / cnt else 9999.0
    }

    /** Refine a sync position: search +/- [window] for the dip closest to 1200 Hz. */
    private fun refineSync(inst: DoubleArray, expected: Int, window: Int, syncLen: Int): Int {
        var best = expected
        var bestAvg = Double.MAX_VALUE
        var c = (expected - window).coerceAtLeast(0)
        val last = (expected + window).coerceAtMost(inst.size - syncLen)
        while (c <= last) {
            val a = avgFreq(inst, c, syncLen)
            if (a < bestAvg) { bestAvg = a; best = c }
            c++
        }
        return best
    }

    /**
     * Find line 0's sync pulse. Only sync pulses (1200 Hz) and the VIS header bits fall below
     * ~1350 Hz - image pixels are always >= 1500 Hz - so the header shows up as one long low run
     * while each line sync is a short isolated run. The VIS block runs 300 ms from its start bit to
     * the first line, so line 0 sits 300 ms past the start of that long run; without a VIS header we
     * fall back to the first sync. The rough position is then locked to the actual pulse by refine.
     */
    private fun findFirstLine(inst: DoubleArray, lineLen: Int, syncLen: Int): Int? {
        val thresh = 1350.0
        val runs = ArrayList<IntArray>() // [start, end)
        var i = 0
        var runStart = -1
        while (i < inst.size - syncLen) {
            if (avgFreq(inst, i, syncLen) < thresh) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) { runs.add(intArrayOf(runStart, i)); runStart = -1 }
            i++
        }
        if (runStart >= 0) runs.add(intArrayOf(runStart, inst.size - syncLen))
        if (runs.isEmpty()) return null
        val visRun = runs.firstOrNull { it[1] - it[0] >= samplesFor(100.0) }
        val rough = if (visRun != null) visRun[0] + samplesFor(300.0) else runs.first()[0]
        if (rough >= inst.size - syncLen) return null
        return refineSync(inst, rough.coerceIn(0, inst.size - syncLen - 1), samplesFor(18.0), syncLen)
    }

    // --- FM discriminator -----------------------------------------------------

    private val lowpass: DoubleArray by lazy { designLowpass(63, 1100.0, FS) }

    /** Per-sample instantaneous frequency (Hz) via quadrature downconversion + FM discrimination. */
    fun fmDemod(pcm: ShortArray): DoubleArray {
        val n = pcm.size
        val fc = MID_PORCH
        val w = 2.0 * PI * fc / FS
        val iArr = DoubleArray(n)
        val qArr = DoubleArray(n)
        for (k in 0 until n) {
            val s = pcm[k] / 32768.0
            iArr[k] = s * cos(w * k)
            qArr[k] = -s * sin(w * k)
        }
        val iF = convolveCentered(iArr, lowpass)
        val qF = convolveCentered(qArr, lowpass)
        val out = DoubleArray(n)
        if (n == 0) return out
        var prev = atan2(qF[0], iF[0])
        out[0] = fc
        for (k in 1 until n) {
            val ph = atan2(qF[k], iF[k])
            var d = ph - prev
            while (d > PI) d -= 2.0 * PI
            while (d < -PI) d += 2.0 * PI
            out[k] = fc + d * FS / (2.0 * PI)
            prev = ph
        }
        return out
    }

    private fun designLowpass(numtaps: Int, cutoffHz: Double, fs: Int): DoubleArray {
        val fc = cutoffHz / fs
        val mid = (numtaps - 1) / 2.0
        val h = DoubleArray(numtaps)
        var sum = 0.0
        for (i in 0 until numtaps) {
            val x = i - mid
            val sinc = if (x == 0.0) 2.0 * fc else sin(2.0 * PI * fc * x) / (PI * x)
            val wnd = 0.54 - 0.46 * cos(2.0 * PI * i / (numtaps - 1))
            h[i] = sinc * wnd
            sum += h[i]
        }
        for (i in h.indices) h[i] /= sum
        return h
    }

    /** FIR convolution with the output aligned to the input (compensates group delay). */
    private fun convolveCentered(x: DoubleArray, h: DoubleArray): DoubleArray {
        val n = x.size
        val m = h.size
        val mid = (m - 1) / 2
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var acc = 0.0
            for (j in 0 until m) {
                val k = i - (j - mid)
                if (k in 0 until n) acc += h[j] * x[k]
            }
            out[i] = acc
        }
        return out
    }

    // --- helpers --------------------------------------------------------------

    /** Nearest-neighbour scale of [img] to [w]x[h] (no-op when already that size). */
    private fun scale(img: Image, w: Int, h: Int): Image {
        if (img.width == w && img.height == h) return img
        val out = Image(w, h)
        for (y in 0 until h) {
            val sy = if (img.height > 0) (y.toLong() * img.height / h).toInt().coerceIn(0, img.height - 1) else 0
            for (x in 0 until w) {
                val sx = if (img.width > 0) (x.toLong() * img.width / w).toInt().coerceIn(0, img.width - 1) else 0
                out.set(x, y, img.get(sx, sy))
            }
        }
        return out
    }

    /** Phase-continuous sine oscillator; avoids clicks at segment boundaries. */
    private class Osc(private val amplitude: Double = 0.55) {
        private var phase = 0.0
        private val amp = amplitude * Short.MAX_VALUE
        fun write(buf: ShortBuf, freq: Double, n: Int) {
            val dp = 2.0 * PI * freq / FS
            for (i in 0 until n) {
                buf.add((amp * sin(phase)).toInt().coerceIn(-32768, 32767).toShort())
                phase += dp
                if (phase > 2.0 * PI) phase -= 2.0 * PI
            }
        }
    }

    /** Amortised-growth short buffer, so a long encode does not box millions of samples. */
    private class ShortBuf {
        private var a = ShortArray(4096)
        private var n = 0
        fun add(s: Short) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = s
        }
        fun toArray(): ShortArray = a.copyOf(n)
    }

    private fun Array<IntArray?>.getOrNull(i: Int): IntArray? = if (i in indices) this[i] else null
}
