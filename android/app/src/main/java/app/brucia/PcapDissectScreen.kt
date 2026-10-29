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
import app.brucia.wifi.Dot11Frame
import app.brucia.wifi.PcapReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row in the packet list (kept small so a big capture doesn't blow up memory). */
private data class PktRow(val n: Int, val label: String, val src: String, val dst: String, val bssid: String, val eapol: Boolean)

private data class PcapSummary(
    val frames: Int, val mgmt: Int, val ctrl: Int, val data: Int, val eapol: Int,
    val bssids: List<Pair<String, Int>>, // top BSSIDs by frame count
)

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
    var summary by remember { mutableStateOf<PcapSummary?>(null) }
    var rows by remember { mutableStateOf<List<PktRow>>(emptyList()) }
    var error by remember { mutableStateOf("") }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true; error = ""; summary = null; rows = emptyList()
        fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "capture.pcap"
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                }
                val (sum, list) = withContext(Dispatchers.Default) {
                    val recs = PcapReader.parse(bytes)
                    if (recs.isEmpty()) return@withContext null to emptyList<PktRow>()
                    var mgmt = 0; var ctrl = 0; var dataC = 0; var eapol = 0
                    val bssidCount = HashMap<String, Int>()
                    val list = ArrayList<PktRow>()
                    recs.forEachIndexed { i, r ->
                        val info = Dot11Frame.parse(r.frame)
                        when (info.type) { 0 -> mgmt++; 1 -> ctrl++; 2 -> dataC++ }
                        if (info.isEapol) eapol++
                        if (info.addr3.isNotBlank()) bssidCount[info.addr3] = (bssidCount[info.addr3] ?: 0) + 1
                        if (list.size < 1000) list.add(PktRow(i + 1, info.label, info.addr2, info.addr1, info.addr3, info.isEapol))
                    }
                    val top = bssidCount.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
                    PcapSummary(recs.size, mgmt, ctrl, dataC, eapol, top) to list
                }
                if (sum == null) error = "Not a classic pcap, or no frames found."
                summary = sum
                rows = list
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

            Text("Packets (${minOf(rows.size, 1000)}${if (s.frames > 1000) " of ${s.frames}" else ""})",
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
