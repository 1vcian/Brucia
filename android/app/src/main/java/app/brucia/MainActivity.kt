package app.brucia

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brucia.transport.UsbTransport
import app.brucia.ui.Amber
import app.brucia.ui.BruciaTheme
import app.brucia.wardrive.ApObservation
import app.brucia.wardrive.DeviceInfo
import app.brucia.wardrive.WardriveViewModel
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        setContent {
            BruciaTheme {
                val vm: WardriveViewModel = viewModel()
                LaunchedEffect(Unit) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    vm.startLocation()
                }
                BruciaScreen(vm, onConnect = { connectUsb(vm) })
            }
        }
    }

    private fun connectUsb(vm: WardriveViewModel) {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            toast("No USB serial device found")
            return
        }
        val driver = drivers[0]
        if (usbManager.hasPermission(driver.device)) {
            openPort(vm, driver)
            return
        }
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    openPort(vm, driver)
                } else {
                    toast("USB permission denied")
                }
                try { unregisterReceiver(this) } catch (_: Exception) {}
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        usbManager.requestPermission(driver.device, pi)
    }

    private fun openPort(vm: WardriveViewModel, driver: UsbSerialDriver) {
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            toast("Could not open USB device")
            return
        }
        try {
            vm.connect(UsbTransport(connection, driver.ports[0]))
            toast("Connected")
        } catch (e: Exception) {
            toast("Open failed: ${e.message}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val ACTION_USB_PERMISSION = "app.brucia.USB_PERMISSION"
    }
}

@Composable
private fun BruciaScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val list by vm.aps.collectAsState()
    val wardriving by vm.wardriving.collectAsState()
    val context = LocalContext.current
    var showMap by remember { mutableStateOf(false) }
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Brucia",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = cs.primary,
            )
            Spacer(Modifier.size(6.dp))
            Text("🔥", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text("Offload companion · wardrive", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        DeviceCard(device)
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (connected) "Reconnect over USB" else "Connect over USB") }
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Wardriving", fontWeight = FontWeight.SemiBold)
                Text("wifi.ap stream", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = wardriving, enabled = connected, onCheckedChange = { vm.setWardriving(it) })
        }
        Spacer(Modifier.height(12.dp))

        // Stats + view toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${list.size}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = cs.primary,
                )
                Spacer(Modifier.size(6.dp))
                Text("unique APs", color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            OutlinedButton(onClick = { showMap = !showMap }) {
                Text(if (showMap) "List" else "Map")
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = list.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val csv = WigleExport(list)
                    val file = File(
                        context.getExternalFilesDir(null),
                        "brucia_wardrive_${System.currentTimeMillis()}.csv"
                    )
                    file.writeText(csv)
                    Toast.makeText(context, "Saved ${file.absolutePath}", Toast.LENGTH_LONG).show()
                }
            ) { Text("Export CSV") }
            OutlinedButton(
                enabled = list.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = { vm.clearSession() }
            ) { Text("Clear") }
        }
        Spacer(Modifier.height(12.dp))

        if (showMap) {
            ApMap(list, Modifier.fillMaxWidth().weight(1f))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(list) { ap -> ApCard(ap) }
            }
        }
    }
}

@Composable
private fun StatusPill(connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val color = if (connected) Color(0xFF4CAF50) else cs.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(6.dp))
        Text(
            if (connected) "Connected" else "Disconnected",
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun DeviceCard(device: DeviceInfo?) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (device == null) {
                Text("No device connected", color = cs.onSurfaceVariant)
                Text(
                    "Plug the T-Embed via USB-C OTG, open Connect → Companion on it, then tap Connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            } else {
                Text(device.board, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${device.chip}  ·  fw ${device.fw}",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                if (device.modules.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        device.modules.forEach { Tag(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(cs.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun ApCard(ap: ApObservation) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    ap.ssid.ifEmpty { "(hidden)" },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    ap.bssid,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                val encColor = if (ap.enc == "open") cs.error else cs.onSurfaceVariant
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ch ${ap.channel}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    Text(ap.enc, style = MaterialTheme.typography.bodySmall, color = encColor)
                    if (ap.lat != null && ap.lon != null) {
                        Spacer(Modifier.size(8.dp))
                        Text("📍", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            RssiPill(ap.rssi)
        }
    }
}

@Composable
private fun RssiPill(rssi: Int) {
    val cs = MaterialTheme.colorScheme
    val color = when {
        rssi >= -60 -> Color(0xFF4CAF50)
        rssi >= -75 -> Amber
        else -> cs.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text("$rssi dBm", style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun WigleExport(list: List<ApObservation>): String =
    app.brucia.wardrive.WigleCsv.build(list)

@Composable
private fun ApMap(aps: List<ApObservation>, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Configuration.getInstance()
                .load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(16.0)
                onResume()
            }
        },
        update = { map ->
            map.overlays.clear()
            var centered = false
            for (ap in aps) {
                val la = ap.lat
                val lo = ap.lon
                if (la != null && lo != null) {
                    val point = GeoPoint(la, lo)
                    val marker = Marker(map)
                    marker.position = point
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    marker.title = ap.ssid.ifEmpty { "(hidden)" }
                    marker.snippet = "${ap.bssid} • ${ap.enc} • ${ap.rssi} dBm"
                    map.overlays.add(marker)
                    if (!centered) {
                        map.controller.setCenter(point)
                        centered = true
                    }
                }
            }
            map.invalidate()
        }
    )
}
