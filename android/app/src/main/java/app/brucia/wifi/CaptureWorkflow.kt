package app.brucia.wifi

import app.brucia.files.FileEntry
import app.brucia.session.CommandRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.security.MessageDigest

/** One snapshot of a connection. Implementations must never switch to a reconnected device. */
interface CaptureLink {
    val commands: CommandRunner
    suspend fun list(): List<FileEntry>
    suspend fun read(path: String): ByteArray
}

data class HandshakeFlow(val active: Boolean = false, val step: String = "idle",
    val message: String = "", val bssid: String = "", val ssid: String = "")
data class CapturedTarget(val handshake: Handshake?, val pmkid: PmkidTarget?, val path: String, val bytes: ByteArray)

/** Existing capture commands, with fail-closed sequencing and owner-only import. */
class CaptureWorkflow(
    private val scope: CoroutineScope,
    private val connect: () -> CaptureLink?,
    private val onLoaded: (CapturedTarget) -> Unit,
    private val parse: (ByteArray, String) -> Pair<Handshake?, PmkidTarget?> = { bytes, ssid ->
        val h = HandshakeParser.parse(bytes, ssid.ifEmpty { null })
        h to if (h == null) HandshakeParser.parsePmkid(bytes, ssid.ifEmpty { null }) else null
    },
    private val blockedReason: () -> String? = { null },
) {
    private val state = MutableStateFlow(HandshakeFlow())
    val status: StateFlow<HandshakeFlow> = state.asStateFlow()
    private var epoch = 0L
    private var job: Job? = null

    @Synchronized fun reset() { epoch++; job?.cancel(); state.value = HandshakeFlow() }
    @Synchronized fun stop(message: String = "Import cancelled; device capture may continue until its timeout.") {
        epoch++; job?.cancel()
        if (state.value.active) state.value = state.value.copy(active = false, step = "failed", message = message)
    }

    @Synchronized fun start(bssid: String, channel: Int, ssid: String, seconds: Int, pmkid: Boolean) {
        epoch++; val owner = epoch; job?.cancel()
        val mac = bssid.replace(":", "").replace("-", "").uppercase()
        if (!Regex("[0-9A-F]{12}").matches(mac) || channel !in 1..14 || ssid.any { it == '\n' || it == '\r' || it == '\u0000' }) {
            state.value = HandshakeFlow(step = "failed", message = "Invalid capture parameters."); return
        }
        blockedReason()?.let {
            state.value = HandshakeFlow(step = "failed", message = it); return
        }
        val link = connect()
        if (link == null) { state.value = HandshakeFlow(step = "failed", message = "Connect the device over USB first."); return }
        val address = mac.chunked(2).joinToString(":")
        val duration = if (pmkid) seconds.coerceIn(5, 120) else if (seconds < 5) 30 else seconds.coerceAtMost(120)
        state.value = HandshakeFlow(true, "prepare", "Checking existing captures...", bssid, ssid)
        job = scope.launch {
            try {
                withTimeout(600_000) {
                    suspend fun checkOwner() {
                        currentCoroutineContext().ensureActive()
                        synchronized(this@CaptureWorkflow) { if (owner != epoch) throw CancellationException() }
                    }
                    suspend fun read(entry: FileEntry): ByteArray {
                        checkOwner()
                        return link.read("$DIRECTORY/${entry.name}").also { checkOwner() }
                    }
                    suspend fun command(cmd: String, timeout: Long) {
                        checkOwner()
                        val result = link.commands.run(cmd, timeout)
                        checkOwner()
                        if (!result.first) throw IOException(result.second.ifBlank { "Capture command failed." })
                    }
                    val before = matching(link.list(), mac, pmkid)
                    val hashes = mutableMapOf<String, ByteArray>()
                    for (entry in before) hashes[entry.name] = digest(read(entry))
                    checkOwner()
                    if (!pmkid) {
                        update(owner, "deauth", "Deauthing ${ssid.ifEmpty { bssid }}...")
                        command("wifi_deauth $address $channel 5", 15_000)
                    }
                    update(owner, "capture", "Capturing ${if (pmkid) "PMKID" else "handshake"} (${duration}s)...")
                    command(if (pmkid) "wifi_pmkid $address $channel $duration" else "wifi_handshake $address $channel $duration $ssid", (duration + 20) * 1000L)
                    update(owner, "pull", "Checking capture results...")
                    val after = matching(link.list(), mac, pmkid)
                    checkOwner()
                    var selected: CapturedTarget? = null
                    for (entry in after) {
                        val bytes = read(entry)
                        if (hashes[entry.name]?.contentEquals(digest(bytes)) == true) continue
                        val (h, p) = withContext(Dispatchers.Default) { parse(bytes, ssid) }
                        val ap = h?.apMac ?: p?.apMac ?: continue
                        if (ap.joinToString("") { "%02X".format(it.toInt() and 255) } != mac) continue
                        if (selected != null) throw IOException("Multiple changed captures; select a file manually.")
                        selected = CapturedTarget(h, p, "$DIRECTORY/${entry.name}", bytes)
                    }
                    checkOwner()
                    val target = selected ?: throw IOException("No new or changed usable capture for this BSSID. Select an existing file manually or retry.")
                    synchronized(this@CaptureWorkflow) {
                        if (owner == epoch) {
                            onLoaded(target)
                            state.value = state.value.copy(active = false, step = "ready", message = "Handshake loaded - ready to test.")
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                fail(owner, "Capture workflow timed out.")
            } catch (e: CancellationException) {
                fail(owner, "Capture workflow cancelled.")
                throw e
            } catch (e: Exception) { fail(owner, e.message ?: "Capture workflow failed.") }
        }.also { running -> running.invokeOnCompletion { if (it is CancellationException) fail(owner, "Capture workflow cancelled.") } }
    }

    @Synchronized private fun update(owner: Long, step: String, message: String) {
        if (epoch == owner) state.value = state.value.copy(step = step, message = message)
    }
    @Synchronized private fun fail(owner: Long, message: String) {
        if (epoch == owner) state.value = state.value.copy(active = false, step = "failed", message = message)
    }
    private fun matching(entries: List<FileEntry>, mac: String, pmkid: Boolean): List<FileEntry> {
        val prefix = if (pmkid) "PMKID_$mac" else "HS_$mac"
        val matches = entries.filter {
            val stem = it.name.substringBeforeLast('.').uppercase()
            !it.isDir && !it.name.contains('/') && !it.name.contains('\\') &&
                (it.name.endsWith(".pcap", true) || it.name.endsWith(".cap", true)) &&
                (stem == prefix || (!pmkid && stem.startsWith("${prefix}_")))
                && it.size in 0..(16L * 1024 * 1024)
        }
        if (matches.size > 8 || matches.map { it.name }.distinct().size != matches.size)
            throw IOException("Capture listing is ambiguous or too large; select a file manually.")
        return matches
    }
    private suspend fun digest(bytes: ByteArray) = withContext(Dispatchers.Default) { MessageDigest.getInstance("SHA-256").digest(bytes) }
    companion object { const val DIRECTORY = "/BrucePCAP/handshakes" }
}
