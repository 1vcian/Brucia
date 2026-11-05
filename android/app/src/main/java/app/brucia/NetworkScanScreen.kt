package app.brucia

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.brucia.netscan.NetworkScanViewModel
import app.brucia.netscan.NetworkScanner
import app.brucia.netscan.ScanHost
import app.brucia.netscan.VulnHit
import app.brucia.net.LocalNetworkAccess
import app.brucia.net.LocalNetworkGateCard
import app.brucia.net.rememberLocalNetworkGate
import kotlinx.coroutines.launch
import androidx.core.net.toUri
import app.brucia.ui.BruciaButtonDefaults
import app.brucia.ui.Button
import app.brucia.ui.LeaveEffect
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Phone-side network scanner ("nmap-lite"): discovers hosts on the local subnet, finds open ports +
 * service versions, and flags likely vulnerabilities (with CVE links). Runs entirely on the phone -
 * no device needed - so it works whenever the phone is on a Wi-Fi network.
 */
@Composable
internal fun NetworkScanScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lan = rememberLocalNetworkGate()

    val base = remember { NetworkScanner.subnet24() }
    val localIp = remember { NetworkScanner.localIpv4()?.first }
    // Results and the running scan live in an Activity-scoped ViewModel: a rotation, window resize
    // or fold keeps them (and does not restart the scan); leaving the tool stops and clears it.
    val session = viewModel<NetworkScanViewModel>().session
    val state by session.state.collectAsState()
    // The local network gate check runs before the scan starts; it belongs to this composition.
    var checking by remember { mutableStateOf(false) }

    LeaveEffect { session.reset() }

    NetworkScanContent(
        base = base,
        localIp = localIp,
        hosts = state.hosts,
        scanning = state.scanning,
        progress = state.progress,
        total = state.total,
        lanStatus = lan.status,
        onScan = {
            if (base != null && !checking && !session.isRunning) {
                checking = true
                scope.launch {
                    try {
                        // Local network gate: permission (Android 17 + target 37) and an EPERM probe.
                        if (lan.checkBeforeStart(localIp)) session.start(base)
                    } finally { checking = false }
                }
            }
        },
        onStop = { session.stop() },
        onRequestLan = { lan.request() },
        onOpenLanSettings = { lan.openSettings() },
        onRetryLan = { scope.launch { lan.checkBeforeStart(localIp) } },
        onOpenUrl = { url ->
            try { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { app.brucia.BruciaLog.w("NetScan", "open host url", e) }
        },
    )
}

/**
 * Stateless Network Scan page (rendered by [NetworkScanScreen] and by the screenshot tests).
 * [lanStatus] other than READY shows the local network reason card and disables Scan.
 */
@Composable
internal fun NetworkScanContent(
    base: String?,
    localIp: String?,
    hosts: List<ScanHost>,
    scanning: Boolean,
    progress: Int,
    total: Int,
    lanStatus: LocalNetworkAccess.Status = LocalNetworkAccess.Status.READY,
    onScan: () -> Unit = {},
    onStop: () -> Unit = {},
    onRequestLan: () -> Unit = {},
    onOpenLanSettings: () -> Unit = {},
    onRetryLan: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Network Scan", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold, color = cs.primary)
        Text("Discovers devices on your Wi-Fi, their open ports and service versions, and flags likely vulnerabilities (CVE links). Runs on the phone - no device needed. Authorized / own-network use only.",
            color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

        if (base == null) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Text("Connect the phone to a Wi-Fi network to scan its subnet.",
                    modifier = Modifier.padding(14.dp), color = cs.onSurfaceVariant)
            }
            return@Column
        }

        if (!scanning) {
            LocalNetworkGateCard(lanStatus, "Network Scan", onRequestLan, onOpenLanSettings, onRetryLan)
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Subnet ${base}0/24   (this phone: ${localIp ?: "?"})",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = scanning || LocalNetworkAccess.canStart(lanStatus),
                    colors = BruciaButtonDefaults.buttonColors(containerColor = if (scanning) Color(0xFFD32F2F) else cs.primary),
                    onClick = { if (scanning) onStop() else onScan() },
                ) { Text(if (scanning) "Stop scan" else "Scan network") }
                if (scanning) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { if (total > 0) progress / total.toFloat() else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("$progress / $total hosts probed  -  ${hosts.size} up",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                } else if (progress > 0) {
                    Text("Done: ${hosts.size} host${if (hosts.size == 1) "" else "s"} with open ports.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        hosts.forEach { host -> HostCard(host, onOpenUrl) }
        if (hosts.isNotEmpty()) Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HostCard(host: ScanHost, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val vulnCount = host.ports.sumOf { it.vulns.size }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(host.ip, fontWeight = FontWeight.Bold, color = cs.onSurface, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text("${host.ports.size} port${if (host.ports.size == 1) "" else "s"}" +
                    if (vulnCount > 0) "  -  $vulnCount flag${if (vulnCount == 1) "" else "s"}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (vulnCount > 0) Color(0xFFD32F2F) else cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            host.ports.forEach { p ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text("%5d".format(p.port), fontFamily = FontFamily.Monospace,
                        color = cs.primary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (p.version.isNotBlank()) "${p.service}  ${p.version}" else p.service,
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
                        p.vulns.forEach { v -> VulnBadge(v, onOpen) }
                    }
                }
            }
        }
    }
}

@Composable
private fun VulnBadge(v: VulnHit, onOpen: (String) -> Unit) {
    val color = when (v.severity) {
        "critical" -> Color(0xFFD32F2F); "high" -> Color(0xFFF57C00)
        "medium" -> Color(0xFFFBC02D); else -> Color(0xFF8E8E8E)
    }
    Row(
        Modifier.padding(top = 3.dp).clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f)).clickable { onOpen(v.url) }.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(v.id, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(6.dp))
        Text(v.title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}
