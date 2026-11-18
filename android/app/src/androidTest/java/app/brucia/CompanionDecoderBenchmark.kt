package app.brucia

import android.os.Build
import android.os.Bundle
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.protocol.PROTO_VERSION
import app.brucia.transport.Transport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.locks.LockSupport

/**
 * Opt-in ART probe, not a performance gate. Runs the production decoder and Android JSON parser
 * on the instrumentation worker, without an Activity, USB, radios or network. Runtime statistics
 * are approximate process-wide counters (including parser/harness/other threads), not exclusively
 * decoder allocations. GC duration is collector work, not a measurement of UI pause/jank.
 *
 * Pass decoderBenchmark=true and select this class alone; output is in the instrumentation result
 * and target filesDir (Gradle may uninstall the app after testing, deleting that second copy).
 * No forced GC, timing assertion or production buffer change. See TESTS.md for reproduction.
 */
@RunWith(AndroidJUnit4::class)
class CompanionDecoderBenchmark {
    private data class Workload(
        val name: String,
        val frame: ByteArray,
        val chunks: IntArray,
        val frames: Int,
        val intervalNs: Long = 0,
    )

    /** Prebuilt repeated frames; bulk copies do not allocate or perform per-byte modulo work. */
    private class Pipe(private val workload: Workload, count: Int) : Transport {
        private val total = workload.frame.size * count
        private var offset = 0
        private var reads = 0
        private var releasedFrame = -1
        private var startNs = 0L
        override val isOpen get() = offset < total
        override fun close() = Unit
        override fun write(bytes: ByteArray) = error("Read-only benchmark transport")

        override fun read(buf: ByteArray, timeoutMs: Int): Int {
            val frameSize = workload.frame.size
            if (workload.intervalNs > 0) {
                val frameIndex = offset / frameSize
                if (frameIndex != releasedFrame) {
                    if (releasedFrame < 0) startNs = System.nanoTime()
                    val due = startNs + frameIndex * workload.intervalNs
                    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
                    while (true) {
                        val now = System.nanoTime()
                        if (now >= due) break
                        if (now >= deadline) return 0
                        LockSupport.parkNanos(minOf(due, deadline) - now)
                    }
                    releasedFrame = frameIndex
                }
            }
            var n = minOf(buf.size, workload.chunks[reads++ % workload.chunks.size], total - offset)
            // Paced data exposes one frame at a time; saturated data may coalesce frame boundaries.
            if (workload.intervalNs > 0) n = minOf(n, frameSize - offset % frameSize)
            var copied = 0
            while (copied < n) {
                val source = (offset + copied) % frameSize
                val length = minOf(n - copied, frameSize - source)
                workload.frame.copyInto(buf, copied, source, source + length)
                copied += length
            }
            offset += n
            return n
        }
    }

    private fun frame(payload: String): ByteArray {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        return ByteArray(4) { (bytes.size ushr (8 * it)).toByte() } + bytes
    }

    private fun paddedFrame(size: Int): ByteArray {
        val prefix = "{\"v\":$PROTO_VERSION,\"t\":${Kind.DATA},\"id\":7,\"d\":{\"padding\":\""
        val suffix = "\"}}"
        return frame(prefix + "x".repeat(size - prefix.length - suffix.length) + suffix)
    }

    private val statKeys = listOf(
        "art.gc.bytes-allocated", "art.gc.gc-count", "art.gc.gc-time",
        "art.gc.blocking-gc-count", "art.gc.blocking-gc-time",
    )

    private fun stats(): LongArray {
        val values = Debug.getRuntimeStats()
        return LongArray(statKeys.size) { i ->
            requireNotNull(values[statKeys[i]]?.toLongOrNull()) { "Missing ART stat ${statKeys[i]}" }
        }
    }

