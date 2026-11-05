package app.brucia

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.brucia.ui.Amber
import app.brucia.ui.BruciaTheme
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.cash.paparazzi.detectEnvironment
import org.junit.Rule
import org.junit.Test

/**
 * Renders the real flame theme + the real `ic_flame` drawable to a PNG on the JVM
 * (no device/emulator), so the fire-themed UI is verified and captured for the repo.
 */
class FlameUiScreenshotTest {

    // Paparazzi 1.3.5 bundles layoutlib 14.0.11 (API 34) but reports SDK_INT = compileSdk (35).
    // Compose 1.8+ then takes API 35 paths (StaticLayout.Builder.setUseBoundsForWidth) that this
    // layoutlib lacks and every snapshot fails; report the API level the renderer implements.
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        environment = detectEnvironment().copy(compileSdkVersion = 34),
    )

    @Test
    fun wardriveScreen() {
        paparazzi.snapshot { BruciaTheme { WardrivePreview() } }
    }

    @Test
    fun crackScreen() {
        paparazzi.snapshot { BruciaTheme { CrackPreview() } }
    }

    @Test
    fun nfcScreen() {
        paparazzi.snapshot { BruciaTheme { NfcPreview() } }
    }

    @Test
    fun crackDownloadsScreen() {
        paparazzi.snapshot { BruciaTheme { CrackDownloadsPreview() } }
    }

    @Test
    fun irBrowserScreen() {
        paparazzi.snapshot { BruciaTheme { IrBrowserPreview() } }
    }

    // --- Headline screens rendered with representative static sample data. ---

    @Test
    fun homeDashboardConnectedScreen() {
        paparazzi.snapshot { BruciaTheme { HomeDashboardConnectedPreview() } }
    }

    @Test
    fun homeDashboardDisconnectedScreen() {
        paparazzi.snapshot { BruciaTheme { HomeDashboardDisconnectedPreview() } }
    }

    @Test
    fun spamHubScreen() {
        paparazzi.snapshot { BruciaTheme { SpamHubPreview() } }
    }

    @Test
    fun wifiMonitorScreen() {
        paparazzi.snapshot { BruciaTheme { WifiMonitorPreview() } }
    }

    @Test
    fun mitmScreen() {
        paparazzi.snapshot { BruciaTheme { MitmPreview() } }
    }

    @Test
    fun nrf24Screen() {
        paparazzi.snapshot { BruciaTheme { Nrf24Preview() } }
    }

    @Test
    fun subGhzScreen() {
        paparazzi.snapshot { BruciaTheme { SubGhzPreview() } }
    }

    @Test
    fun reportScreen() {
        paparazzi.snapshot { BruciaTheme { ReportPreview() } }
    }

    @Test
    fun bleScreen() {
        paparazzi.snapshot { BruciaTheme { BlePreview() } }
    }

    @Test
    fun capabilityDisabledScreen() {
        paparazzi.snapshot { BruciaTheme { CapabilityDisabledPreview() } }
    }

    // Definitive validation: render a REAL frame captured from the device (`display start`)
    // through the app's mirror pipeline and snapshot it - this is the actual Bruce screen
    // as the Remote tab reproduces it.
    @Test
    fun mirrorRenderRealFrame() {
        val bytes = this::class.java.classLoader!!.getResourceAsStream("frame.bin")!!.readBytes()
        val r = app.brucia.remote.TftRenderer(320, 170)
        val (ops, _) = app.brucia.remote.TftProtocol.parse(bytes)
        r.applyAll(ops)
        val img = r.bitmap.asImageBitmap()
        paparazzi.snapshot {
            androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
                Image(bitmap = img, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            }
        }
    }

    // Validates the Remote-control mirror renderer without hardware: feed synthetic
    // draw-ops (as the device would send via `display start`) and snapshot the result.
    @Test
    fun mirrorRender() {
        val r = app.brucia.remote.TftRenderer(320, 170)
        val (ops, _) = app.brucia.remote.TftProtocol.parse(syntheticBruceScreen())
        r.applyAll(ops)
        val img = r.bitmap.asImageBitmap()
        paparazzi.snapshot {
            androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            }
        }
    }
}

