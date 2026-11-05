package app.brucia.wifi

import app.brucia.files.FileEntry
import app.brucia.session.CommandRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/** One snapshot of a connection. Implementations must never switch to a reconnected device. */
interface CaptureLink {
    val commands: CommandRunner
    suspend fun list(): List<FileEntry>
    suspend fun read(path: String): ByteArray

    /**
     * True when the connected firmware advertises `features.capture_descriptor`. The workflow then
     * imports exactly the descriptor's path and verifies it, instead of the legacy listing-diff path.
     */
    val descriptorSupported: Boolean get() = false

    /**
     * Run a capture command and return its terminal ACK's data object (carrying `desc`). Only used on
     * descriptor-capable links; the default delegates to [commands] and reports no descriptor, so
     * legacy links and tests need not implement it.
     */
    suspend fun capture(cmd: String, timeoutMs: Long): Pair<Boolean, JSONObject?> =
        commands.run(cmd, timeoutMs).let { it.first to null }
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
    /** Parsing and hashing run here, off the caller's dispatcher (tests inject a virtual-time one). */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
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
                    suspend fun readPath(path: String): ByteArray {
                        checkOwner()
                        return link.read(path).also { checkOwner() }
                    }
                    suspend fun command(cmd: String, timeout: Long) {
                        checkOwner()
                        val result = link.commands.run(cmd, timeout)
                        checkOwner()
                        if (!result.first) throw IOException(result.second.ifBlank { "Capture command failed." })
                    }
                    // Both paths provoke a reassociation with a short deauth before a handshake capture.
                    suspend fun provoke() {
                        if (!pmkid) {
                            update(owner, "deauth", "Deauthing ${ssid.ifEmpty { bssid }}...")
                            command("wifi_deauth $address $channel 5", 15_000)
                        }
                    }
                    val captureCmd = if (pmkid) "wifi_pmkid $address $channel $duration"
                        else "wifi_handshake $address $channel $duration $ssid"

                    val selected: CapturedTarget = if (link.descriptorSupported) {
                        // New path: the device tells us exactly which file it wrote; import and verify it.
                        provoke()
                        update(owner, "capture", "Capturing ${if (pmkid) "PMKID" else "handshake"} (${duration}s)...")
                        checkOwner()
                        val (ok, data) = link.capture(captureCmd, (duration + 20) * 1000L)
                        checkOwner()
                        val desc = CaptureDescriptor.fromJson(data)
                            ?: throw IOException("Device did not return a valid capture descriptor.")
                        if (!ok || !desc.complete) throw IOException(desc.partialReason(pmkid))
                        if (!descriptorPathAllowed(desc.fileName, mac, pmkid))
                            throw IOException("Capture descriptor path does not match this target; not importing.")
                        update(owner, "pull", "Verifying device capture...")
                        val bytes = readPath(desc.path)
                        desc.verify(bytes)?.let { throw IOException("Capture verification failed: $it") }
                        toTarget(bytes, desc.path, ssid, mac)
                            ?: throw IOException("Verified capture is not for this BSSID; not importing.")
                    } else {
                        // Legacy path: no descriptor. Infer the new/changed file from listing + content diffs.
                        val before = matching(link.list(), mac, pmkid)
                        val hashes = mutableMapOf<String, ByteArray>()
                        for (entry in before) { checkOwner(); hashes[entry.name] = digest(readPath("$DIRECTORY/${entry.name}")) }
                        checkOwner()
                        provoke()
                        update(owner, "capture", "Capturing ${if (pmkid) "PMKID" else "handshake"} (${duration}s)...")
                        command(captureCmd, (duration + 20) * 1000L)
                        update(owner, "pull", "Checking capture results...")
                        val after = matching(link.list(), mac, pmkid)
                        checkOwner()
                        var found: CapturedTarget? = null
                        for (entry in after) {
                            val bytes = readPath("$DIRECTORY/${entry.name}")
                            if (hashes[entry.name]?.contentEquals(digest(bytes)) == true) continue
                            val target = toTarget(bytes, "$DIRECTORY/${entry.name}", ssid, mac) ?: continue
                            if (found != null) throw IOException("Multiple changed captures; select a file manually.")
                            found = target
                        }
                        checkOwner()
                        found ?: throw IOException("No new or changed usable capture for this BSSID. Select an existing file manually or retry.")
                    }
                    checkOwner()
                    val note = if (link.descriptorSupported) "verified by device descriptor" else "verified by listing diff"
                    synchronized(this@CaptureWorkflow) {
                        if (owner == epoch) {
                            onLoaded(selected)
                            state.value = state.value.copy(active = false, step = "ready",
                                message = "Handshake loaded ($note) - ready to test.")
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

    /** Parse pulled bytes and confirm the capture is for the requested BSSID; null = not ours. */
    private suspend fun toTarget(bytes: ByteArray, path: String, ssid: String, mac: String): CapturedTarget? {
        val (h, p) = withContext(compute) { parse(bytes, ssid) }
        val ap = h?.apMac ?: p?.apMac ?: return null
        if (ap.joinToString("") { "%02X".format(it.toInt() and 255) } != mac) return null
        return CapturedTarget(h, p, path, bytes)
    }

    /** A descriptor's basename must be the expected per-BSSID capture name, never an arbitrary path. */
    private fun descriptorPathAllowed(name: String, mac: String, pmkid: Boolean): Boolean {
        if (name.isEmpty() || name.contains('/') || name.contains('\\')) return false
        if (!name.endsWith(".pcap", true) && !name.endsWith(".cap", true)) return false
        val stem = name.substringBeforeLast('.').uppercase()
        val prefix = if (pmkid) "PMKID_$mac" else "HS_$mac"
        return stem == prefix || (!pmkid && stem.startsWith("${prefix}_"))
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
    private suspend fun digest(bytes: ByteArray) = withContext(compute) { MessageDigest.getInstance("SHA-256").digest(bytes) }
    companion object { const val DIRECTORY = "/BrucePCAP/handshakes" }
}
