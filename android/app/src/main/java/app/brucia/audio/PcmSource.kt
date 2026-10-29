package app.brucia.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Audio helpers for the FM transmitter tool. The firmware expects 16 kHz mono 16-bit PCM
 * chunks (fm_audio_data <base64>), so everything here produces that format.
 *
 * The pure DSP - tone synthesis, downmix + resample, little-endian packing, RMS level -
 * is Android-free and unit-tested. File decode and mic capture use the Android media stack.
 */
object PcmSource {
    const val SAMPLE_RATE = 16000

    /** A pull source of 16 kHz mono 16-bit PCM samples. */
    interface PcmReader {
        /** Fill [out]; return the sample count written, 0 if none is ready yet, or -1 at end. */
        fun read(out: ShortArray): Int
        fun close() {}
    }

    /** Generate [seconds] of a [freqHz] sine as 16 kHz mono samples (finite; for tests/clips). */
    fun tone(freqHz: Double, seconds: Double, amplitude: Double = 0.6): ShortArray {
        val n = (SAMPLE_RATE * seconds).toInt().coerceAtLeast(0)
        val out = ShortArray(n)
        val amp = amplitude.coerceIn(0.0, 1.0) * Short.MAX_VALUE
        for (i in 0 until n) {
            out[i] = (amp * sin(2.0 * PI * freqHz * i / SAMPLE_RATE)).roundToInt().toShort()
        }
        return out
    }

    /** A [PcmReader] that synthesises an endless [freqHz] sine, chunk by chunk, until stopped. */
    fun toneReader(freqHz: Double, amplitude: Double = 0.6): PcmReader = object : PcmReader {
        private var phase = 0L
        private val amp = amplitude.coerceIn(0.0, 1.0) * Short.MAX_VALUE
        override fun read(out: ShortArray): Int {
            for (i in out.indices) {
                out[i] = (amp * sin(2.0 * PI * freqHz * phase / SAMPLE_RATE)).roundToInt().toShort()
                phase++
            }
            return out.size
        }
    }

    /** A [PcmReader] that serves an in-memory sample array in fixed chunks, then ends. */
    fun arrayReader(samples: ShortArray): PcmReader = object : PcmReader {
        private var pos = 0
        override fun read(out: ShortArray): Int {
            if (pos >= samples.size) return -1
            val n = min(out.size, samples.size - pos)
            System.arraycopy(samples, pos, out, 0, n)
            pos += n
            return n
        }
    }

    /** Downmix [channels]-channel interleaved PCM to mono and linear-resample [srcRate] -> 16 kHz. */
    fun downmixAndResample(samples: ShortArray, srcRate: Int, channels: Int): ShortArray {
        if (samples.isEmpty() || srcRate <= 0 || channels <= 0) return ShortArray(0)
        val frames = samples.size / channels
        if (frames == 0) return ShortArray(0)
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var acc = 0
            val base = f * channels
            for (c in 0 until channels) acc += samples[base + c]
            mono[f] = (acc / channels).toShort()
        }
        if (srcRate == SAMPLE_RATE) return mono
        val outLen = ((frames.toLong() * SAMPLE_RATE) / srcRate).toInt()
        if (outLen <= 0) return ShortArray(0)
        val out = ShortArray(outLen)
        val step = srcRate.toDouble() / SAMPLE_RATE
        for (i in 0 until outLen) {
            val pos = i * step
            val i0 = pos.toInt()
            val i1 = min(i0 + 1, frames - 1)
            val frac = pos - i0
            out[i] = (mono[i0] * (1 - frac) + mono[i1] * frac).roundToInt().toShort()
        }
        return out
    }

    /** Pack the first [count] samples of [src] as little-endian 16-bit PCM bytes. */
    fun shortsToLittleEndian(src: ShortArray, count: Int = src.size): ByteArray {
        val n = count.coerceIn(0, src.size)
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = src[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Normalised RMS level (0..1) of the first [count] samples, for the UI meter. */
    fun rmsLevel(src: ShortArray, count: Int = src.size): Float {
        val n = count.coerceIn(0, src.size)
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) { val s = src[i].toDouble(); sum += s * s }
        return (sqrt(sum / n) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Decode an audio file [uri] to 16 kHz mono 16-bit PCM using the platform codecs.
     * Intended for short clips - the whole clip is held in memory. Empty on failure.
     */
    fun decodeFile(context: Context, uri: Uri): ShortArray {
        val extractor = MediaExtractor()
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: return ShortArray(0)
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) return ShortArray(0)
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return ShortArray(0)
            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val out = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            val timeout = 10000L
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(timeout)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, timeout)
                if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        val chunk = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(chunk, 0, info.size)
                        out.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty()) return ShortArray(0)
            val shorts = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return downmixAndResample(shorts, srcRate, channels)
        } catch (_: Exception) {
            return ShortArray(0)
        } finally {
            try { codec?.stop() } catch (e: Exception) { app.brucia.BruciaLog.d("Pcm", "codec stop: ${e.message}") }
            try { codec?.release() } catch (e: Exception) { app.brucia.BruciaLog.d("Pcm", "codec release: ${e.message}") }
            try { extractor.release() } catch (e: Exception) { app.brucia.BruciaLog.d("Pcm", "extractor release: ${e.message}") }
            try { afd.close() } catch (e: Exception) { app.brucia.BruciaLog.d("Pcm", "afd close: ${e.message}") }
        }
    }

    /** Live capture from the phone microphone as 16 kHz mono 16-bit PCM. Needs RECORD_AUDIO. */
    class MicReader : PcmReader {
        private var record: AudioRecord? = null

        /** Open and start the mic. Returns false if unavailable or permission is missing. */
        @SuppressLint("MissingPermission")
        fun start(): Boolean {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return false
            val r = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
            } catch (_: Exception) {
                return false
            }
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
            return try {
                r.startRecording()
                record = r
                true
            } catch (_: Exception) {
                r.release(); false
            }
        }

        override fun read(out: ShortArray): Int {
            val r = record ?: return -1
            val n = r.read(out, 0, out.size)
            return if (n < 0) -1 else n
        }

        override fun close() {
            record?.let {
                try { it.stop() } catch (e: Exception) { app.brucia.BruciaLog.d("Pcm", "record stop: ${e.message}") }
                it.release()
            }
            record = null
        }
    }
}
