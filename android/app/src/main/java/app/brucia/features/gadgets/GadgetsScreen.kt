package app.brucia.features.gadgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.brucia.R
import app.brucia.ui.Button
import app.brucia.ui.OutlinedButton
import app.brucia.ui.StatusPill

/** Presentation only: inputs remain local/saveable; device work belongs to the injected services. */
@Composable
internal fun GadgetsScreen(state: GadgetsState, actions: GadgetsActions) {
    val cs = MaterialTheme.colorScheme
    var hex by rememberSaveable { mutableStateOf("") }
    var toneFreq by rememberSaveable { mutableStateOf("1000") }
    var toneMs by rememberSaveable { mutableStateOf("500") }
    var playPath by rememberSaveable { mutableStateOf("") }
    var recSecs by rememberSaveable { mutableStateOf("5") }
    var espnowMsg by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(cs.primary),  contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(state.connected)
        }
        Text(
            "Tools - iButton and device clock",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        if (!state.connected) {
            Button(onClick = actions.connect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
            Spacer(Modifier.height(8.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surface),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text("iButton", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                if (state.connected && state.caps?.ibutton != true) {
                    Text("No confirmed iButton interface", color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                if (state.ibuttonBusy) {
                    OutlinedButton(onClick = { actions.stopIButton() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                } else {
                    Button(
                        enabled = state.connected && state.caps?.ibutton == true,
                        onClick = { actions.readIButton() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Read") }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { hex = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("id (hex)") },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    )
                    Button(
                        enabled = state.connected && state.caps?.ibutton == true && hex.isNotBlank() && !state.ibuttonBusy,
                        onClick = { actions.writeIButton(hex.trim()) },
                    ) { Text("Write") }
                }
                if (state.ibuttonStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.ibuttonStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surface),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text("Clock", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = state.connected,
                        onClick = { actions.getClock() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Get time") }
                    Button(
                        enabled = state.connected,
                        onClick = { actions.syncClock() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Sync from phone") }
                }
                if (state.clockStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.clockStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Audio / ESP-NOW",
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
            color = cs.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )

        // Speaker: tone generator + file playback. Hidden when the device reports no speaker.
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surface),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text("Speaker", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                if (state.caps != null && !state.caps.speaker) {
                    Spacer(Modifier.height(6.dp))
                    Text("This device reports no speaker.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = toneFreq,
                            onValueChange = { toneFreq = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.weight(1f),
                            label = { Text("freq (Hz)") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = toneMs,
                            onValueChange = { toneMs = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.weight(1f),
                            label = { Text("duration (ms)") },
                            singleLine = true,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = state.connected && toneFreq.toIntOrNull() != null && toneMs.toIntOrNull() != null,
                        onClick = { actions.playTone(toneFreq.toInt(), toneMs.toInt()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Play tone") }
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = playPath,
                            onValueChange = { playPath = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("file path") },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        )
                        Button(
                            enabled = state.connected && playPath.isNotBlank(),
                            onClick = { actions.playFile(playPath.trim()) },
                        ) { Text("Play file") }
                    }
                    if (state.speakerFile.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { actions.stopFile() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Stop (${state.speakerFile})")
                        }
                    }
                    if (state.speakerStatus.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.speakerStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                    }
                }
            }
        }

        // Mic: fixed-duration recording to the device storage. Shown only with a mic.
        if (state.caps == null || state.caps.mic) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Mic", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = recSecs,
                            onValueChange = { recSecs = it.filter { c -> c.isDigit() } },
                            modifier = Modifier.weight(1f),
                            label = { Text("seconds") },
                            singleLine = true,
                        )
                        Button(
                            enabled = state.connected && recSecs.toIntOrNull() != null,
                            onClick = { actions.recordMic(recSecs.toInt()) },
                        ) { Text("Record") }
                    }
                    if (state.micStatus.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.micStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                    }
                }
            }
        }

        // ESP-NOW broadcast (no capability gate - available on any ESP32).
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surface),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                if (state.connected && !state.espNowAvailable) Text("ESP-NOW unavailable on this device")
                Text("ESP-NOW", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = espnowMsg,
                    onValueChange = { espnowMsg = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("message") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = state.connected && state.espNowAvailable && espnowMsg.isNotBlank(),
                    onClick = { actions.sendEspNow(espnowMsg.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Broadcast (ESP-NOW)") }
                if (state.espnowStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(state.espnowStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}
