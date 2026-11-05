package app.brucia

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.brucia.wardrive.WardriveViewModel
import app.brucia.wifi.PcapDissector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.brucia.ui.Button

/**
 * On-phone PCAP dissection - a Wireshark-lite for the 802.11 captures the device records in monitor
 * mode (which only the ESP can do). Load a .pcap exported/pulled from the device, and the phone
 * parses it (PcapReader + Dot11Frame) into a summary and a packet list: frame type/subtype, src/dst,
 * BSSID and EAPOL (handshake) flag. The dissection the device's tiny screen could never show.
 */
@Composable
internal fun PcapDissectScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var fileName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<PcapDissector.Summary?>(null) }
    var rows by remember { mutableStateOf<List<PcapDissector.Row>>(emptyList()) }
    var error by remember { mutableStateOf("") }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true; error = ""; summary = null; rows = emptyList()
        fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "capture.pcap"
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { PcapDissector.readBounded(it) } ?: ByteArray(0)
                }
                val result = withContext(Dispatchers.Default) { PcapDissector.dissect(bytes) { ensureActive() } }
                if (result == null) error = "Not a classic pcap, or no frames found."
                summary = result?.summary
                rows = result?.rows ?: emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Failed to read: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("PCAP dissect", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = cs.primary)
        Text("Open an 802.11 .pcap (captured by the device in monitor mode, pulled to the phone via " +
            "the Files tool or exported) and the phone dissects it: types, addresses, BSSIDs and EAPOL " +
            "handshakes. For authorized analysis of your own captures.",
            color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

        Button(onClick = { open.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
            Text(if (loading) "Parsing..." else "Open .pcap from phone")
        }
        if (fileName.isNotBlank()) Text(fileName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        if (error.isNotBlank()) Text(error, color = cs.error, style = MaterialTheme.typography.bodySmall)

        summary?.let { s ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Summary", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("${s.frames} frames  -  mgmt ${s.mgmt} / ctrl ${s.ctrl} / data ${s.data}", color = cs.onSurface)
                    if (s.eapol > 0) Text("EAPOL (handshake) frames: ${s.eapol}", color = cs.secondary, fontWeight = FontWeight.SemiBold)
                    if (s.bssids.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("Top BSSIDs:", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        s.bssids.forEach { (b, c) ->
                            Text("  $b  -  $c", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                        }
                    }
                }
            }

            Text("Packets (${minOf(rows.size, PcapDissector.MAX_ROWS)}${if (s.frames > PcapDissector.MAX_ROWS) " of ${s.frames}" else ""})",
                fontWeight = FontWeight.SemiBold, color = cs.primary)
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.n}", modifier = Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(r.label + if (r.eapol) "  [EAPOL]" else "", color = if (r.eapol) cs.secondary else cs.onSurface,
                            style = MaterialTheme.typography.bodySmall, fontWeight = if (r.eapol) FontWeight.Bold else FontWeight.Normal)
                        Text("${r.src.ifBlank { "?" }} -> ${r.dst.ifBlank { "?" }}  @${r.bssid.ifBlank { "?" }}",
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