    private fun trial(workload: Workload, count: Int): JSONObject {
        val client = CompanionClient(Pipe(workload, count))
        val latencies = LongArray(count)
        val before = stats()
        val started = System.nanoTime()
        repeat(count) { index ->
            val start = System.nanoTime()
            val message = requireNotNull(client.readMessage(10_000)) { "Missing frame $index" }
            latencies[index] = System.nanoTime() - start
            check(message.getInt("v") == PROTO_VERSION)
            val data = message.getJSONObject("d")
            if (workload.name.startsWith("wifi_ap")) {
                check(message.getInt("id") == 0 && message.getInt("t") == Kind.EVENT)
                check(data.getString("ev") == "wifi.ap")
                check(data.getJSONObject("data").getString("ssid") == "Benchmark AP")
            } else {
                check(message.getInt("id") == 7 && message.getInt("t") == Kind.DATA)
                check(data.getString("padding").length == workload.frame.size - paddedFrameOverhead)
            }
        }
        val elapsed = System.nanoTime() - started
        val after = stats()
        check(!client.takeCliLeftCompanion())
        val counters = client.counters.snapshot()
        check(counters.framesReceived == count.toLong())
        check(counters.malformedFrames == 0L && counters.invalidLengthFrames == 0L && counters.noiseBytes == 0L)
        latencies.sort()
        return JSONObject().apply {
            put("frames", count)
            put("elapsed_ns", elapsed)
            put("wire_bytes_per_second", workload.frame.size.toDouble() * count * 1e9 / elapsed)
            put("read_message_p50_ns", latencies[(count - 1) / 2])
            put("read_message_p95_ns", latencies[(count * 95 + 99) / 100 - 1])
            put("read_message_max_ns", latencies.last())
            statKeys.forEachIndexed { i, key ->
                val delta = after[i] - before[i]
                check(delta >= 0) { "ART counter decreased: $key" }
                put(key, delta)
            }
        }
    }

    private val paddedFrameOverhead: Int = paddedFrame(128).size -
        JSONObject(String(paddedFrame(128), 4, 128, Charsets.UTF_8)).getJSONObject("d").getString("padding").length

    @Test
    fun profileDecoderOnArt() {
        assumeTrue("Opt-in benchmark", InstrumentationRegistry.getArguments().getString("decoderBenchmark") == "true")
        val workloads = buildList {
            for (size in listOf(128, 1024, 8192)) for (chunk in listOf(1, 64, 4096)) {
                add(Workload("padding_$size", paddedFrame(size), intArrayOf(chunk), if (chunk == 1) 20 else 200))
            }
            // Shape mirrors sendWifiApEvent; chosen cadence is synthetic, not a captured USB trace.
            val event = frame("{\"v\":$PROTO_VERSION,\"t\":${Kind.EVENT},\"id\":0,\"d\":{\"ev\":\"wifi.ap\",\"data\":{\"bssid\":\"02:00:00:00:00:01\",\"ssid\":\"Benchmark AP\",\"ch\":6,\"rssi\":-55,\"enc\":\"WPA2\",\"ts\":123456}}}")
            add(Workload("wifi_ap_saturated", event, intArrayOf(64, 512, 4096), 1000))
            add(Workload("wifi_ap_50fps", event, intArrayOf(64, 512, 4096), 100, 20_000_000L))
            add(Workload("wifi_ap_50fps_fragmented", event, intArrayOf(1), 100, 20_000_000L))
        }
        val rows = JSONArray()
        for (workload in workloads) {
            // Warm the actual workload twice; do not force collection between trials.
            repeat(2) { trial(workload, if (workload.intervalNs > 0) 20 else workload.frames) }
            val samples = JSONArray()
            repeat(3) { samples.put(trial(workload, workload.frames)) }
            rows.put(JSONObject().apply {
                put("workload", workload.name)
                put("payload_bytes", workload.frame.size - 4)
                put("read_chunks", JSONArray(workload.chunks.toList()))
                put("interval_ns", workload.intervalNs)
                put("samples", samples)
            })
        }
        val report = JSONObject().apply {
            put("scope", "Synthetic Android ART decoder/JSON/harness probe; no USB, UI, radio or phone throughput claim")
            put("allocation_scope", "Approximate process-wide ART counters; includes observers and other threads")
            put("latency_scope", "readMessage wall time, including transport pacing; GC duration is not UI pause time")
            put("build_fingerprint", Build.FINGERPRINT)
            put("model", Build.MODEL)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("vm_version", System.getProperty("java.vm.version"))
            put("app_version", BuildConfig.VERSION_NAME)
            put("build_type", BuildConfig.BUILD_TYPE)
            put("warmup_trials", 2)
            put("measured_trials", 3)
            put("rows", rows)
        }
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "decoder-benchmark.json")
            .writeText(report.toString(2) + "\n")
        InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
            putString("decoder_report", report.toString())
        })
    }
}
