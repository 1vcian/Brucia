package app.brucia.wardrive

import app.brucia.protocol.LinkCountersSnapshot
import org.json.JSONObject

/** Heap or PSRAM figures from a `diag` reply, in bytes. [size] is reported for PSRAM only. */
data class DeviceMemory(val free: Long, val minFree: Long, val largest: Long, val size: Long? = null)

/**
 * Parsed `diag` ACK (firmware with literal `features.diag`; proto/PROTOCOL.md "Recovery
 * diagnostics"). Counters are uint32 since device boot and saturate at [U32_MAX]. Values are
 * device-supplied: anything outside the documented shape makes [fromAck] return null.
 */
data class DeviceDiagnostics(
    val version: Int,
    val reset: String,
    val resetCode: Long,
    val uptimeS: Long,
    val heap: DeviceMemory,
    val psram: DeviceMemory? = null,
    /** `link` counters in protocol order (see [LINK_KEYS]). */
    val link: Map<String, Long>,
    /** ERR replies by status bucket in protocol order (see [ERR_KEYS]). */
    val errors: Map<String, Long>,
    val fmPcmLost: Long? = null,
    val fmPcmActive: Boolean? = null,
    val micPcmDropped: Long? = null,
) {
    fun lines(): List<String> = buildList {
        add("Device reset reason: $reset (code $resetCode), uptime ${uptime(uptimeS)}")
        add("Device heap: ${count(heap.free)} B free, ${count(heap.minFree)} B lowest since boot, " +
            "${count(heap.largest)} B largest block")
        psram?.let {
            add("Device PSRAM: ${count(it.free)} of ${count(it.size ?: 0)} B free, ${count(it.minFree)} B lowest, " +
                "${count(it.largest)} B largest block")
        }
        add("Device link since boot: ${count(link["sessions"])} session(s), ${count(link["rx_frames"])} frames in, " +
            "${count(link["tx_frames"])} frames out")
        add("Device rejected frames: ${count(link["rx_bad_json"])} bad JSON, ${count(link["rx_oversize"])} oversize, " +
            "${count(link["rx_zero_len"])} zero length, ${count(link["rx_alloc_fail"])} alloc failure, " +
            "${count(link["rx_stale"])} stale partial")
        val errs = errors.filterValues { it > 0 }.map { (k, v) -> "$k x${count(v)}" }
        add("Device ERR replies: " + (if (errs.isEmpty()) "none" else errs.joinToString(", ")) +
            " (busy during blocking ops: ${count(link["busy_409"])})")
        fmPcmLost?.let { add("Device FM PCM lost samples: ${count(it)} (since last FM start" + (if (fmPcmActive == true) ", active)" else ")")) }
        micPcmDropped?.let { add("Device mic PCM dropped samples: ${count(it)} (current stream)") }
    }

    companion object {
        const val U32_MAX = 4_294_967_295L
        val LINK_KEYS = listOf("sessions", "rx_frames", "tx_frames", "rx_zero_len", "rx_oversize",
            "rx_alloc_fail", "rx_stale", "rx_bad_json", "busy_409")
        val ERR_KEYS = listOf("400", "404", "409", "413", "4xx", "5xx", "other")
        private val RESET_NAME = Regex("[a-z_]{1,24}")

        /** A saturated counter means "at least this many". */
        fun count(v: Long?): String = when {
            v == null -> "?"
            v >= U32_MAX -> "$v+"
            else -> v.toString()
        }

        fun uptime(seconds: Long): String {
            val d = seconds / 86_400
            val h = (seconds % 86_400) / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            val hms = "%02d:%02d:%02d".format(h, m, s)
            return if (d > 0) "${d}d $hms" else hms
        }

        private fun u32(obj: JSONObject?, key: String): Long? {
            val v = obj?.opt(key) ?: return null
            val n = when (v) { is Int -> v.toLong(); is Long -> v; else -> return null }
            return n.takeIf { it in 0..U32_MAX }
        }

        private fun memory(obj: JSONObject?, withSize: Boolean): DeviceMemory? {
            if (obj == null) return null
            val size = if (withSize) u32(obj, "size") ?: return null else null
            return DeviceMemory(
                free = u32(obj, "free") ?: return null,
                minFree = u32(obj, "min_free") ?: return null,
                largest = u32(obj, "largest") ?: return null,
                size = size,
            )
        }

        /** Parse a `diag` ACK body; null when it is not an ok:true v1 reply of the documented shape. */
        fun fromAck(d: JSONObject?): DeviceDiagnostics? {
            if (d == null || d.opt("ok") != true) return null
            val version = (d.opt("v") as? Int)?.takeIf { it >= 1 } ?: return null
            val reset = (d.opt("reset") as? String)?.takeIf { RESET_NAME.matches(it) } ?: return null
            val linkObj = d.optJSONObject("link") ?: return null
            val errObj = linkObj.optJSONObject("err") ?: return null
            val link = LINK_KEYS.associateWith { u32(linkObj, it) ?: return null }
            val errors = ERR_KEYS.associateWith { u32(errObj, it) ?: return null }
            val psramObj = d.optJSONObject("psram")
            val psram = if (psramObj == null) null else memory(psramObj, withSize = true) ?: return null
            val drops = d.optJSONObject("drops")
            return DeviceDiagnostics(
                version = version,
                reset = reset,
                resetCode = u32(d, "reset_code") ?: return null,
                uptimeS = u32(d, "uptime_s") ?: return null,
                heap = memory(d.optJSONObject("heap"), withSize = false) ?: return null,
                psram = psram,
                link = link,
                errors = errors,
                fmPcmLost = u32(drops, "fm_pcm_lost"),
                fmPcmActive = drops?.opt("fm_pcm_active") as? Boolean,
                micPcmDropped = u32(drops, "mic_pcm_dropped"),
            )
        }
    }
}