// Builds a Bruce-like screen as a TFT mirror byte stream (0xAA size fn uint16-BE args).
private fun syntheticBruceScreen(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    fun pkt(fn: Int, args: List<Int>, text: String? = null) {
        val body = java.io.ByteArrayOutputStream()
        for (a in args) { body.write((a shr 8) and 0xFF); body.write(a and 0xFF) }
        if (text != null) body.write(text.toByteArray(Charsets.UTF_8))
        out.write(0xAA); out.write(3 + body.size()); out.write(fn); out.write(body.toByteArray())
    }
    pkt(0, listOf(0x0000))                              // fill screen black
    pkt(2, listOf(0, 0, 320, 22, 0xFB63))              // ember title bar
    pkt(16, listOf(8, 4, 2, 0x0000, 0xFB63), text = "Brucia")  // title text
    var y = 34
    for (label in listOf("WiFi", "BLE", "Sub-GHz", "RFID / NFC", "IR", "Others")) {
        pkt(1, listOf(6, y - 2, 308, 20, 0xFD80))      // amber row outline
        pkt(16, listOf(14, y + 2, 1, 0xFFFF, 0x0000), text = label)
        y += 24
    }
    return out.toByteArray()
}

@Composable
private fun IrBrowserPreview() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            ScreenHeader("IR catalog")
            Spacer(Modifier.height(8.dp))
            Text("Categories / TVs", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            IrRow("TVs", "378 devices", cs)
            IrRow("Air conditioners", "98 devices", cs)
            IrRow("LED lighting", "152 devices", cs)
            IrRow("Audio/Video receivers", "96 devices", cs)
            IrRow("Soundbars", "93 devices", cs)
            IrRow("Projectors", "109 devices", cs)
            IrRow("Fans", "89 devices", cs)
        }
    }
}

@Composable
private fun IrRow(title: String, sub: String, cs: androidx.compose.material3.ColorScheme) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Text(">", color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun CrackDownloadsPreview() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            ScreenHeader("WPA Crack")
            Spacer(Modifier.height(12.dp))
            Text("Download real wordlists", color = cs.primary, fontWeight = FontWeight.SemiBold)
            Text("rockyou and SecLists, straight to the phone", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            DlCard("rockyou.txt", "14.3M entries  -  ~139MB", "The classic real-world password dump", "Download", true)
            DlCard("SecLists top 1M", "1M entries  -  ~8MB", "Most common passwords, high hit rate", "Download", false)
            DlCard("SecLists top 100k", "100k entries  -  ~0MB", "Quick pass of the 100k most common", "Download", false)
            DlCard("Probable top 12k", "12k entries  -  ~0MB", "Highest-probability for a fast first try", "Crack", false)
        }
    }
}

