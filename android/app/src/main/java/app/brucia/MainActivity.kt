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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brucia.transport.UsbTransport
import app.brucia.wardrive.ApObservation
import app.brucia.wardrive.WardriveViewModel
import app.brucia.wardrive.WigleCsv
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
            MaterialTheme {
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
    val caps by vm.caps.collectAsState()
    val list by vm.aps.collectAsState()
    val wardriving by vm.wardriving.collectAsState()
    val context = LocalContext.current
    var showMap by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Brucia — Wardrive", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Status: $status")
        caps?.let { Text("Device: $it", style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(12.dp))

        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Text("Connect over USB")
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Wardriving (wifi.ap stream)")
            Switch(checked = wardriving, onCheckedChange = { vm.setWardriving(it) })
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Unique APs: ${list.size}")
            OutlinedButton(onClick = { showMap = !showMap }) {
                Text(if (showMap) "List" else "Map")
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(
                enabled = list.isNotEmpty(),
                onClick = {
                    val csv = WigleCsv.build(list)
                    val file = File(
                        context.getExternalFilesDir(null),
                        "brucia_wardrive_${System.currentTimeMillis()}.csv"
                    )
                    file.writeText(csv)
                    Toast.makeText(context, "Saved ${file.absolutePath}", Toast.LENGTH_LONG).show()
                }
            ) { Text("Export CSV") }
            OutlinedButton(enabled = list.isNotEmpty(), onClick = { vm.clearSession() }) {
                Text("Clear")
            }
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
private fun ApCard(ap: ApObservation) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(ap.ssid.ifEmpty { "(hidden)" }, style = MaterialTheme.typography.titleMedium)
            Text("${ap.bssid}  •  ch ${ap.channel}  •  ${ap.rssi} dBm  •  ${ap.enc}")
            val loc = if (ap.lat != null && ap.lon != null)
                "%.5f, %.5f".format(ap.lat, ap.lon) else "no GPS fix"
            Text(loc, style = MaterialTheme.typography.bodySmall)
        }
    }
}

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
