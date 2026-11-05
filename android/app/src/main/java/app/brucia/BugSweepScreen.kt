package app.brucia

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.brucia.wardrive.WardriveViewModel
import kotlinx.coroutines.delay
import app.brucia.ui.BruciaButtonDefaults
import app.brucia.ui.Button

/**
 * Hidden-transmitter / bug & spy-cam RF sweep. A defensive tool: it listens across the bands only the
 * ESP can cover together - Wi-Fi and BLE RSSI plus the CC1101 sub-GHz spectrum peak (a phone cannot
 * sweep sub-GHz at all) - and shows a live "hotter / colder" strength meter so you can physically
 * home in on an unknown emitter by walking the device around. Authorized use only.
 */
@Composable
internal fun BugSweepScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val aps by vm.aps.collectAsState()
    val ble by vm.ble.collectAsState()
    val spectrum by vm.spectrum.collectAsState()

    var useWifi by rememberSaveable { mutableStateOf(true) }
    var useBle by rememberSaveable { mutableStateOf(true) }
    var useSub by rememberSaveable { mutableStateOf(true) }
    var sweeping by remember { mutableStateOf(false) }

    // Latest strength per band (dBm; null = nothing seen) and who the strongest emitter is.
    val wifiTop = if (useWifi) aps.maxByOrNull { it.rssi } else null
    val bleTop = if (useBle) ble.maxByOrNull { it.rssi } else null
    val subTopRssi = if (useSub) spectrum?.rssi?.maxOrNull() else null

    data class Band(val label: String, val rssi: Int?, val who: String, val color: Color)
    val bands = listOf(
        Band("Wi-Fi", wifiTop?.rssi, wifiTop?.ssid?.ifBlank { wifiTop.bssid } ?: "-", Color(0xFFFF6D1A)),
        Band("BLE", bleTop?.rssi, bleTop?.let { it.name.ifBlank { it.mac } } ?: "-", Color(0xFF4AA3FF)),
        Band("Sub-GHz", subTopRssi, if (subTopRssi != null) "spectrum peak" else "-", Color(0xFFFF4D4D)),
    )
    val overall = bands.mapNotNull { it.rssi }.maxOrNull()
    val strongest = bands.filter { it.rssi != null }.maxByOrNull { it.rssi!! }

    // Peak-hold and hotter/colder direction, sampled so the arrow is readable.
    var peakHold by remember { mutableStateOf<Int?>(null) }
    var prevSample by remember { mutableStateOf<Int?>(null) }
    var trend by remember { mutableIntStateOf(0) } // -1 colder, 0 flat, +1 hotter
    // Keep a live handle to the latest overall RSSI so the long-running sampling loop sees updates
    // as the aps/ble/spectrum flows emit (otherwise it would read the value captured at launch).
    val overallLive = rememberUpdatedState(overall)
    LaunchedEffect(sweeping) {
        if (!sweeping) return@LaunchedEffect
        while (true) {
            val cur = overallLive.value
            if (cur != null) {
                peakHold = maxOf(peakHold ?: cur, cur)
                val p = prevSample
                trend = when { p == null -> 0; cur - p >= 2 -> 1; cur - p <= -2 -> -1; else -> 0 }
                prevSample = cur
            }
            delay(700)
        }
    }

    // Track whether THIS screen turned BLE on, so we don't stop a scan started elsewhere.
    var bleStartedHere by remember { mutableStateOf(false) }
    // Start/stop the three ESP streams with the sweep.
    fun startSweep() {
        if (!connected) return
        if (useSub) vm.startSpectrum()
        if (useBle) { vm.setBleScan(true); bleStartedHere = true }
        if (useWifi) vm.setWardriving(true)
        peakHold = null; prevSample = null; trend = 0
        sweeping = true
    }
    fun stopSweep() {
        sweeping = false
        if (vm.spectrumActive.value) vm.stopSpectrum()
        if (bleStartedHere) { vm.setBleScan(false); bleStartedHere = false }
        if (vm.wardriving.value) vm.setWardriving(false)
    }
    DisposableEffect(Unit) { onDispose { if (sweeping) stopSweep() } }

    // Map an RSSI (~ -95 far .. -30 near) to 0..1 for the meter.
    fun pct(rssi: Int?): Float = if (rssi == null) 0f else ((rssi + 95) / 65f).coerceIn(0f, 1f)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Bug Sweep", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (connected) Color(0xFF3DDC84) else cs.surfaceVariant))
        }
        Text("Find hidden transmitters (bugs, spy cams, trackers). Walk the device around a room and " +
            "watch the meter: it rises as you get closer to an emitter. Covers Wi-Fi, BLE and - only " +
            "possible with the CC1101 - sub-GHz. Defensive / authorized use.",
            color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (!connected) Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }

        // Band toggles.
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Bands to sweep", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(useWifi, { useWifi = it }, enabled = !sweeping); Spacer(Modifier.width(8.dp)); Text("Wi-Fi", color = cs.onSurface) }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(useBle, { useBle = it }, enabled = !sweeping); Spacer(Modifier.width(8.dp)); Text("BLE", color = cs.onSurface) }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(useSub, { useSub = it }, enabled = !sweeping); Spacer(Modifier.width(8.dp)); Text("Sub-GHz (CC1101)", color = cs.onSurface) }
            }
        }

        Button(
            enabled = connected && (useWifi || useBle || useSub),
            modifier = Modifier.fillMaxWidth(),
            colors = BruciaButtonDefaults.buttonColors(containerColor = if (sweeping) Color(0xFFD32F2F) else cs.primary),
            onClick = { if (sweeping) stopSweep() else startSweep() },
        ) { Text(if (sweeping) "Stop sweep" else "Start sweep") }

        if (sweeping) {
            // Big combined strength meter + hotter/colder.
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (trend) { 1 -> "HOTTER"; -1 -> "colder"; else -> "hold steady" },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
                        color = when (trend) { 1 -> Color(0xFFFF4D4D); -1 -> Color(0xFF4AA3FF); else -> cs.onSurfaceVariant },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(overall?.let { "$it dBm" } ?: "no signal", color = cs.onSurface, style = MaterialTheme.typography.bodyMedium)
                    strongest?.let { Text("strongest: ${it.label} - ${it.who}", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(10.dp))
                    // Combined bar + peak-hold tick.
                    Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp)).background(cs.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(pct(overall)).fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(cs.primary))
                        peakHold?.let { ph ->
                            Box(Modifier.fillMaxWidth(pct(ph)).fillMaxHeight()) {
                                Box(Modifier.align(Alignment.CenterEnd).width(3.dp).fillMaxHeight().background(cs.onSurface))
                            }
                        }
                    }
                    peakHold?.let { Text("peak this sweep: $it dBm", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
            // Per-band readouts.
            bands.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(b.color)); Spacer(Modifier.width(8.dp))
                    Text(b.label, color = cs.onSurface, modifier = Modifier.width(72.dp))
                    Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(cs.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(pct(b.rssi)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(b.color))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(b.rssi?.let { "$it" } ?: "-", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(36.dp))
                }
            }
            Text("Tip: the sub-GHz peak reflects whatever range the device is sweeping - set it in the RF tool to target a suspected band.",
                color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
