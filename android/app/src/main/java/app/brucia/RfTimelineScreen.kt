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
import app.brucia.ui.Button
import app.brucia.ui.TextButton

/**
 * Unified RF presence timeline - "temporal intelligence". The ESP scans Wi-Fi + BLE; the phone keeps
 * a persistent history of every device seen, with first-seen / last-seen and a hit count, merged
 * across sessions. Lets you see who/what is around over time - impossible on a Flipper (no storage/
 * compute) or on the device alone. Authorized use.
 */
@Composable
internal fun RfTimelineScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val active by vm.timelineActive.collectAsState()
    val sightings by vm.sightings.collectAsState()
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.US) }

    var filter by remember { mutableStateOf("") }
    var bleStartedHere by remember { mutableStateOf(false) }

    // Scan Wi-Fi + BLE and accumulate while the screen is open; stop what we started on leave.
    DisposableEffect(connected) {
        if (connected) {
            if (!vm.wardriving.value) vm.setWardriving(true)
            vm.setBleScan(true); bleStartedHere = true
            vm.startTimeline()
        }
        onDispose {
            vm.stopTimeline()
            if (vm.wardriving.value) vm.setWardriving(false)
            if (bleStartedHere) { vm.setBleScan(false); bleStartedHere = false }
        }
    }

    val shown = remember(sightings, filter) {
        if (filter.isBlank()) sightings
        else sightings.filter { it.label.contains(filter, true) || it.key.contains(filter, true) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("RF timeline", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (active) Color(0xFF3DDC84) else cs.surfaceVariant))
        }
        Text("A persistent history of every Wi-Fi/BLE device the ESP sees, with first/last seen and " +
            "count, kept across sessions. Walk around or leave it running to build a presence picture.",
            color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (!connected) Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(if (active) "Logging - ${sightings.size} devices seen" else "${sightings.size} devices in history",
                color = cs.onSurface, modifier = Modifier.weight(1f))
            if (sightings.isNotEmpty()) TextButton(onClick = { vm.clearTimeline() }) { Text("Clear") }
        }
        OutlinedTextField(filter, { filter = it }, label = { Text("Filter") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        if (shown.isEmpty()) {
            Text("No sightings yet.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            shown.take(300).forEach { s ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (s.type == "wifi") Color(0xFFFF6D1A) else Color(0xFF4AA3FF)))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.label.ifBlank { s.key }, color = cs.onSurface, maxLines = 1)
                            Text("${s.type}  -  seen ${s.count}x  -  first ${fmt.format(Date(s.firstSeen))}, last ${fmt.format(Date(s.lastSeen))}",
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            if (shown.size > 300) Text("... and ${shown.size - 300} more", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}
