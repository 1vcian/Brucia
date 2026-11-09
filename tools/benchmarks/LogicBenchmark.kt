import app.brucia.audio.Dtmf
import app.brucia.audio.Fft
import app.brucia.audio.Sstv
import app.brucia.wifi.Handshake
import app.brucia.wifi.WpaCracker
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.SecretKeyFactory
import kotlin.math.PI
import kotlin.math.sin

// Throughput of existing production code. UI, storage, USB and RF are excluded.
private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private var sink = 0L

private fun measure(name: String, operations: Int, unit: String, action: () -> Unit): JSONObject {
    repeat(3) { action() }
    val elapsed = List(7) {
        val start = System.nanoTime()
        action()
        (System.nanoTime() - start) / 1e6
    }
    val sorted = elapsed.sorted()
    return JSONObject().put("name", name).put("operations_per_sample", operations)
        .put("unit", unit).put("samples_ms", JSONArray(elapsed))
        .put("median_ms", sorted[3]).put("min_ms", sorted.first()).put("max_ms", sorted.last())
        .put("operations_per_second", operations * 1000.0 / sorted[3])
}

fun main() {
    val h = Handshake(Fixture.ssid, Fixture.ap.bytes(), Fixture.sta.bytes(), Fixture.anonce.bytes(),
        Fixture.snonce.bytes(), Fixture.eapol.bytes(), Fixture.mic.bytes(), 2)
    // Independent Python hashlib/hmac fixture checks before the clock starts.
    check(WpaCracker.pmk(Fixture.password, Fixture.ssid).contentEquals(Fixture.pmk.bytes()))
    check(WpaCracker.computeMic(Fixture.password, h).contentEquals(h.mic))
    val pmkid = Fixture.pmkid.bytes()
    check(WpaCracker.pmkid(Fixture.password, h.ssid, h.apMac, h.clMac).contentEquals(pmkid))
    check(WpaCracker.crack(h, sequenceOf("wrong-password", Fixture.password)) == Fixture.password)
    // Every candidate is valid length and absent from the synthetic target: no early match.
    val candidates = List(200) { "candidate-${it.toString().padStart(4, '0')}" }
    val results = JSONArray()
    results.put(measure("WPA2 PBKDF2 + PTK + MIC", candidates.size, "candidates") {
        var tried = 0
        check(WpaCracker.crack(h, candidates.asSequence(), 1, { tried = it }) == null)
        check(tried == candidates.size)
        sink += tried
    })
    results.put(measure("PMKID PBKDF2 + HMAC", candidates.size, "candidates") {
        var tried = 0
        check(WpaCracker.crackPmkid(h.ssid, h.apMac, h.clMac, pmkid, candidates.asSequence(), 1, { tried = it }) == null)
        check(tried == candidates.size)
        sink += tried
    })
    val tone = ShortArray(2048) { (12000 * sin(2 * PI * 1000 * it / 16000)).toInt().toShort() }
    check(Fft.peakBin(Fft.magnitude(tone, 2048)) == 128)
    results.put(measure("FFT magnitude 2048 samples / 16 kHz", 1000, "windows") {
        repeat(1000) { sink += Fft.peakBin(Fft.magnitude(tone, 2048)) }
    })
    val keys = "123A456B789C*0#D"
    val dtmf = keys.flatMap { (Dtmf.synth(it, 16000, 160) + ShortArray(1280)).asIterable() }.toShortArray()
    check(Dtmf.decodeSequence(dtmf, 16000) == keys)
    results.put(measure("DTMF decode 3.84 s / 16 kHz", 100, "recordings") {
        repeat(100) { check(Dtmf.decodeSequence(dtmf, 16000) == keys); sink += keys.length }
    })
    val img = Sstv.Image(320, 240, IntArray(320 * 240) { 0x808080 })
    val pcm = Sstv.encodeRobot36(img)
    val decoded = Sstv.decodeRobot36(pcm)
    check(decoded.width == 320 && decoded.height == 240)
    val center = decoded.get(160, 120)
    check(listOf(Sstv.Image.r(center), Sstv.Image.g(center), Sstv.Image.b(center)).all { it in 100..156 })
    results.put(measure("SSTV Robot36 encode 320x240", 1, "images") {
        val encoded = Sstv.encodeRobot36(img)
        check(encoded.size == pcm.size)
        sink += encoded[encoded.size / 2]
    }.put("audio_seconds", pcm.size.toDouble() / Sstv.FS))
    results.put(measure("SSTV Robot36 decode 320x240", 1, "images") {
        sink += Sstv.decodeRobot36(pcm).get(160, 120)
    }.put("audio_seconds", pcm.size.toDouble() / Sstv.FS))
    println(JSONObject().put("runtime", System.getProperty("java.runtime.version"))
        .put("vm", System.getProperty("java.vm.name"))
        .put("pbkdf2_provider", SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").provider.name)
        .put("warmup_batches", 3).put("measured_batches", 7).put("sink", sink).put("results", results))
}
