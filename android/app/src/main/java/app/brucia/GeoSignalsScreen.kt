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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import app.brucia.ui.Button
import app.brucia.ui.OutlinedButton

/** Great-circle distance in metres between two lat/lon points. */
private fun haversineM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(h), sqrt(1 - h))
}

/**
 * Geofenced capture/replay. Pin a saved RF (sub-GHz) or IR signal to the spot where you are now;
 * later the list shows how far each pinned signal is and lets you replay it when you return (the ESP
 * transmits). The compelling case is sub-GHz - a gate/garage that works on arrival. The phone brings
 * the GPS the device lacks. Authorized use only (your own gates/remotes).
 */
@Composable
internal fun GeoSignalsScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val loc by vm.currentLoc.collectAsState()
    val savedRf by vm.savedRf.collectAsState()
    val savedIr by vm.savedIr.collectAsState()
    val geo by vm.geoSignals.collectAsState()
    val geoStatus by vm.geoStatus.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Geo signals", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (connected) Color(0xFF3DDC84) else cs.surfaceVariant))
        }
        Text("Pin a saved sub-GHz / IR signal to this GPS spot, then replay it when you come back. " +
            "The device transmits; the phone provides the GPS it doesn't have. Authorized use (your own).",
            color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (!connected) Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }

        Text(loc?.let { "GPS: %.5f, %.5f".format(it.first, it.second) } ?: "Waiting for a GPS fix...",
            color = if (loc == null) cs.error else cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        if (geoStatus.isNotBlank()) Text(geoStatus, color = cs.onSurface, style = MaterialTheme.typography.bodySmall)

        // Pinned signals, with live distance.
        if (geo.isNotEmpty()) {
            Text("Pinned here", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            geo.forEachIndexed { i, g ->
                val dist = loc?.let { haversineM(it.first, it.second, g.lat, g.lon) }
                val near = dist != null && dist < 100
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (near) Color(0xFF123A12) else cs.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(if (g.type == "rf") Color(0xFFFF6D1A) else Color(0xFF4AA3FF)))
                            Spacer(Modifier.width(8.dp))
                            Text(g.name, color = cs.onSurface, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(dist?.let { if (it < 1000) "${it.roundToInt()} m" else "%.1f km".format(it / 1000) } ?: "-",
                                color = if (near) Color(0xFF3DDC84) else cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = connected, modifier = Modifier.weight(1f), onClick = { vm.replayGeoSignal(g) }) { Text("Replay") }
                            OutlinedButton(onClick = { vm.deleteGeoSignal(i) }) { Text("Delete") }
                        }
                    }
                }
            }
        }

        // Saved signals you can pin to the current spot.
        val pinnable = savedRf + savedIr
        Text("Saved signals - pin to here", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (pinnable.isEmpty()) {
            Text("No saved RF/IR signals yet. Capture and save one in the RF or IR tool first.",
                color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            pinnable.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, color = cs.onSurface, maxLines = 1)
                        Text("${item.type}  -  ${item.subtitle}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = loc != null, onClick = { vm.addGeoSignal(item.name, item.type, item.payload) }) { Text("Pin here") }
                }
            }
        }
    }
}
