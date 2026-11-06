package app.brucia

import app.brucia.session.StreamClaim
import app.brucia.ui.rememberRetained
import app.brucia.ui.LeaveEffect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.brucia.wardrive.ChannelAdvisor
import app.brucia.wardrive.WardriveViewModel
import app.brucia.ui.Button

/**
 * Live 2.4GHz channel analyzer: runs the wifi.ap scan and shows, per channel, how many APs are on it
 * and the strongest signal seen, plus the least-congested recommended channel. All phone-side from the
 * same wifi.ap stream the wardrive uses - the device only captures.
 */
@Composable
internal fun ChannelAnalyzerScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val aps by vm.aps.collectAsState()
    val scanning by vm.wardriving.collectAsState()

    // Scan whenever the session is usable while this screen is open: on enter, and again on a
    // connect or reconnect made here (a disconnect ends the scan in the ViewModel). Leaving stops
    // only a scan this screen started; a Wardrive scan already running stays on. The claim is
    // retained across a rotation, which neither restarts nor stops the scan.
    val wifiClaim = rememberRetained { StreamClaim() }
    LaunchedEffect(connected) { if (connected) wifiClaim.acquire(vm.wardriving.value) { vm.setWardriving(true) } }
    LeaveEffect { wifiClaim.release(vm.streamMayRun("wifi.ap")) { vm.setWardriving(false) } }

    val band24 = aps.filter { it.channel in 1..14 }
    val counts = remember(band24) { ChannelAdvisor.congestion(band24) }
    val maxRssiByCh = remember(band24) {
        band24.groupBy { it.channel }.mapValues { e -> e.value.maxOf { it.rssi } }
    }
    val best = remember(band24) { if (band24.isEmpty()) 1 else ChannelAdvisor.recommend24(band24) }
    val maxCount = (counts.values.maxOrNull() ?: 0).coerceAtLeast(1)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Channel Analyzer", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (connected) Color(0xFF3DDC84) else cs.surfaceVariant))
        }
        Text("Live 2.4GHz congestion from the Wi-Fi scan. Taller bar = more APs on that channel; the number is the strongest signal seen.",
            color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (!connected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
            return@Column
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.primaryContainer)) {
            Column(Modifier.padding(14.dp)) {
                Text("Recommended channel: $best", fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
                Text(if (band24.isEmpty()) (if (scanning) "Scanning..." else "No 2.4GHz networks yet")
                     else "${band24.size} APs across ${counts.size} channels",
                    style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
            }
        }

        // Bar chart for channels 1..14.
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Channels 1 - 14", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Spacer(Modifier.height(10.dp))
                for (ch in 1..14) {
                    val c = counts[ch] ?: 0
                    val frac = c.toFloat() / maxCount
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text("%2d".format(ch), style = MaterialTheme.typography.bodySmall,
                            color = if (ch == best) cs.primary else cs.onSurfaceVariant,
                            fontWeight = if (ch == best) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.width(26.dp))
                        Box(Modifier.weight(1f).height(18.dp)) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(cs.surfaceVariant.copy(alpha = 0.4f), size = Size(size.width, size.height))
                                if (c > 0) drawRect(
                                    if (ch == best) Color(0xFF3DDC84) else cs.primary,
                                    size = Size(size.width * frac, size.height),
                                )
                            }
                        }
                        Text(
                            if (c > 0) "$c AP${if (c == 1) "" else "s"}  ${maxRssiByCh[ch]}dBm" else "-",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp).width(96.dp),
                        )
                    }
                }
            }
        }
    }
}