@Composable
private fun DlCard(title: String, meta: String, desc: String, action: String, downloading: Boolean) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (action == "Crack") OutlinedButton(onClick = {}) { Text("Crack") }
                else Button(onClick = {}) { Text(action) }
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (downloading) {
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { 0.37f },
                    modifier = Modifier.fillMaxWidth(),
                    color = cs.primary,
                    trackColor = cs.surfaceVariant,
                )
                Text("51MB / 139MB", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ScreenHeader(subtitle: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Image(painter = painterResource(R.drawable.ic_flame), contentDescription = null, modifier = Modifier.size(26.dp))
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Text(subtitle, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CrackPreview() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            ScreenHeader("WPA Crack")
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("handshake  -  \"domotica\"", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text("WPA2  -  EAPOL M1-M4  -  captured on device", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("rockyou.txt  -  14,344,392 candidates", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { 0.60f },
                modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)),
                color = cs.primary,
                trackColor = cs.surfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("60.1%", color = cs.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                Text("91,400 keys/s", color = cs.secondary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("[+] PMK derived (PBKDF2-SHA1 x4096)", color = Color(0xFF6EE68C), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("try: summer2024 ... miss", color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("try: password1 ... miss", color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("try: domotica99 ... miss", color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("MIC check: 8f2a... vs a91c...", color = cs.secondary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Built-in list") }
                OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Wordlists") }
            }
        }
    }
}

@Composable
private fun NfcPreview() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            ScreenHeader("NFC Security")
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("MIFARE Classic 1K", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text("UID 04:A2:2B:9C  -  SAK 08  -  ATQA 0004", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Text("16 sectors  -  Crypto-1", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Clone risk: HIGH", color = cs.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text("default keys present  -  weak cipher (Crypto-1)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Findings", color = cs.onSurfaceVariant, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            Finding("Sector 0 key = FFFFFFFFFFFF", "factory default key A", cs.tertiary)
            Finding("Crypto-1 is broken (nested)", "recoverable with known attacks", cs.secondary)
            Finding("12 / 65 keys from dictionary", "mifare_keys.txt on the phone", cs.secondary)
            Spacer(Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Read tag") }
                OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Report") }
            }
        }
    }
}

@Composable
private fun Finding(title: String, sub: String, dot: androidx.compose.ui.graphics.Color) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(dot))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WardrivePreview() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Image(painter = painterResource(R.drawable.ic_flame), contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
                    Text("Wardrive", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant), shape = RoundedCornerShape(14.dp)) {
                    Text("Connected", color = cs.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("42", "unique APs", cs.secondary, Modifier.weight(1f))
                StatTile("7", "GPS fixes", cs.primary, Modifier.weight(1f))
                StatTile("ch 6", "best 2.4G", cs.onBackground, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            ApCard("Lord of the pings", "F8:0D:A9:1E:32:57  ch11  -50dBm  wpa2/wpa3")
            ApCard("FASTWEB-Q7U3EN", "E4:4E:12:7E:CF:6F  ch2  -51dBm  wpa2/wpa3")
            ApCard("domotica", "3A:07:16:3B:4B:A4  ch6  -65dBm  wpa2")
            Spacer(Modifier.weight(1f))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Map") }
                OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Export CSV") }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = color)
            Text(label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun ApCard(ssid: String, line: String) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp)) {
            Text(ssid, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------
// Headline-screen previews. These mirror the real (file-private) screen
// composables in MainActivity.kt / MitmUi.kt with static sample data, so the
// whole app surface is captured for visual review without a ViewModel/device.
// ---------------------------------------------------------------------------

/** Compact status bar that mirrors the real TopStatusBar (connect state + battery). */
@Composable
private fun PreviewStatusBar(connected: Boolean, battery: Int = 83) {
    val cs = MaterialTheme.colorScheme
    val label = if (connected) "Connected" else "Tap to connect"
    val color = if (connected) Color(0xFF4CAF50) else cs.primary
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painter = painterResource(R.drawable.ic_flame), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(6.dp))
            Text("Brucia", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.size(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
            Spacer(Modifier.size(10.dp))
            Text("$battery%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.surfaceVariant))
    }
}

@Composable
private fun ToolCardPreview(title: String, desc: String, available: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (available) cs.surface else cs.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (available) cs.onSurface else cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!available) Text("not available", style = MaterialTheme.typography.labelSmall, color = cs.error)
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun CategoryHeader(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun HomeDashboardConnectedPreview() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PreviewStatusBar(connected = true)
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                CategoryHeader("Wi-Fi")
                ToolCardPreview("Wardrive", "Scan and map nearby access points")
                ToolCardPreview("Crack", "Crack captured handshakes and PMKIDs")
                ToolCardPreview("Monitor", "Monitor your own network - live packet list and pcap export")
                Spacer(Modifier.height(10.dp))
                CategoryHeader("Sub-GHz")
                ToolCardPreview("RF", "Capture, decode and replay sub-GHz signals")
                Spacer(Modifier.height(10.dp))
                CategoryHeader("2.4GHz")
                ToolCardPreview("NRF24", "2.4GHz scanner, ESB sniffer and MouseJack")
            }
        }
    }
}

@Composable
private fun HomeDashboardDisconnectedPreview() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PreviewStatusBar(connected = false)
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("Device not connected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                        Spacer(Modifier.height(4.dp))
                        Text("Plug the device into this phone over USB to start using the tools.", style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
                    }
                }
                CategoryHeader("Wi-Fi")
                ToolCardPreview("Wardrive", "Scan and map nearby access points")
                ToolCardPreview("Crack", "Crack captured handshakes and PMKIDs")
                Spacer(Modifier.height(10.dp))
                CategoryHeader("NFC")
                ToolCardPreview("NFC", "Read, emulate and attack NFC tags")
            }
        }
    }
}

@Composable
private fun SpamPlatformCardPreview(title: String, desc: String) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = cs.primary)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant)) {
                Text("Duration (seconds): 10", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Start") }
            Spacer(Modifier.height(6.dp))
            Text("Authorized / lawful use only. Broadcasting pairing popups may be illegal without permission.", style = MaterialTheme.typography.bodySmall, color = cs.error)
        }
    }
}

