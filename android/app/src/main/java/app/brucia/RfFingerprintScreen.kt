package app.brucia

import app.brucia.ui.rememberRetained
import app.brucia.ui.LeaveEffect
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.brucia.wardrive.RfFingerprint
import app.brucia.wardrive.WardriveViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.brucia.ui.Button
import app.brucia.ui.OutlinedButton
import app.brucia.ui.TextButton

/**
 * RF place-fingerprint - a defensive counter-surveillance tool. The ESP scans the Wi-Fi/BLE emitters
 * present HERE; you save that set as a named "fingerprint" of the place. Later, Compare checks the
 * live scan against a saved fingerprint and shows what is NEW (appeared since - a new device could
 * mean surveillance), MISSING (gone) or unchanged. Only the ESP can sweep these radios; the phone
 * stores and diffs. Authorized use.
 */
@Composable
internal fun RfFingerprintScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val aps by vm.aps.collectAsState()
    val ble by vm.ble.collectAsState()
    val fingerprints by vm.rfFingerprints.collectAsState()
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.US) }

    var name by rememberSaveable { mutableStateOf("") }
    var compareWith by remember { mutableStateOf<RfFingerprint?>(null) }
    var bleStartedHere by rememberRetained { mutableStateOf(false) }

    // Scan Wi-Fi + BLE while this screen is open; stop what we started on leave. Starts only what is
    // not already on, so a screen recreated by a rotation re-binds (no second SUB ON); a rotation
    // is not leaving (LeaveEffect). A disconnect is cleaned up by the ViewModel.
    LaunchedEffect(connected) {
        if (connected) {
            if (!vm.wardriving.value) vm.setWardriving(true)
            if (!vm.bleScanning.value) { vm.setBleScan(true); bleStartedHere = true }
        }
    }
    LeaveEffect {
        if (vm.wardriving.value) vm.setWardriving(false)
        if (bleStartedHere) { vm.setBleScan(false); bleStartedHere = false }
    }

    val curBssids = remember(aps) { aps.map { it.bssid }.filter { it.isNotBlank() }.toSet() }
    val curMacs = remember(ble) { ble.map { it.mac }.filter { it.isNotBlank() }.toSet() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("RF fingerprint", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (connected) Color(0xFF3DDC84) else cs.surfaceVariant))
        }
        Text("Record the RF signature of a place (the Wi-Fi/BLE devices the ESP sees here), then later " +
            "compare to detect what is NEW - a device that appeared can mean new surveillance. " +
            "Defensive / authorized use.",
            color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (!connected) Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }

        // Live scan + capture.
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Seen now: ${curBssids.size} Wi-Fi + ${curMacs.size} BLE", color = cs.onSurface)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(name, { name = it }, label = { Text("Place name") }, singleLine = true,
                        enabled = connected, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = connected && (curBssids.isNotEmpty() || curMacs.isNotEmpty()),
                        onClick = { vm.captureFingerprint(name); name = "" }) { Text("Capture") }
                }
                Text("Let the scan settle a few seconds before capturing.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }

        // Comparison result.
        compareWith?.let { fp ->
            val savedB = fp.bssids.toSet(); val savedM = fp.bleMacs.toSet()
            val newB = curBssids - savedB; val newM = curMacs - savedM
            val goneB = savedB - curBssids; val goneM = savedM - curMacs
            val alert = newB.isNotEmpty() || newM.isNotEmpty()
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (alert) Color(0xFF4A2A00) else cs.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("vs \"${fp.name}\"", fontWeight = FontWeight.SemiBold, color = cs.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { compareWith = null }) { Text("Close") }
                    }
                    Text(if (alert) "CHANGED - new devices present" else "No new devices vs this fingerprint",
                        color = if (alert) cs.secondary else Color(0xFF3DDC84), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("NEW: ${newB.size} Wi-Fi, ${newM.size} BLE   -   gone: ${goneB.size} Wi-Fi, ${goneM.size} BLE",
                        color = cs.onSurface, style = MaterialTheme.typography.bodySmall)
                    if (newB.isNotEmpty() || newM.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text("New emitters:", style = MaterialTheme.typography.labelMedium, color = cs.secondary)
                        (newB.map { "wifi $it" } + newM.map { "ble  $it" }).take(30).forEach {
                            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = cs.onSurface)
                        }
                    }
                }
            }
        }

        // Saved fingerprints.
        if (fingerprints.isNotEmpty()) {
            Text("Saved places", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            fingerprints.forEachIndexed { i, fp ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(fp.name, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        Text("${fp.bssids.size} Wi-Fi + ${fp.bleMacs.size} BLE  -  ${fmt.format(Date(fp.at))}",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = connected, modifier = Modifier.weight(1f), onClick = { compareWith = fp }) { Text("Compare now") }
                            OutlinedButton(onClick = { vm.deleteFingerprint(i); if (compareWith == fp) compareWith = null }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