/** Device side of the Diagnostics section. */
sealed class DeviceDiagState {
    object NoDevice : DeviceDiagState()
    object Unsupported : DeviceDiagState()
    object NotFetched : DeviceDiagState()
    object Loading : DeviceDiagState()
    data class Failed(val message: String) : DeviceDiagState()
    data class Ready(val diag: DeviceDiagnostics, val fetchedAt: String) : DeviceDiagState()
}

/** Pure helpers for the Diagnostics card, the log export header and the session report. */
object Diagnostics {
    const val UNSUPPORTED = "Device diagnostics: not supported by this firmware"

    /** State after a CAPS handshake: keeps a fetched/in-flight result across the periodic CAPS refresh. */
    fun afterCaps(info: DeviceInfo, current: DeviceDiagState): DeviceDiagState = when {
        !info.features.diag -> DeviceDiagState.Unsupported
        current is DeviceDiagState.Ready || current is DeviceDiagState.Loading -> current
        else -> DeviceDiagState.NotFetched
    }

    fun deviceLines(state: DeviceDiagState): List<String> = when (state) {
        DeviceDiagState.NoDevice -> listOf("Device diagnostics: no device connected since the app started")
        DeviceDiagState.Unsupported -> listOf(UNSUPPORTED)
        DeviceDiagState.NotFetched -> listOf("Device diagnostics: not fetched yet (Refresh in Diagnostics)")
        DeviceDiagState.Loading -> listOf("Device diagnostics: fetching...")
        is DeviceDiagState.Failed -> listOf("Device diagnostics: failed (${state.message})")
        is DeviceDiagState.Ready ->
            listOf("Device diagnostics fetched at ${state.fetchedAt} (counters since device boot)") + state.diag.lines()
    }

    /** Lines for the log export header and the session report (app and device counters). */
    fun exportLines(app: LinkCountersSnapshot, device: DeviceDiagState): List<String> =
        app.lines() + deviceLines(device)
}