@Composable
private fun SpamHubPreview() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
                Spacer(Modifier.weight(1f))
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant), shape = RoundedCornerShape(14.dp)) {
                    Text("Connected", color = cs.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            Text("Spam hub - pick a target platform; the app picks the transport.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            SpamPlatformCardPreview("Apple / iOS / Mac", "BLE Continuity / ProximityPair: fake AirPods, AppleTV and Setup-new-device prompts.")
            SpamPlatformCardPreview("Android", "BLE Fast Pair: Google pairing popups.")
            SpamPlatformCardPreview("Windows / PC", "BLE Swift Pair popups.")
            SpamPlatformCardPreview("Samsung", "BLE EasySetup / Buds popups.")
        }
    }
}

@Composable
private fun PacketRowPreview(index: Int, label: String, ts: String, len: Int, rssi: Int, eapol: Boolean, dst: String, src: String, bssid: String, ch: Int) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#$index", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.width(52.dp))
                Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = if (eapol) cs.primary else cs.onSurface, modifier = Modifier.weight(1f))
                Text(ts, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Text("${len}B", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                RssiPillPreview(rssi)
            }
            Text("dst $dst  src $src", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            Text("bssid $bssid  ch $ch", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun RssiPillPreview(rssi: Int) {
    val cs = MaterialTheme.colorScheme
    val color = when { rssi >= -55 -> Color(0xFF4CAF50); rssi >= -70 -> Amber; else -> cs.error }
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant), shape = RoundedCornerShape(10.dp)) {
        Text("$rssi", color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun WifiMonitorPreview() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Image(painter = painterResource(R.drawable.ic_flame), contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(8.dp))
                Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            }
            Text("Monitor my network - live 802.11 packet list and pcap export", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.errorContainer.copy(alpha = 0.5f))) {
                Text("MONITOR YOUR OWN NETWORK. Capture only networks you own or are explicitly authorized to test.", style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("128", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = cs.primary)
                Spacer(Modifier.size(6.dp))
                Text("frames", color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                Spacer(Modifier.size(14.dp))
                Text("4", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = cs.primary)
                Spacer(Modifier.size(6.dp))
                Text("EAPOL (handshake)", color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            PacketRowPreview(128, "EAPOL M3/4", "3.812s", 155, -48, true, "3a:07:16:3b:4b:a4", "f8:0d:a9:1e:32:57", "3a:07:16:3b:4b:a4", 6)
            PacketRowPreview(127, "Data (QoS)", "3.801s", 342, -52, false, "ff:ff:ff:ff:ff:ff", "f8:0d:a9:1e:32:57", "3a:07:16:3b:4b:a4", 6)
            PacketRowPreview(126, "Beacon", "3.744s", 213, -63, false, "ff:ff:ff:ff:ff:ff", "3a:07:16:3b:4b:a4", "3a:07:16:3b:4b:a4", 6)
            PacketRowPreview(125, "Probe Resp", "3.690s", 178, -66, false, "a0:d7:95:1c:04:2f", "3a:07:16:3b:4b:a4", "3a:07:16:3b:4b:a4", 6)
            PacketRowPreview(124, "Deauth", "3.655s", 38, -70, false, "a0:d7:95:1c:04:2f", "3a:07:16:3b:4b:a4", "3a:07:16:3b:4b:a4", 6)
        }
    }
}

@Composable
private fun MitmRowPreview(status: Int, method: String, scheme: String, hostPath: String, durationMs: Int, note: String) {
    val cs = MaterialTheme.colorScheme
    val statusColor = when {
        status < 0 -> cs.error
        status in 200..299 -> Color(0xFF4CAF50)
        status in 300..399 -> cs.primary
        else -> cs.error
    }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (status < 0) "ERR" else status.toString(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = statusColor)
                Spacer(Modifier.size(8.dp))
                Text(method, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Spacer(Modifier.size(8.dp))
                Text(scheme, style = MaterialTheme.typography.labelSmall, color = if (scheme.contains("tunnel")) cs.onSurfaceVariant else cs.primary)
                Spacer(Modifier.weight(1f))
                Text("$durationMs ms", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Text(hostPath, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
            Text(note, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun MitmPreview() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("MITM proxy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = cs.errorContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Authorized use only", fontWeight = FontWeight.Bold, color = cs.onErrorContainer)
                    Text("This terminates TLS to read HTTPS traffic. Use it only on devices and networks you own or are explicitly authorized to test.", style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Root certificate", fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    Text("To decrypt HTTPS, install this CA on your own target device (Settings > Security > Install a certificate > CA certificate).", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Export CA certificate (.crt)") }
                    Spacer(Modifier.height(6.dp))
                    Text("Note: apps using HSTS or certificate pinning will refuse interception even with the CA installed. Those connections appear in the log as non-decrypted tunnels.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Transactions (2)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Spacer(Modifier.height(4.dp))
            MitmRowPreview(200, "GET", "https", "example.com/api/v2/profile", 182, "req 412 B   resp 1944 B")
            MitmRowPreview(-1, "CONNECT", "https (tunnel)", "hsts-pinned.bank.example/", 63, "CONNECT tunnel (not decrypted)")
        }
    }
}

@Composable
private fun Nrf24ChannelBar(ch: Int, level: Int, max: Int) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text("ch $ch", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.width(44.dp))
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(level.toFloat() / max).height(10.dp).clip(RoundedCornerShape(4.dp)).background(cs.primary))
        }
        Spacer(Modifier.size(6.dp))
        Text("$level", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.width(32.dp))
    }
}

@Composable
private fun Nrf24DevicePreview(addr: String, ch: Int, count: Int, rssi: Int) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(addr, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                Row {
                    Text("ch $ch", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    Text("x$count", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
            RssiPillPreview(rssi)
        }
    }
}

@Composable
private fun Nrf24Preview() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
                Spacer(Modifier.weight(1f))
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant), shape = RoundedCornerShape(14.dp)) {
                    Text("Connected", color = cs.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            Text("2.4GHz / NRF24L01 - scanner, ESB sniffer and MouseJack", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("2.4GHz scanner", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text("Per-channel RF activity via nrf24_scan.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    val levels = listOf(3 to 12, 17 to 71, 32 to 44, 45 to 90, 62 to 33, 78 to 58)
                    val max = levels.maxOf { it.second }
                    levels.forEach { (ch, lvl) -> Nrf24ChannelBar(ch, lvl, max) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("ESB devices", fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Nrf24DevicePreview("0xA1:B2:C3:D4:E5", 45, 214, -48)
            Nrf24DevicePreview("0x12:34:56:78:9A", 62, 88, -61)
            Nrf24DevicePreview("0xDE:AD:BE:EF:00", 17, 31, -72)
        }
    }
}

/** Mirror of spectrumColor in MainActivity (cold -> warm -> hot RSSI ramp). */
private fun previewSpectrumColor(v: Float): Color {
    val x = v.coerceIn(0f, 1f)
    val cold = Color(0xFF0A1026)
    val cool = Color(0xFF2B4C9B)
    val warm = Color(0xFFFF8A00)
    val hot = Color(0xFFFF2A2A)
    return when {
        x < 0.4f -> lerp(cold, cool, x / 0.4f)
        x < 0.75f -> lerp(cool, warm, (x - 0.4f) / 0.35f)
        else -> lerp(warm, hot, (x - 0.75f) / 0.25f)
    }
}

@Composable
private fun SpectrumWaterfallPreview(history: List<IntArray>, modifier: Modifier) {
    Canvas(modifier = modifier) {
        if (history.isEmpty()) return@Canvas
        var mn = Int.MAX_VALUE
        var mx = Int.MIN_VALUE
        for (s in history) for (r in s) { if (r < mn) mn = r; if (r > mx) mx = r }
        val range = (mx - mn).coerceAtLeast(1).toFloat()
        val rowH = size.height / history.size
        history.forEachIndexed { idx, sweep ->
            val n = sweep.size
            if (n == 0) return@forEachIndexed
            val y = idx * rowH
            val cellW = size.width / n
            for (i in 0 until n) {
                val value = (sweep[i] - mn) / range
                drawRect(color = previewSpectrumColor(value), topLeft = Offset(i * cellW, y), size = Size(cellW + 1f, rowH + 1f))
            }
        }
    }
}

@Composable
private fun SignalCardPreview(protocol: String, classification: String, codeHex: String, bits: Int, te: Int, repeats: Int, serial: String, button: Int, note: String) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(protocol, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                val rolling = classification == "rolling"
                Text(classification.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (rolling) Amber else cs.primary)
            }
            Text("0x$codeHex", style = MaterialTheme.typography.titleMedium, color = cs.primary, fontFamily = FontFamily.Monospace)
            Text("$bits bits  -  TE $te us  -  x$repeats", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (serial.isNotEmpty()) {
                Text("serial 0x$serial" + if (button >= 0) "  -  button $button" else "", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (note.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = Amber)
            }
        }
    }
}

@Composable
private fun SubGhzPreview() {
    val cs = MaterialTheme.colorScheme
    // Synthetic sweeps: a travelling peak over the band.
    val history = (0 until 24).map { row ->
        IntArray(48) { i ->
            val peak = (row + i) % 48
            val d = kotlin.math.abs(i - peak)
            (-95 + (60 * kotlin.math.exp(-(d * d) / 18.0))).toInt()
        }
    }
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
                Spacer(Modifier.weight(1f))
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant), shape = RoundedCornerShape(14.dp)) {
                    Text("Connected", color = cs.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            Text("Sub-GHz RF - capture, decode and replay", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Spectrum waterfall", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text("subghz.spectrum stream - sweeps over time, colour = RSSI", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    SpectrumWaterfallPreview(history, Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(6.dp)))
                    Spacer(Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("433.05 MHz", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text("48 pts - 24 sweeps", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text("434.79 MHz", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Decoded signals", fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            SignalCardPreview("Princeton", "fixed", "1C5A3E", 24, 350, 6, "", -1, "")
            SignalCardPreview("KeeLoq", "rolling", "9F3C21A0", 66, 400, 3, "12AB34", 2, "Rolling code - cannot be replayed; capture is for analysis only.")
        }
    }
}

@Composable
private fun ReportPreview() {
    val cs = MaterialTheme.colorScheme
    val insights = listOf(
        "42 APs seen; 3 open networks worth a closer look.",
        "Channel 6 is the most congested (2.4GHz) - 11 APs overlap.",
        "1 WPA2 handshake captured - crackable offline.",
        "2 BLE devices broadcast resolvable names (vendor: Apple).",
    )
    val report = listOf(
        "# Brucia session report",
        "generated 2026-10-28 14:12",
        "",
        "## Wi-Fi",
        "access points .......... 42",
        "open networks .......... 3",
        "handshakes captured .... 1",
        "",
        "## Sub-GHz",
        "signals decoded ........ 2 (1 fixed, 1 rolling)",
        "",
        "## BLE",
        "devices ................ 2",
    )
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Image(painter = painterResource(R.drawable.ic_flame), contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(8.dp))
                Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            }
            Text("Session report - the phone aggregates everything captured", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Generate report (.md)") }
                OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Export .txt") }
            }
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Insights (heuristic)", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text("Rule-of-thumb analysis of the captured data - not machine learning. Hints only.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    for (i in insights) {
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("- ", color = cs.primary, style = MaterialTheme.typography.bodySmall)
                            Text(i, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Report preview", fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Card(modifier = Modifier.fillMaxWidth().weight(1f), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    for (line in report) {
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun BleDevicePreview(name: String, mac: String, vendor: String, rssi: Int) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text("$mac  $vendor", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
            RssiPillPreview(rssi)
        }
    }
}

@Composable
private fun BlePreview() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
                Spacer(Modifier.weight(1f))
                Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant), shape = RoundedCornerShape(14.dp)) {
                    Text("Connected", color = cs.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            Text("BLE scan and spam - device scans/broadcasts, phone lists and resolves vendors", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("BLE spam (Apple popups)", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text("Broadcasts fake Apple ProximityPair pairing popups to nearby devices for 10s. Authorized testing only.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("BLE spam (10s)") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("GATT explorer", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text("4C:87:5D:0A:11:92", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("-", fontWeight = FontWeight.Bold, color = cs.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Service 0x180F (Battery)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp)) {
                        Text("0x2A19", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text("props: r/notify", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Text("value: 0x54 (84%)", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurface)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("-", fontWeight = FontWeight.Bold, color = cs.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Service 0x180A (Device Info)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp)) {
                        Text("0x2A29", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        Text("props: r", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("3 devices", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            BleDevicePreview("Galaxy Buds", "4C:87:5D:0A:11:92", "Samsung", -47)
            BleDevicePreview("(no name)", "F0:21:3C:88:AE:10", "Apple, Inc.", -59)
            BleDevicePreview("Mi Band 7", "C8:47:8C:22:9B:04", "Xiaomi", -71)
        }
    }
}

@Composable
private fun CapabilityDisabledPreview() {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(48.dp))
            Text("Module not available", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onSurface)
            Spacer(Modifier.height(12.dp))
            Text("This device has no LoRa module", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("Attach the required module and reconnect to enable this tool.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}
