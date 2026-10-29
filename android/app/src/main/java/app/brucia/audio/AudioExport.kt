package app.brucia.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Offline export of a recorded device-mic session: the raw PCM is written as a standard WAV, and one
 * PNG is rendered for each visualization type (waveform, spectrum, spectrogram) straight from the
 * samples (not a screen grab), so the images are clean and full-resolution. All rendering is pure and
 * runs off the main thread; [saveAll] is the single entry point used by the Record button.
 */
object AudioExport {

    private const val WAV_BITS = 16
    private const val CHANNELS = 1

    /** Result of a save: the folder and the files written, for the share sheet / toast. */
    data class Result(val dir: File, val files: List<File>)

    /**
     * Write the WAV + the three PNGs for [pcm] (S16LE mono at [sampleRate]) into a fresh timestamped
     * folder under the app's external files dir. Returns the folder and the files written.
     */
    fun saveAll(context: Context, pcm: ByteArray, sampleRate: Int): Result {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null), "recordings/rec_$stamp").apply { mkdirs() }
        val shorts = toShorts(pcm)

        val files = mutableListOf<File>()
        File(dir, "recording.wav").also { writeWav(it, pcm, sampleRate); files += it }
        File(dir, "waveform.png").also { writePng(it, renderWaveform(shorts)); files += it }
        File(dir, "spectrum.png").also { writePng(it, renderSpectrum(shorts, sampleRate)); files += it }
        File(dir, "spectrogram.png").also { writePng(it, renderSpectrogram(shorts, sampleRate)); files += it }
        return Result(dir, files)
    }

    // --- WAV ---------------------------------------------------------------------------------------

    /** Write a canonical 44-byte-header PCM WAV for the little-endian 16-bit mono [pcm] data. */
    fun writeWav(file: File, pcm: ByteArray, sampleRate: Int) {
        val byteRate = sampleRate * CHANNELS * WAV_BITS / 8
        val blockAlign = CHANNELS * WAV_BITS / 8
        val dataLen = pcm.size
        val riffLen = 36 + dataLen
        FileOutputStream(file).use { o ->
            fun str(s: String) = o.write(s.toByteArray(Charsets.US_ASCII))
            fun le32(v: Int) = o.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
                ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()))
            fun le16(v: Int) = o.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
            str("RIFF"); le32(riffLen); str("WAVE")
            str("fmt "); le32(16); le16(1); le16(CHANNELS); le32(sampleRate)
            le32(byteRate); le16(blockAlign); le16(WAV_BITS)
            str("data"); le32(dataLen); o.write(pcm)
        }
    }

    // --- Images ------------------------------------------------------------------------------------

    /** Time-domain waveform as a min/max envelope across the full recording. */
    fun renderWaveform(s: ShortArray, w: Int = 1200, h: Int = 320): Bitmap {
        val bmp = blank(w, h)
        val c = Canvas(bmp)
        grid(c, w, h)
        if (s.isEmpty()) return titled(bmp, "Waveform", "no audio")
        val mid = h / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; strokeWidth = 1.5f }
        val per = (s.size.toDouble() / w).coerceAtLeast(1.0)
        for (x in 0 until w) {
            val lo = (x * per).toInt()
            val hi = ((x + 1) * per).toInt().coerceAtMost(s.size)
            var mn = Short.MAX_VALUE.toInt(); var mx = Short.MIN_VALUE.toInt()
            var i = lo
            while (i < hi) { val v = s[i].toInt(); if (v < mn) mn = v; if (v > mx) mx = v; i++ }
            if (lo >= hi) { mn = 0; mx = 0 }
            val y1 = mid - (mx / 32768f) * (mid * 0.95f)
            val y2 = mid - (mn / 32768f) * (mid * 0.95f)
            c.drawLine(x.toFloat(), y1, x.toFloat(), y2, paint)
        }
        return titled(bmp, "Waveform", "${"%.1f".format(s.size / 16000.0)} s @ 16 kHz")
    }

    /** Average magnitude spectrum (dB) over the whole recording. */
    fun renderSpectrum(s: ShortArray, fs: Int, w: Int = 1200, h: Int = 360): Bitmap {
        val bmp = blank(w, h)
        val c = Canvas(bmp)
        grid(c, w, h)
        val n = 2048
        if (s.size < n) return titled(bmp, "Spectrum", "no audio")
        val bins = n / 2
        val acc = DoubleArray(bins)
        val frames = ((s.size - n) / (n / 2)).coerceAtLeast(1)
        var cnt = 0
        var off = 0
        while (off + n <= s.size) {
            val mag = Fft.magnitude(s, n, off)
            for (k in 0 until bins) acc[k] += mag[k]
            cnt++; off += n / 2
        }
        if (cnt > 0) for (k in 0 until bins) acc[k] /= cnt
        var mx = 1e-9; for (v in acc) if (v > mx) mx = v
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
        for (x in 0 until w) {
            val k = (x.toLong() * bins / w).toInt().coerceIn(0, bins - 1)
            val v = Fft.toDbNorm(acc[k], mx, -70.0)
            val bh = v * (h - 1)
            paint.color = heat(v)
            c.drawRect(x.toFloat(), h - bh, x + 1f, h.toFloat(), paint)
        }
        return titled(bmp, "Spectrum", "0 - ${fs / 2} Hz, averaged")
    }

    /** STFT spectrogram: time on X (left -> right), frequency on Y (top = high). */
    fun renderSpectrogram(s: ShortArray, fs: Int, w: Int = 1200, h: Int = 400): Bitmap {
        val bmp = blank(w, h)
        val n = 1024
        val bins = n / 2
        if (s.size < n) { val c = Canvas(bmp); grid(c, w, h); return titled(bmp, "Spectrogram", "no audio") }
        // One FFT column per output pixel column, spread across the recording.
        val cols = w
        val mags = Array(cols) { DoubleArray(bins) }
        var mx = 1e-9
        val span = (s.size - n).coerceAtLeast(1)
        for (x in 0 until cols) {
            val off = (x.toLong() * span / (cols - 1).coerceAtLeast(1)).toInt().coerceIn(0, span)
            val m = Fft.magnitude(s, n, off)
            mags[x] = m
            for (v in m) if (v > mx) mx = v
        }
        val px = IntArray(w * h)
        for (x in 0 until cols) {
            val m = mags[x]
            for (y in 0 until h) {
                val binIdx = (bins - 1) - (y.toLong() * (bins - 1) / (h - 1)).toInt() // top = high freq
                val v = Fft.toDbNorm(m[binIdx.coerceIn(0, bins - 1)], mx, -65.0)
                px[y * w + x] = heat(v)
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return titled(bmp, "Spectrogram", "0 - ${fs / 2} Hz (top = high)")
    }

    // --- helpers -----------------------------------------------------------------------------------

    private const val BG = 0xFF101014.toInt()
    private const val GRID = 0x22FFFFFF
    private const val ACCENT = 0xFFFF7A1A.toInt()
    private const val TEXT = 0xFFCCCCCC.toInt()

    private fun blank(w: Int, h: Int): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(BG) }

    private fun grid(c: Canvas, w: Int, h: Int) {
        val p = Paint().apply { color = GRID; strokeWidth = 1f }
        for (i in 1 until 10) { val x = w * i / 10f; c.drawLine(x, 0f, x, h.toFloat(), p) }
        for (i in 1 until 6) { val y = h * i / 6f; c.drawLine(0f, y, w.toFloat(), y, p) }
    }

    private fun titled(bmp: Bitmap, title: String, sub: String): Bitmap {
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; textSize = 26f; isFakeBoldText = true }
        c.drawText(title, 16f, 34f, p)
        p.textSize = 20f; p.isFakeBoldText = false; p.color = 0xFF9AA0A6.toInt()
        c.drawText(sub, 16f, 60f, p)
        return bmp
    }

    /** Black -> blue -> cyan -> yellow -> red colormap for a 0..1 magnitude. */
    private fun heat(v: Float): Int {
        val t = v.coerceIn(0f, 1f)
        val r: Int; val g: Int; val b: Int
        when {
            t < 0.25f -> { val u = t / 0.25f; r = 0; g = 0; b = (120 + 135 * u).toInt() }
            t < 0.5f  -> { val u = (t - 0.25f) / 0.25f; r = 0; g = (200 * u).toInt(); b = 255 }
            t < 0.75f -> { val u = (t - 0.5f) / 0.25f; r = (255 * u).toInt(); g = (200 + 55 * u).toInt(); b = (255 * (1 - u)).toInt() }
            else      -> { val u = (t - 0.75f) / 0.25f; r = 255; g = (255 * (1 - u)).toInt(); b = 0 }
        }
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private fun writePng(file: File, bmp: Bitmap) {
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun toShorts(pcm: ByteArray): ShortArray {
        val out = ShortArray(pcm.size / 2)
        var j = 0; var k = 0
        while (k + 1 < pcm.size) {
            out[j++] = ((pcm[k].toInt() and 0xFF) or (pcm[k + 1].toInt() shl 8)).toShort()
            k += 2
        }
        return out
    }
}
