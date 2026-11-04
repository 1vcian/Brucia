package app.brucia.audio

import app.brucia.session.CommandSession
import org.json.JSONObject
import java.util.Base64
import java.io.IOException

/** Matches audio.cpp: FM arguments are textual; speaker sr/pcm are fields in d{}, not CLI args. */
class SessionPcmLink(
    private val session: CommandSession,
    private val spec: PcmStreamSpec,
    private val current: () -> Boolean,
    private val timeoutMs: Long = 4_000,
) : PcmStreamLink {
    override fun isCurrent() = current() && !session.isClosed
    private fun checkCurrent() { if (!isCurrent()) throw IOException("Connection changed.") }
    override suspend fun begin(): Pair<Boolean, String> {
        checkCurrent()
        return if (spec.fmKHz != null)
            session.await("fm_audio_begin ${spec.fmKHz} ${if (spec.deviceMic) "mic" else "phone"}", timeoutMs)
        else session.awaitFields("spkr_pcm_begin", JSONObject().put("sr", PcmSource.SAMPLE_RATE), timeoutMs)
    }
    override suspend fun data(samples: ShortArray, count: Int): PcmDataReply {
        checkCurrent()
        require(count in 1..minOf(samples.size, spec.frameSamples))
        val pcm = Base64.getEncoder().encodeToString(PcmSource.shortsToLittleEndian(samples, count))
        if (spec.fmKHz == null) {
            val (ok, message) = session.awaitFields("spkr_pcm_data", JSONObject().put("pcm", pcm), timeoutMs)
            return PcmDataReply(ok, message)
        }
        val (ok, d) = session.awaitJson("fm_audio_data $pcm", timeoutMs)
        return parseFmDataReply(ok, d)
    }
    override suspend fun stop(): Pair<Boolean, String> {
        checkCurrent()
        return session.await(if (spec.fmKHz != null) "fm_audio_stop" else "spkr_pcm_stop", timeoutMs)
    }
}

/**
 * fm_audio_data reply. Reporting firmware adds samples/accepted/free/lost to every ACK; older
 * firmware sends only ok/out, so the counts stay null (delivery unknown). A present but
 * malformed count becomes -1 so the stream rejects it instead of assuming delivery.
 */
internal fun parseFmDataReply(ok: Boolean, d: JSONObject?): PcmDataReply {
    fun count(key: String): Long? {
        if (d == null || !d.has(key)) return null
        val v = d.opt(key) as? Number ?: return -1
        val n = v.toLong()
        return if (n.toDouble() == v.toDouble() && n >= 0) n else -1
    }
    val message = d?.optString("out").orEmpty().ifBlank { d?.optString("msg").orEmpty() }
    fun int(key: String) = count(key)?.let { if (it > Int.MAX_VALUE) -1 else it.toInt() }
    return PcmDataReply(ok, message, int("accepted"), int("samples"), int("free"), count("lost"))
}
