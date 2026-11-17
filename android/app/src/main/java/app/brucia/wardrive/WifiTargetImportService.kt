package app.brucia.wardrive

import app.brucia.BoundedImport
import app.brucia.ImportTooLargeException
import app.brucia.wifi.HandshakeParser
import app.brucia.wifi.Hc22000
import app.brucia.wifi.PcapDissector
import app.brucia.wifi.Pmkid
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phone-only target import: document IO, existing parsers, labels and selection ownership.
 * The caller supplies an unopened document; no Uri, Activity, USB session or Android dependency.
 * [target] is shared with device pulls/captures, so all selection paths use the same generation.
 * A failed/empty current import clears the target; a superseded or cancelled import cannot publish.
 * Reads are bounded to 32 MiB. Cancellation is checked between reads, not inside a blocked provider.
 */
class WifiTargetImportService(
    private val scope: CoroutineScope,
    private val target: WifiTargetStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Existing 16800 and 22000 formats; pasting is synchronous and supersedes pending imports. */
    fun importText(line: String) {
        if (line.trim().startsWith("WPA*")) {
            val parsed = Hc22000.parse(line)
            val hs = parsed?.first
            val pm = parsed?.second
            when {
                hs != null -> target.set(WifiTargetStore.Target(handshake = hs), "${hs.ssid} (WPA${hs.keyVersion}) [22000]")
                pm != null -> target.set(WifiTargetStore.Target(pmkid = pm), "${pm.ssid} (PMKID) [22000]")
                else -> target.set(null, "invalid 22000 line")
            }
        } else {
            val pm = Pmkid.parse(line)
            if (pm != null) target.set(WifiTargetStore.Target(pmkid = pm), "${pm.ssid} (PMKID)")
            else target.set(null, "invalid PMKID line")
        }
    }

    /** Claim synchronously, before dispatching IO: the newest operation to begin owns selection. */
    fun importPcap(open: () -> InputStream?): Job {
        val token = target.begin()
        return scope.launch(io) {
            val (loaded, message) = try {
                val bytes = (open() ?: throw IOException("Cannot open document")).use {
                    BoundedImport.read(it, PcapDissector.MAX_CAPTURE_BYTES.toLong()) { !isActive }
                }
                ensureActive()
                val hs = HandshakeParser.parse(bytes)
                hs?.let { WifiTargetStore.Target(handshake = it) } to
                    (hs?.let { "${it.ssid} (WPA${it.keyVersion})" } ?: "no handshake found in file")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ImportTooLargeException) {
                null to "file too large (max 32 MiB)"
            } catch (e: Exception) {
                null to "read error"
            }
            ensureActive()
            target.commit(token, loaded, message)
        }
    }
}
