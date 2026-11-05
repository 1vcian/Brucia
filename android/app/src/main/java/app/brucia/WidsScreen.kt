package app.brucia

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.brucia.wardrive.WardriveViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import app.brucia.ui.BruciaButtonDefaults
import app.brucia.ui.Button
import app.brucia.ui.TextButton

/**
 * WIDS - portable wireless intrusion detection. Loops the device's jam_detect windows (802.11
 * monitor mode, which only the ESP can do - a phone cannot) and logs deauth/disassoc floods with a
 * phone timestamp, raising an ALERT when a window crosses the threshold. A defensive counterpart to
 * the evil-portal/deauth tools - it tells you when someone is attacking the air around you.
 * Authorized / lawful use.
 */
@Composable
internal fun WidsScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val active by vm.widsActive.collectAsState()
    val widsStatus by vm.widsStatus.collectAsState()
    val events by vm.widsEvents.collectAsState()

    var window by remember { mutableFloatStateOf(15f) }
    var threshold by remember { mutableFloatStateOf(20f) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val alerting = active && widsStatus.startsWith("ALERT")

    // Stop the monitor when leaving the screen.
    DisposableEffect(Unit) { onDispose { if (vm.widsActive.value) vm.stopWids() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("WIDS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (connected) Color(0xFF3DDC84) else cs.surfaceVariant))
        }
        Text("Wireless intrusion detection: the device listens in 802.11 monitor mode for deauth/" +
            "disassoc floods (evil-twin / deauth attacks) and alerts you, with a timestamped log. " +
            "Only the ESP can do this - a phone cannot. Defensive / authorized use.",
            color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (!connected) Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }

        // Live banner.
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = if (alerting) Color(0xFFD32F2F) else cs.surface),
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (!active) "Idle" else if (alerting) "ATTACK DETECTED" else "Monitoring - clear",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
                    color = if (alerting) Color.White else if (active) Color(0xFF3DDC84) else cs.onSurfaceVariant,
                )
                if (widsStatus.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(widsStatus, color = if (alerting) Color.White else cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (!active) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Window: ${window.roundToInt()}s per listen", color = cs.onSurfaceVariant)
                    Slider(value = window, onValueChange = { window = it }, valueRange = 5f..30f)
                    Text("Alert threshold: ${threshold.roundToInt()} deauth frames / window", color = cs.onSurfaceVariant)
                    Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 3f..100f)
                }
            }
        }

        Button(
            enabled = connected, modifier = Modifier.fillMaxWidth(),
            colors = BruciaButtonDefaults.buttonColors(containerColor = if (active) Color(0xFFD32F2F) else cs.primary),
            onClick = { if (active) vm.stopWids() else vm.startWids(window.roundToInt(), threshold.roundToInt()) },
        ) { Text(if (active) "Stop monitoring" else "Start monitoring") }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Events (${events.size})", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            if (events.isNotEmpty()) TextButton(onClick = { vm.clearWidsEvents() }) { Text("Clear") }
        }
        if (events.isEmpty()) {
            Text("No deauth/disassoc activity logged yet.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            events.asReversed().forEach { e ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (e.alert) Color(0xFFD32F2F) else cs.secondary))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${if (e.alert) "ALERT - " else ""}deauth ${e.deauth} / ${e.total} frames",
                                color = cs.onSurface, fontWeight = if (e.alert) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(fmt.format(Date(e.at)), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}
