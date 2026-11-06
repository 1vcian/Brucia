package app.brucia

import app.brucia.ui.LeaveEffect
import app.brucia.ui.rememberRetained
import app.brucia.R
import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brucia.transport.PortLease
import app.brucia.transport.UsbPermissionRequests
import app.brucia.transport.releaseUsbPort
import app.brucia.transport.UsbTransport
import app.brucia.update.AppUpdater
import app.brucia.update.EspFlasher
import app.brucia.ui.AdaptiveLayout
import app.brucia.ui.Amber
import app.brucia.ui.CenteredMaxWidth
import app.brucia.ui.BruciaTheme
import app.brucia.audio.Dtmf
import app.brucia.audio.Fft
import app.brucia.audio.Goertzel
import app.brucia.audio.Oscilloscope
import app.brucia.audio.Sstv
import app.brucia.badusb.DuckyLibrary
import app.brucia.ble.BleDevice
import app.brucia.data.SavedItem
import app.brucia.nfc.NdefBuilder
import app.brucia.nfc.NfcSecurity
import app.brucia.wordlist.BundledWordlistUi
import app.brucia.wordlist.WordlistInfo
import app.brucia.subghz.DeBruijn
import app.brucia.subghz.SubGhzEncoder
import app.brucia.subghz.DecodedSignal
import app.brucia.subghz.SubFile
import app.brucia.wardrive.ChannelAdvisor
import app.brucia.wardrive.Finding
import app.brucia.wardrive.GpxExport
import app.brucia.wardrive.KmlExport
import app.brucia.wardrive.SecurityFindings
import app.brucia.ir.IrButton
import app.brucia.ir.IrCatalog
import app.brucia.ir.IrParser
import app.brucia.ir.IrRemoteMap
import app.brucia.ir.RemoteKey
import app.brucia.ir.CatalogDevice
import app.brucia.device.CliCatalog
import app.brucia.wardrive.ApObservation
import app.brucia.wardrive.RiskScore
import app.brucia.wardrive.RssiDistance
import app.brucia.wardrive.DeviceInfo
import app.brucia.wardrive.DEFAULT_SPAM_COPIES
import app.brucia.wardrive.DeviceCaps
import app.brucia.wardrive.CapabilityEvidence
import app.brucia.wardrive.Nrf24Device
import app.brucia.wardrive.Nrf24MjDevice
import app.brucia.wardrive.LoraPacket
import app.brucia.wardrive.EthStatus
import app.brucia.wardrive.ProbeRequest
import app.brucia.wardrive.SpectrumSweep
import app.brucia.wardrive.OuiResolver
import app.brucia.wardrive.WardriveStats
import app.brucia.wardrive.WardriveViewModel
import app.brucia.wardrive.CommandLogEntry
import app.brucia.wardrive.CmdStatus
import app.brucia.wardrive.WigleCsv
import app.brucia.wardrive.WigleUploadStatus
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import androidx.core.net.toUri
import app.brucia.ui.BruciaButtonDefaults
import app.brucia.ui.Button
import app.brucia.ui.OutlinedButton
import app.brucia.ui.TextButton

private enum class View { LIST, MAP, STATS }
private enum class ApSort(val label: String) { STABLE("Found"), NAME("Name"), SIGNAL("Signal"), RISK("Risk") }

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager

    // Kept so lifecycle callbacks (onResume / USB-attach) can drive the link without the Compose tree.
    private var vmRef: WardriveViewModel? = null

    // Fires when a matching USB device is plugged in while the app is already open, so we can
    // auto-connect without the user tapping Connect. Registered in onResume, cleared in onPause.
    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) autoConnectIfReady()
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // USB permission requests, correlated by request id + device and bounded by a deadline. One
    // receiver for the Activity's lifetime (the permission dialog pauses us, so not onResume/onPause).
    private val usbPermissions = UsbPermissionRequests<(UsbPermissionRequests.Outcome) -> Unit>()
    private val usbPermissionTimeouts = android.os.Handler(android.os.Looper.getMainLooper())
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val id = intent.getIntExtra(EXTRA_PERMISSION_REQUEST, -1)
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val result = usbPermissions.resolve(id, device?.deviceName, granted)
            val settle = result.payload
            if (settle == null) app.brucia.BruciaLog.d("USB", "ignored permission result ${result.outcome} for request $id")
            else settle(result.outcome)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Draw edge-to-edge on every API level (Android 15+ enforces it for targetSdk 35+, and
        // targetSdk 36 removes the windowOptOutEdgeToEdgeEnforcement opt-out). The UI is
        // always dark, so both bars keep light icons over a transparent background; AppRoot pads its
        // content by the system bar, cutout and IME insets.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        ContextCompat.registerReceiver(
            this, usbPermissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            val vm: WardriveViewModel = viewModel()
            // The device's UI theme wins: derive the app accent from the connected device's color.
            val dev by vm.device.collectAsState()
            val accent = app.brucia.ui.rgb565ToColor(dev?.themeRgb565 ?: 0)
            BruciaTheme(accent = accent) {
                LaunchedEffect(Unit) {
                    vmRef = vm
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    vm.startLocation()
                    // Device already plugged in (incl. launch via USB_DEVICE_ATTACHED): link up silently.
                    autoConnectIfReady()
                }
                AppRoot(vm, onConnect = { connectUsb(vm) }, onFlash = { flashFirmware(vm) })
                // After a rotation/resize: stop the work of tools that did not come back.
                app.brucia.ui.SettleParkedLeaves()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Catch a device plugged in while we are in the foreground.
        ContextCompat.registerReceiver(
            this, usbAttachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // App brought to the foreground with a device already attached -> link up.
        autoConnectIfReady()
        // If the user just granted "install unknown apps", continue the pending APK install.
        vmRef?.resumeAppInstallIfPending(this)
    }

    override fun onPause() {
        try { unregisterReceiver(usbAttachReceiver) } catch (e: Exception) { app.brucia.BruciaLog.d("USB", "unregister attach receiver: ${e.message}") }
        super.onPause()
    }

    override fun onDestroy() {
        usbPermissionTimeouts.removeCallbacksAndMessages(null)
        try { unregisterReceiver(usbPermissionReceiver) } catch (e: Exception) { app.brucia.BruciaLog.d("USB", "unregister permission receiver: ${e.message}") }
        // Results for these requests can no longer reach this Activity: settle them now.
        usbPermissions.drain().forEach { it(UsbPermissionRequests.Outcome.CANCELLED) }
        super.onDestroy()
    }

    /** Ask for USB permission on [device]; [onResult] runs once with GRANTED, DENIED, TIMED_OUT or CANCELLED. */
    private fun requestUsbPermission(device: UsbDevice, onResult: (UsbPermissionRequests.Outcome) -> Unit) {
        val id = usbPermissions.begin(device.deviceName, USB_PERMISSION_TIMEOUT_MS, onResult)
        // Unique request code per request so the system never merges two pending requests.
        val pi = PendingIntent.getBroadcast(
            this, id, Intent(ACTION_USB_PERMISSION).setPackage(packageName).putExtra(EXTRA_PERMISSION_REQUEST, id),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbPermissionTimeouts.postDelayed({
            usbPermissions.expire(id)?.invoke(UsbPermissionRequests.Outcome.TIMED_OUT)
        }, USB_PERMISSION_TIMEOUT_MS)
        usbManager.requestPermission(device, pi)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) autoConnectIfReady()
    }

    /**
     * Connect automatically when a compatible device is present and we already hold USB permission,
     * without prompting. No-op if we are already connected/connecting, if no device is found, or if
     * permission has not been granted yet (the explicit Connect button handles the first-time grant;
     * a plug-in via the manifest USB_DEVICE_ATTACHED filter grants it automatically).
     */
    private fun autoConnectIfReady() {
        val vm = vmRef ?: return
        // A flash in progress drops companion (status -> Disconnected) and the native-USB ESP32-S3
        // re-enumerates while writing; auto-connecting now would steal the port from the flasher.
        if (vm.isFlashing()) return
        if (!vm.status.value.startsWith("Disconnected")) return // already linked or linking
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull() ?: return
        if (!usbManager.hasPermission(driver.device)) return
        openPort(vm, driver)
    }

    private fun connectUsb(vm: WardriveViewModel) {
        if (vm.isFlashing()) { toast("Firmware update in progress"); return }
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
        requestUsbPermission(driver.device) { outcome ->
            when (outcome) {
                UsbPermissionRequests.Outcome.GRANTED -> openPort(vm, driver)
                UsbPermissionRequests.Outcome.DENIED -> toast("USB permission denied")
                UsbPermissionRequests.Outcome.TIMED_OUT -> toast("USB permission request timed out")
                else -> Unit
            }
        }
    }

    private fun openPort(vm: WardriveViewModel, driver: UsbSerialDriver) {
        // The flasher owns the USB device for the whole update (including the permission wait).
        if (vm.isFlashing()) { toast("Firmware update in progress"); return }
        // One owner of the USB device at a time: attach auto-connect, manual Connect and the flash
        // handoff all claim it by token; this open is refused while another owner holds it.
        val token = vm.beginCompanionLink() ?: run { toast("USB device busy"); return }
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) {
            vm.abortCompanionLink(token)
            toast("Could not open USB device")
            return
        }
        val transport = try {
            UsbTransport(connection, driver.ports[0]) // releases port/connection itself on failure
        } catch (e: Exception) {
            vm.abortCompanionLink(token)
            toast("Open failed: ${e.message}")
            return
        }
        try {
            if (vm.connect(token, transport)) toast("Connected") else toast("USB device busy")
        } catch (e: Exception) {
            vm.disconnect()
            vm.abortCompanionLink(token)
            toast("Open failed: ${e.message}")
        }
    }

    /**
     * Take over the USB port from companion mode and flash the latest firmware via the
     * ESP32 ROM bootloader. Mirrors the USB permission flow used by [connectUsb].
     */
    private fun flashFirmware(vm: WardriveViewModel) {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            toast("No USB serial device found")
            vm.flashNoDevice()
            return
        }
        val driver = drivers[0]
        if (usbManager.hasPermission(driver.device)) {
            startFlash(vm, driver)
            return
        }
        requestUsbPermission(driver.device) { outcome ->
            when (outcome) {
                UsbPermissionRequests.Outcome.GRANTED -> startFlash(vm, driver)
                UsbPermissionRequests.Outcome.DENIED -> toast("USB permission denied")
                UsbPermissionRequests.Outcome.TIMED_OUT -> toast("USB permission request timed out")
                else -> Unit
            }
        }
    }

    private fun startFlash(vm: WardriveViewModel, driver: UsbSerialDriver) {
        // Verify the board BEFORE dropping companion: the safety gate must read the reported board
        // while the link is still up. (Reading it after disconnect() - which clears the device - was
        // the regression that made every flash abort with "supports only the T-Embed".)
        // Auto requires a connected T-Embed. An explicit T-Embed selection also permits recovery
        // without companion identification; flashEnteringBootloader rejects other board images.
        if (vm.releaseUpdates.value.selectedBoard == null) {
            val board = vm.device.value?.board?.lowercase().orEmpty()
            if (!(board.contains("t-embed") && board.contains("cc1101"))) {
                val shown = vm.device.value?.board?.takeIf { it.isNotBlank() } ?: "this board"
                vm.flashError(
                    "In-app firmware update supports only the LilyGo T-Embed CC1101 by default. " +
                        "For \"$shown\", use the matching image with the web flasher."
                )
                return
            }
        }
        // Flashing takes over the serial line: the VM drops companion mode, moves USB ownership and
        // the foreground service + wake lock to this flash operation, and returns its token.
        val token = vm.flashEnteringBootloader() ?: return
        // The T-Embed CC1101 is an ESP32-S3 with the native hardware USB-Serial-JTAG (PID 0x1001).
        // Entering the ROM download loader resets the WHOLE chip, so the USB peripheral drops and
        // RE-ENUMERATES; an already-open handle then goes stale. So we must reset FIRST (on a scratch
        // open of the current port), let it re-enumerate, re-acquire the ROM device, open THAT, and
        // flash WITHOUT resetting again (doReset = false) - a second reset would kill the fresh port.
        Thread {
            resetIntoBootloader(driver)
            try { Thread.sleep(2000) } catch (e: Exception) { app.brucia.BruciaLog.d("Flash", "sleep after reset: ${e.message}") } // let the chip reset + ROM re-enumerate
            val romDriver = waitForEspSerial(9000)
            runOnUiThread {
                if (isDestroyed) {
                    vm.flashAbort(token, "Update screen closed during the bootloader handoff. Unplug/replug and try again.")
                    return@runOnUiThread
                }
                if (romDriver == null) {
                    vm.flashAbort(token, "Device did not enter the bootloader. Unplug/replug and try again, or flash from a PC.")
                    return@runOnUiThread
                }
                if (usbManager.hasPermission(romDriver.device)) openAndFlash(vm, token, romDriver)
                else requestPermThenFlash(vm, token, romDriver)
            }
        }.start()
    }

    /**
     * Pulse the ESP32-S3 USB-Serial-JTAG into the ROM download loader using the esptool
     * "usb_jtag_serial" reset sequence, on a throwaway open of the current port. The chip resets
     * and its USB re-enumerates after this returns; the caller waits and re-acquires the ROM device.
     */
    private fun resetIntoBootloader(driver: UsbSerialDriver) {
        val c = usbManager.openDevice(driver.device) ?: return
        val p = driver.ports.firstOrNull() ?: run {
            try { c.close() } catch (e: Exception) { app.brucia.BruciaLog.d("Flash", "close reset connection: ${e.message}") }
            return
        }
        var opened = false
        try {
            try {
                p.open(c)
                opened = true
                p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                fun dtr(v: Boolean) = try { p.setDTR(v) } catch (e: Exception) { app.brucia.BruciaLog.d("Flash", "setDTR: ${e.message}") }
                fun rts(v: Boolean) = try { p.setRTS(v) } catch (e: Exception) { app.brucia.BruciaLog.d("Flash", "setRTS: ${e.message}") }
                rts(false); dtr(false); Thread.sleep(100)   // idle
                dtr(true); rts(false); Thread.sleep(100)    // set IO0
                rts(true); dtr(false); Thread.sleep(100)    // reset, going through (1,1)
                rts(true); Thread.sleep(100)                // re-assert RTS (host DTR-on-RTS quirk)
                dtr(false); rts(false)                      // release
            } catch (e: Exception) { app.brucia.BruciaLog.d("Flash", "reset pulse: ${e.message}") }
        } finally {
            releaseUsbPort(
                opened,
                closePort = { try { p.close() } catch (e: Exception) { app.brucia.BruciaLog.d("Flash", "close reset port: ${e.message}") } },
                closeConnection = { try { c.close() } catch (e: Exception) { app.brucia.BruciaLog.d("Flash", "close reset connection: ${e.message}") } },
            )
        }
    }

    /** Poll for an ESP serial device (the re-enumerated ROM loader) for up to [timeoutMs]. */
    private fun waitForEspSerial(timeoutMs: Int): UsbSerialDriver? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val d = drivers.firstOrNull { it.device.vendorId == 0x303a } ?: drivers.firstOrNull()
            if (d != null) return d
            try { Thread.sleep(300) } catch (e: Exception) { app.brucia.BruciaLog.d("Flash", "sleep polling ESP serial: ${e.message}") }
        }
        return null
    }

    private fun requestPermThenFlash(vm: WardriveViewModel, token: Long, driver: UsbSerialDriver) {
        requestUsbPermission(driver.device) { outcome ->
            when (outcome) {
                UsbPermissionRequests.Outcome.GRANTED -> openAndFlash(vm, token, driver)
                UsbPermissionRequests.Outcome.DENIED -> vm.flashAbort(token, "USB permission denied for the bootloader device.")
                UsbPermissionRequests.Outcome.TIMED_OUT -> vm.flashAbort(token, "USB permission for the bootloader device timed out.")
                UsbPermissionRequests.Outcome.CANCELLED -> vm.flashAbort(token, "Update screen closed while waiting for USB permission. Try again.")
                else -> Unit
            }
        }
    }

    private fun openAndFlash(vm: WardriveViewModel, token: Long, driver: UsbSerialDriver) {
        val connection = usbManager.openDevice(driver.device)
        if (connection == null) { vm.flashAbort(token, "Could not open the bootloader device."); return }
        val port = driver.ports[0]
        var opened = false
        // Every path below releases the port + connection through this one lease (exactly once);
        // closing an opened port also closes its connection (see releaseUsbPort).
        val lease = PortLease {
            releaseUsbPort(
                opened,
                closePort = { try { port.close() } catch (e: Exception) { app.brucia.BruciaLog.d("Flash", "close port: ${e.message}") } },
                closeConnection = { try { connection.close() } catch (e: Exception) { app.brucia.BruciaLog.d("Flash", "close connection: ${e.message}") } },
            )
        }
        try {
            port.open(connection)
            opened = true
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val io = EspFlasher.UsbSerialIo(port)
            // The device was already pulsed into the ROM loader by resetIntoBootloader() before this
            // port was (re-)opened, so the flasher must NOT reset again (that would re-enumerate and
            // kill this fresh handle). It just SYNCs on the already-in-download-mode port.
            vm.flashFirmware(token, io, lease, skipBoardCheck = true, doReset = false)
        } catch (e: Exception) {
            lease.close()
            vm.flashAbort(token, "Flash open failed: ${e.message}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val ACTION_USB_PERMISSION = "app.brucia.USB_PERMISSION"
        const val EXTRA_PERMISSION_REQUEST = "app.brucia.USB_PERMISSION_REQUEST"
        const val USB_PERMISSION_TIMEOUT_MS = 60_000L
    }
}

@Composable
private fun BruciaScreen(vm: WardriveViewModel, onConnect: () -> Unit, onGoCrack: () -> Unit = {}) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val list by vm.aps.collectAsState()
    val track by vm.track.collectAsState()
    val stats by vm.stats.collectAsState()
    val wardriving by vm.wardriving.collectAsState()
    val deauthActive by vm.deauthActive.collectAsState()
    val deauthTarget by vm.deauthTarget.collectAsState()
    val context = LocalContext.current
    var view by rememberSaveable { mutableStateOf(View.LIST) }
    var sortMode by rememberSaveable { mutableStateOf(ApSort.STABLE) }
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme

    // Stable "Found" order: remember each AP by first-sighting and only APPEND new ones, so the list
    // never reorders under the user (re-sightings update timeMillis in the DB but must not move rows -
    // otherwise an expanded/opened row would jump to a different network). Explicit sorts are applied
    // to a copy for display only.
    val foundOrder = rememberRetained { mutableStateListOf<String>() }
    LaunchedEffect(list) { for (ap in list) if (ap.bssid !in foundOrder) foundOrder.add(ap.bssid) }
    val displayList = remember(list, sortMode, foundOrder.size) {
        when (sortMode) {
            ApSort.STABLE -> list.sortedBy { foundOrder.indexOf(it.bssid).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            ApSort.NAME -> list.sortedBy { it.ssid.lowercase().ifBlank { "￿" + it.bssid } }
            ApSort.SIGNAL -> list.sortedByDescending { it.rssi }
            ApSort.RISK -> list.sortedByDescending { app.brucia.wardrive.RiskScore.of(it).score }
        }
    }

    // One LazyColumn so the whole screen scrolls (no nested same-axis scroll): the controls
    // live in a single header item and the AP list items follow.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.background)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        item {
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary), 
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Brucia",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = cs.primary,
            )
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text(
            "Offload companion - wardrive",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))

        DeviceCard(device)
        Spacer(Modifier.height(12.dp))

        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Text(if (connected) "Reconnect over USB" else "Connect over USB")
        }
        Spacer(Modifier.height(12.dp))

        StreamToggleRow("Wardriving", "wifi.ap stream", checked = wardriving, enabled = connected,
            onCheckedChange = { vm.setWardriving(it) })
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
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
        }
        Spacer(Modifier.height(8.dp))

        SegmentedView(view) { view = it }
        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = list.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val f = File(context.getExternalFilesDir(null), "brucia_${System.currentTimeMillis()}.csv")
                    f.writeText(WigleCsv.build(list))
                    shareFile(context, f, "text/csv")
                }
            ) { Text("CSV") }
            Button(
                enabled = list.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val f = File(context.getExternalFilesDir(null), "brucia_${System.currentTimeMillis()}.kml")
                    f.writeText(KmlExport.build(list))
                    shareFile(context, f, "application/vnd.google-earth.kml+xml")
                }
            ) { Text("KML") }
            Button(
                enabled = track.size >= 2,
                modifier = Modifier.weight(1f),
                onClick = {
                    val f = File(context.getExternalFilesDir(null), "brucia_${System.currentTimeMillis()}.gpx")
                    f.writeText(GpxExport.build(track))
                    shareFile(context, f, "application/gpx+xml")
                }
            ) { Text("GPX") }
            OutlinedButton(
                enabled = list.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = { vm.clearSession() }
            ) { Text("Clear") }
        }
        Spacer(Modifier.height(12.dp))

        WigleUploadCard(vm, hasData = list.isNotEmpty())
        Spacer(Modifier.height(12.dp))

        BeaconSpamCard(vm, connected = connected)
        Spacer(Modifier.height(12.dp))
        }
        }

        when (view) {
            View.MAP -> item { ApMap(list, track, Modifier.fillMaxWidth().height(400.dp)) }
            View.STATS -> item { StatsView(stats, list, Modifier.fillMaxWidth().height(520.dp)) }
            View.LIST -> {
                if (list.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Sort:", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        ApSort.entries.forEach { m ->
                            val on = sortMode == m
                            if (on) Button(onClick = { sortMode = m }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                            else OutlinedButton(onClick = { sortMode = m }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
                items(displayList, key = { it.bssid }) { ap ->
                    val deauthing = deauthActive && deauthTarget == ap.bssid
                    ApCard(
                        ap,
                        deauthing = deauthing,
                        onDeauth = { if (deauthing) vm.stopDeauth() else vm.startDeauth(ap.bssid, ap.channel) },
                        onCapture = { vm.captureAndCrack(ap.bssid, ap.channel, ap.ssid); onGoCrack() },
                        onPmkid = { vm.capturePmkidAndCrack(ap.bssid, ap.channel, ap.ssid); onGoCrack() },
                    )
                }
            }
        }
    }
}

@Composable
private fun WigleUploadCard(vm: WardriveViewModel, hasData: Boolean) {
    val cs = MaterialTheme.colorScheme
    val status by vm.wigleStatus.collectAsState()
    var apiName by remember { mutableStateOf(vm.wigleApiName()) }
    var apiToken by remember { mutableStateOf(vm.wigleApiToken()) }
    val uploading = status is WigleUploadStatus.Uploading

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("WiGLE upload", fontWeight = FontWeight.SemiBold)
            Text(
                "Get your API Name and Token at wigle.net (Account -> Show my token).",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiName,
                onValueChange = { apiName = it },
                label = { Text("API Name") },
                singleLine = true,
                enabled = !uploading,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiToken,
                onValueChange = { apiToken = it },
                label = { Text("API Token") },
                singleLine = true,
                enabled = !uploading,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = hasData && !uploading && apiName.isNotBlank() && apiToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.uploadToWigle(apiName, apiToken) },
            ) { Text(if (uploading) "Uploading..." else "Upload to WiGLE") }

            val statusLine: Pair<String, Color>? = when (val s = status) {
                is WigleUploadStatus.Idle ->
                    if (!hasData) "No wardrive APs collected yet" to cs.onSurfaceVariant else null
                is WigleUploadStatus.Uploading -> "Uploading..." to cs.onSurfaceVariant
                is WigleUploadStatus.Success -> s.message to cs.primary
                is WigleUploadStatus.Error -> s.message to cs.error
            }
            if (statusLine != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    statusLine.first,
                    color = statusLine.second,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Default fake-network name(s) pre-filled in the beacon-spam editor. */
private val defaultBeaconSsids = listOf(
    BrandLinks.SITE_DISPLAY,
)

/** Four zero-width characters, valid in an SSID but invisible, used to make same-looking names unique. */
private val INVISIBLE_SSID_CHARS = charArrayOf('​', '‌', '‍', '⁠')

/**
 * Expand [bases] into [count] distinct SSID byte-strings that all look identical: each gets a suffix
 * built from 4 invisible characters (4 positions x 4 chars = 256 unique combinations), so a scanner
 * sees [count] separate networks sharing one visible name instead of deduping them into one. Bases
 * are kept within 20 bytes so base + 12-byte suffix stays under the 32-byte SSID limit.
 */
private fun beaconSsidVariants(bases: List<String>, count: Int): List<String> {
    fun invisibleSuffix(n: Int): String {
        val sb = StringBuilder(4)
        var v = n
        repeat(4) { sb.append(INVISIBLE_SSID_CHARS[v and 3]); v = v shr 2 } // base-4 over 4 positions
        return sb.toString()
    }
    val trimmed = bases.map { if (it.toByteArray(Charsets.UTF_8).size > 20) it.take(20) else it }
    val out = ArrayList<String>(count)
    var variant = 0
    while (out.size < count) {
        for (b in trimmed) {
            if (out.size >= count) break
            out += b + invisibleSuffix(variant)
        }
        variant++
        if (variant >= 256) break // 256 invisible combinations exhausted
    }
    return out
}

@Composable
private fun BeaconSpamCard(vm: WardriveViewModel, connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var ssidText by rememberSaveable { mutableStateOf(defaultBeaconSsids.joinToString("\n")) }
    var copiesText by rememberSaveable { mutableStateOf("128") }
    val active by vm.beaconActive.collectAsState()
    val status by vm.beaconStatus.collectAsState()

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Beacon spam (fake networks)", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Text(
                "Broadcasts beacon frames advertising these Wi-Fi network names, continuously until you Stop. Whether a nearby device lists them depends on its Wi-Fi scanner and is not verified here. Type one SSID per line, and set how many fake APs to broadcast - each copy gets an invisible suffix so the bytes differ while the visible name stays the same (up to 256).",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ssidText,
                onValueChange = { ssidText = it },
                label = { Text("SSID list (one per line)") },
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = copiesText,
                onValueChange = { copiesText = it.filter { c -> c.isDigit() }.take(3) },
                singleLine = true,
                label = { Text("Number of fake APs (1-256)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (active) {
                        vm.stopBeaconSpam()
                    } else {
                        val manual = ssidText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        val bases = manual.ifEmpty { listOf("Brucia") }
                        // "Number of fake APs" = how many networks to broadcast. Wi-Fi scanners dedupe
                        // by the SSID bytes, so to show up as N separate entries WITH THE SAME VISIBLE
                        // NAME we append a combination of 4 zero-width (invisible) characters: the name
                        // looks identical but the bytes differ, so scanners list each as its own AP.
                        // 4 chars over 4 positions = 4^4 = 256 distinct invisible suffixes per base.
                        val count = (copiesText.toIntOrNull() ?: 128).coerceIn(1, 256)
                        val list = beaconSsidVariants(bases, count)
                        vm.startBeaconSpam(list)
                        Toast.makeText(context, "Beacon spam: ${list.size} SSID(s) requested (Stop to end)", Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text(if (active) "Stop beacon spam" else "Start beacon spam") }
            if (!connected) {
                Text("Connect the device to run beacon spam.", style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(top = 4.dp))
            }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Authorized / lawful use only. Broadcasting fake networks may be illegal without permission.",
                style = MaterialTheme.typography.bodySmall, color = cs.error,
            )
        }
    }
}

@Composable
private fun SegmentedView(current: View, onSelect: (View) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SegButton("List", current == View.LIST, Modifier.weight(1f)) { onSelect(View.LIST) }
        SegButton("Map", current == View.MAP, Modifier.weight(1f)) { onSelect(View.MAP) }
        SegButton("Stats", current == View.STATS, Modifier.weight(1f)) { onSelect(View.STATS) }
    }
}

@Composable
private fun SegButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
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
                    "Plug the T-Embed via USB-C OTG, open Connect / Companion on it, then tap Connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(device.board, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (device.battery in 0..100) {
                        val batColor = when { device.battery <= 15 -> cs.error; device.battery <= 40 -> Amber; else -> Color(0xFF4CAF50) }
                        Text("${device.battery}%", fontWeight = FontWeight.Bold, color = batColor, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text(
                    "${device.chip}  -  fw ${device.fw}" + (device.proto?.let { "  -  protocol $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                // Release provenance (CAPS d.build); older firmware shows "not reported".
                device.buildLines().forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
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

/**
 * Per-module evidence from CAPS d.evidence: compiled / configured / probed / initialized, the probe
 * outcome and static pin conflicts. Older firmware shows one fallback line. Nothing here is a
 * physical test result.
 */
@Composable
private fun CapabilityEvidenceCard(device: DeviceInfo?) {
    if (device == null) return
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Hardware evidence", fontWeight = FontWeight.SemiBold, color = cs.primary)
            val evidence = device.evidence
            if (evidence == null) {
                Text("Evidence: not reported by this firmware", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            } else {
                Text(
                    "What the firmware checked this session. Detection is not a physical test.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                evidence.rows().forEach { row ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.label, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(row.status, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                    row.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                    row.conflicts.forEach { Text("Conflict: $it", style = MaterialTheme.typography.bodySmall, color = cs.error) }
                }
            }
        }
    }
}

/**
 * Recovery diagnostics: app link counters (malformed frames skipped, timeouts, unmatched replies)
 * and, on firmware with `features.diag`, the device counters fetched only when Refresh is tapped.
 * Nothing here is sent automatically or proves hardware health.
 */
@Composable
private fun DiagnosticsCard(vm: WardriveViewModel, device: DeviceInfo?) {
    val cs = MaterialTheme.colorScheme
    val appDiag by vm.appDiag.collectAsState()
    val deviceDiag by vm.deviceDiag.collectAsState()
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Diagnostics", fontWeight = FontWeight.SemiBold, color = cs.primary, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = { vm.refreshDiagnostics() },
                    enabled = deviceDiag !is app.brucia.wardrive.DeviceDiagState.Loading,
                ) { Text("Refresh") }
            }
            Text(
                "Local counters, fetched only on Refresh. Included in log exports and session reports.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            val deviceLines = if (device == null) listOf("Device diagnostics: no device connected")
                else app.brucia.wardrive.Diagnostics.deviceLines(deviceDiag)
            (appDiag.lines() + deviceLines).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
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
private fun ApCard(ap: ApObservation, deauthing: Boolean = false, onDeauth: () -> Unit = {}, onCapture: () -> Unit = {}, onPmkid: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val vendor = OuiResolver.vendor(ap.bssid)
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = cs.surface),
    ) {
      Column {
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
                    ap.bssid + (if (vendor != null) "  $vendor" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                val encColor = if (ap.enc == "open") cs.error else cs.onSurfaceVariant
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ch ${ap.channel}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    Text(ap.enc, style = MaterialTheme.typography.bodySmall, color = encColor)
                    Spacer(Modifier.size(8.dp))
                    val risk = RiskScore.of(ap)
                    val riskColor = when (risk.level) { "high" -> cs.error; "medium" -> Amber; else -> Color(0xFF4CAF50) }
                    Text("risk ${risk.score}", style = MaterialTheme.typography.labelSmall, color = riskColor)
                    Spacer(Modifier.size(8.dp))
                    Text("x${ap.seen}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    Text(RssiDistance.label(ap.rssi), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    if (ap.lat != null && ap.lon != null) {
                        Spacer(Modifier.size(8.dp))
                        Text("~GPS", style = MaterialTheme.typography.labelSmall, color = cs.primary)
                    }
                }
            }
            RssiPill(ap.rssi)
        }
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Text(
                    "Authorized testing only. Actions run on the device against ch ${ap.channel}.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onCapture(); Toast.makeText(context, "Capturing handshake...", Toast.LENGTH_SHORT).show() },
                ) { Text("Capture + crack") }
                Text(
                    "Deauths, captures the 4-way handshake, pulls it to the phone and loads it into the Crack tab automatically.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPmkid(); Toast.makeText(context, "Capturing PMKID...", Toast.LENGTH_SHORT).show() },
                ) { Text("PMKID (clientless)") }
                Text(
                    "Clientless: grabs the PMKID from the AP (no connected client needed), pulls it and loads the Crack tab.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onDeauth()
                        Toast.makeText(context, if (deauthing) "Deauth stopped" else "Deauth ${ap.bssid}...", Toast.LENGTH_SHORT).show()
                    },
                    colors = BruciaButtonDefaults.buttonColors(containerColor = cs.error),
                ) { Text(if (deauthing) "Stop deauth" else "Deauth (continuous)") }
            }
        }
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

@Composable
private fun StatsView(stats: WardriveStats, aps: List<ApObservation>, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(modifier = modifier) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface),
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    Metric("${stats.total}", "APs", cs.primary)
                    Metric("${stats.openCount}", "open", if (stats.openCount > 0) cs.error else cs.onSurface)
                    Metric("${stats.withGps}", "geotagged", cs.onSurface)
                    Metric(stats.mostCongestedChannel?.let { "ch$it" } ?: "-", "busiest", cs.secondary)
                }
            }
        }
        item { StatCard("Encryption", stats.byEnc.map { it.first to it.second }, stats.total) }
        item {
            StatCard(
                "Channels",
                stats.byChannel.map { "ch ${it.first}" to it.second },
                stats.byChannel.maxOfOrNull { it.second } ?: 0,
            )
        }
        item { StatCard("Top vendors", stats.topVendors, stats.topVendors.maxOfOrNull { it.second } ?: 0) }
        item {
            val best = remember(aps) { ChannelAdvisor.recommend24(aps) }
            val cs = MaterialTheme.colorScheme
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Recommended 2.4GHz channel", fontWeight = FontWeight.SemiBold)
                    Text("Channel $best (least congested of 1 / 6 / 11)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        item {
            val findings = remember(aps) { SecurityFindings.analyze(aps) }
            val cs = MaterialTheme.colorScheme
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = cs.surface),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Security findings", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    if (findings.isEmpty()) {
                        Text("None", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    } else {
                        findings.forEach { FindingRow(it) }
                    }
                }
            }
        }
    }
}

/**
 * A device stream on/off row drawn straight on the screen background (Wardriving, Sub-GHz Listen,
 * BLE Scan). The title takes the theme's default content color (onBackground, see BruciaTheme).
 */
@Composable
internal fun StreamToggleRow(
    title: String,
    stream: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(stream, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun FindingRow(f: Finding) {
    val cs = MaterialTheme.colorScheme
    val color = when (f.severity) {
        "high" -> cs.error
        "medium" -> Amber
        else -> cs.onSurfaceVariant
    }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.size(6.dp))
            Text(f.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        }
        Text(f.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun Metric(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatCard(title: String, rows: List<Pair<String, Int>>, max: Int) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (rows.isEmpty()) {
                Text("No data yet", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            } else {
                rows.forEach { (label, count) -> BarRow(label, count, max) }
            }
        }
    }
}

@Composable
private fun BarRow(label: String, count: Int, max: Int) {
    val cs = MaterialTheme.colorScheme
    val fraction = if (max > 0) count.toFloat() / max else 0f
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("$count", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(cs.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(cs.primary)
            )
        }
    }
}

@Composable
private fun ApMap(aps: List<ApObservation>, track: List<Pair<Double, Double>>, modifier: Modifier) {
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
            // GPS track polyline
            if (track.size >= 2) {
                val line = Polyline()
                line.setPoints(track.map { GeoPoint(it.first, it.second) })
                line.outlinePaint.color = android.graphics.Color.rgb(0xFF, 0x6D, 0x1A)
                line.outlinePaint.strokeWidth = 6f
                map.overlays.add(line)
            }
            // Current position
            track.lastOrNull()?.let { here ->
                val me = Marker(map)
                me.position = GeoPoint(here.first, here.second)
                me.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                me.title = "You"
                map.overlays.add(me)
                map.controller.setCenter(me.position)
            }
            var centered = track.isNotEmpty()
            for (ap in aps) {
                val la = ap.lat
                val lo = ap.lon
                if (la != null && lo != null) {
                    val point = GeoPoint(la, lo)
                    val marker = Marker(map)
                    marker.position = point
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    marker.title = ap.ssid.ifEmpty { "(hidden)" }
                    marker.snippet = "${ap.bssid} - ${ap.enc} - ${ap.rssi} dBm"
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

// --- Navigation model ---------------------------------------------------------
// Tabs keep their original integer ids so existing deep-links (e.g. tab = 7 for
// Crack) still work. `tab = HOME` shows the dashboard instead of a tool.
private const val HOME = -1

// The tool catalog lives in [app.brucia.features.BuiltinFeatures] (pure JVM, validated,
// unit-tested). A tool's `cap` is the hardware capability it needs (null = always available).
// When the connected device reports that capability false, the tool page is shown disabled
// and its home card is greyed. See DeviceCaps / capsAllow / capMessage.
private val toolCategories = app.brucia.features.BuiltinFeatures.registry.categories

/** Material (Google) icon for a category, shown on the home grid buttons. */
private fun categoryIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    val I = androidx.compose.material.icons.Icons.Filled
    return when (name) {
        "Wi-Fi" -> I.Wifi
        "Sub-GHz" -> I.SettingsInputAntenna
        "Radio" -> I.Radio
        "2.4GHz" -> I.Router
        "NFC" -> I.Nfc
        "BLE & IR" -> I.Bluetooth
        "Spam" -> I.Campaign
        "Spam BLE" -> I.Bluetooth
        "Spam Wi-Fi" -> I.Wifi
        "Spam NFC" -> I.Nfc
        "Intercept" -> I.Security
        "Device" -> I.DeveloperBoard
        "Audio" -> I.GraphicEq
        "Tools" -> I.Build
        else -> I.Apps
    }
}

/** Material (Google) icon for an individual tool card. */
private fun toolIcon(title: String): androidx.compose.ui.graphics.vector.ImageVector {
    val I = androidx.compose.material.icons.Icons.Filled
    return when (title) {
        "Wardrive" -> I.Map
        "Crack" -> I.Key
        "Sniffer" -> I.Sensors
        "Probes" -> I.TravelExplore
        "Monitor" -> I.Lan
        "Evil Portal" -> I.WifiTethering
        "Channels" -> I.BarChart
        "Network Scan" -> I.Radar
        "RF" -> I.SettingsInputAntenna
        "FM" -> I.RadioButtonChecked
        "LoRa" -> I.Podcasts
        "NRF24" -> I.Mouse
        "NFC" -> I.Nfc
        "BLE" -> I.Bluetooth
        "IR" -> I.SettingsRemote
        "BadUSB" -> I.Usb
        "Spam" -> I.Campaign
        "Spam BLE" -> I.Bluetooth
        "Spam Wi-Fi" -> I.Wifi
        "Spam NFC" -> I.Nfc
        "ESP-NOW" -> I.Cast
        "MAC" -> I.Badge
        "Connect" -> I.Wifi
        "Brucegotchi" -> I.Pets
        "Karma" -> I.Router
        "Responder" -> I.Dns
        "IR Remote" -> I.Dialpad
        "BLE Remote" -> I.Keyboard
        "MITM" -> I.Security
        "Device info" -> I.Info
        "Console" -> I.Terminal
        "Remote" -> Icons.AutoMirrored.Filled.ScreenShare
        "Files" -> I.Folder
        "Updates" -> I.SystemUpdate
        "Ethernet" -> I.SettingsEthernet
        "Audio" -> I.GraphicEq
        "Oscilloscope" -> Icons.AutoMirrored.Filled.ShowChart
        "SSTV" -> I.Image
        "Tone" -> I.MusicNote
        "Report" -> I.Assessment
        "Scripts" -> I.Code
        "Macros" -> Icons.AutoMirrored.Filled.PlaylistPlay
        "RF Map 3D" -> I.Sensors
        "Bug Sweep" -> I.TravelExplore
        "WIDS" -> I.Shield
        "PCAP dissect" -> Icons.AutoMirrored.Filled.ViewList
        "RF Fingerprint" -> I.Fingerprint
        "RF Timeline" -> I.History
        "Geo signals" -> I.PinDrop
        "Gadgets" -> I.Memory
        "Utilities" -> I.Handyman
        else -> I.ChevronRight
    }
}

/** The capability a tab requires, or null if it is always available. */
private fun capForTab(tab: Int): String? = app.brucia.features.BuiltinFeatures.registry.byTabId(tab)?.cap

/** Whether [caps] permits a tool needing capability [cap] (null cap is always allowed). */
private fun capsAllow(caps: DeviceCaps, cap: String?): Boolean = caps.allows(cap)

/** User-facing "this device has no <module>" message for a missing capability. */
private fun capMessage(cap: String?): String = when (cap) {
    "wifi" -> "This device has no Wi-Fi radio."
    "cc1101" -> "This device has no CC1101 sub-GHz module."
    "nfc" -> "No supported NFC reader was detected or reported by this firmware."
    "ble" -> "This device has no BLE radio."
    "ir" -> "This device has no IR module."
    "nrf24" -> "This device has no NRF24L01 module."
    "fm" -> "This device has no FM radio module."
    "lora" -> "This device has no LoRa module"
    "eth" -> "This device has no Ethernet"
    "mic" -> "This device has no microphone."
    "speaker" -> "This device has no speaker."
    else -> "This feature is not available on this device."
}

/** Short module name a capability needs, for the "needs X" hint on gated tools. */
private fun capModuleName(cap: String?): String = when (cap) {
    "wifi" -> "Wi-Fi"
    "cc1101" -> "CC1101"
    "nfc" -> "NFC reader"
    "ble" -> "BLE"
    "ir" -> "IR LED"
    "nrf24" -> "NRF24L01"
    "fm" -> "Si4713 (FM)"
    "lora" -> "LoRa"
    "eth" -> "W5500 (Ethernet)"
    "mic" -> "microphone"
    "speaker" -> "speaker"
    else -> "a module"
}

@Composable
private fun AppRoot(vm: WardriveViewModel, onConnect: () -> Unit, onFlash: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Saveable so a rotation, fold/unfold or window resize (Activity recreation; Android 16 ignores
    // orientation/resizability limits on large screens) keeps the open tool instead of going home.
    var tab by rememberSaveable { mutableIntStateOf(HOME) }     // HOME = dashboard, else tool id
    var category by rememberSaveable { mutableIntStateOf(-1) }  // -1 = all, 0..4 = one category
    val context = LocalContext.current
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val commandLog by vm.commandLog.collectAsState()
    val appUpdate by vm.releaseUpdates.collectAsState()
    // Check for a newer app / firmware release once on launch, so the Updates tile can flag it
    // without the user opening the Updates page first. The firmware comparison also reacts to the
    // device connecting later (device.fw becomes known then).
    LaunchedEffect(Unit) { vm.checkUpdates(BuildConfig.VERSION_NAME) }
    val fwUpdateAvailable = AppUpdater.firmwareUpdateNeeded(device?.fw.orEmpty(), appUpdate.latest,
        device?.features?.verifiedFiles == true,
        appUpdate.firmwareUrl != null && AppUpdater.supportsInAppFirmware(device?.board.orEmpty()))
    val updateAvailable = appUpdate.newer || fwUpdateAvailable
    val updateLabel = when {
        appUpdate.newer && fwUpdateAvailable -> "App + firmware update"
        appUpdate.newer -> "App update available"
        fwUpdateAvailable -> "Firmware update available"
        else -> ""
    }
    var showCmdLog by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    // Background survival is handled by the foreground ConnectionService + wake lock (started on
    // connect), not by keeping the Activity's screen on, so the old FLAG_KEEP_SCREEN_ON workaround
    // is gone - the link stays up with the screen off while the service runs.
    // Ask once for notification permission (Android 13+) so the service's status notification shows.
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // System Back: when a tool is open, return to the dashboard instead of exiting the app (mirrors
    // the top-bar back arrow). On the dashboard the handler is disabled, so Back falls through to the
    // system and the app minimises/exits as usual. The category filter is kept so a second Back from
    // a filtered dashboard still exits predictably.
    BackHandler(enabled = tab != HOME || category != -1) {
        if (tab != HOME) tab = HOME else category = -1
    }
    // Edge-to-edge: the background fills the whole window, content stays inside the safe area. The
    // bottom inset (navigation bar or IME, whichever is taller) is applied by BottomNav so its surface
    // color extends under the gesture/button bar.
    Column(
        modifier = Modifier.fillMaxSize().background(cs.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    ) {
        TopStatusBar(
            status = status,
            battery = device?.battery ?: -1,
            showBack = tab != HOME,
            onBack = { tab = HOME },
            onHome = { tab = HOME; category = -1 },
            onConnect = onConnect,
            onHelp = { showHelp = true },
        )
        Box(modifier = Modifier.weight(1f)) {
            if (tab == HOME) {
                HomeDashboard(
                    category = category,
                    caps = device?.caps,
                    evidence = device?.evidence,
                    connected = status == "Connected",
                    updateAvailable = updateAvailable,
                    updateLabel = updateLabel,
                    onConnect = onConnect,
                    onOpen = { tab = it },
                    onSelectCategory = { idx ->
                        val cat = toolCategories.getOrNull(idx)
                        // A single-tool category has no useful sub-list: open the tool directly.
                        if (cat != null && cat.features.size == 1) tab = cat.features[0].tabId else category = idx
                    },
                )
            } else {
                val cap = capForTab(tab)
                val caps = device?.caps
                // Wide windows: tool screens are centered and capped at 840dp (Material's
                // expanded breakpoint); narrower windows keep the full width unchanged.
                CenteredMaxWidth(AdaptiveLayout.ToolMaxWidth) {
                    // Capability gating: when connected (caps known) and the module is absent, show a
                    // disabled page instead of the tool. NRF24 (tab 13) gates itself with its own
                    // message. When disconnected / caps unknown, nothing is hidden.
                    if (caps != null && tab != 13 && !capsAllow(caps, cap)) {
                        CapabilityDisabled(capMessage(cap), CapabilityEvidence.unavailableReason(cap, device?.evidence))
                    } else when (tab) {
                        0 -> BruciaScreen(vm, onConnect, onGoCrack = { tab = 7 })
                        1 -> IrScreen(vm, onConnect)
                        2 -> SubGhzScreen(vm, onConnect)
                        3 -> NfcScreen(vm, onConnect)
                        4 -> BadUsbScreen(vm, onConnect)
                        5 -> BleScreen(vm, onConnect)
                        6 -> DeviceScreen(vm, onConnect, updateLabel = updateLabel, onOpenUpdates = { tab = 12 })
                        7 -> CrackScreen(vm)
                        8 -> ConsoleScreen(vm, onConnect)
                        9 -> RemoteScreen(vm, onConnect)
                        10 -> FilesScreen(vm, onConnect)
                        11 -> StationSnifferScreen(vm, onConnect)
                        12 -> UpdatesScreen(vm, onFlash = onFlash)
                        13 -> Nrf24Screen(vm, onConnect)
                        14 -> FmScreen(vm, onConnect)
                        15 -> WifiProbeScreen(vm, onConnect)
                        16 -> SpamScreen(vm, onConnect)
                        49 -> SpamScreen(vm, onConnect, filter = "ble")
                        50 -> SpamScreen(vm, onConnect, filter = "wifi")
                        51 -> SpamScreen(vm, onConnect, filter = "nfc")
                        17 -> WifiMonitorScreen(vm, onConnect)
                        18 -> MitmScreen(vm, status == "Connected")
                        19 -> ScriptsScreen(vm, onConnect)
                        20 -> ToolsScreen(vm, onConnect)
                        21 -> ReportScreen(vm)
                        22 -> LoRaScreen(vm, onConnect)
                        23 -> EthernetScreen(vm, onConnect)
                        24 -> UtilitiesScreen()
                        25 -> AudioDashboardScreen(vm, onConnect)
                        41 -> MacroScreen(vm, onConnect)
                        42 -> RfMap3dScreen(vm, onConnect)
                        43 -> BugSweepScreen(vm, onConnect)
                        44 -> WidsScreen(vm, onConnect)
                        45 -> PcapDissectScreen(vm, onConnect)
                        46 -> RfFingerprintScreen(vm, onConnect)
                        47 -> RfTimelineScreen(vm, onConnect)
                        48 -> GeoSignalsScreen(vm, onConnect)
                        27 -> SstvScreen(vm, onConnect)
                        28 -> ToneScreen(vm, onConnect)
                        29 -> OscilloscopeScreen(vm, onConnect)
                        30 -> EvilPortalScreen(vm, onConnect)
                        26 -> ChannelAnalyzerScreen(vm, onConnect)
                        31 -> NetworkScanScreen()
                        32 -> EspnowScreen(vm, onConnect)
                        33 -> MacScreen(vm, onConnect)
                        34 -> WifiJoinScreen(vm, onConnect)
                        35 -> BrucegotchiScreen(vm, onConnect)
                        36 -> ThemeScreen(vm, onConnect)
                        37 -> IrRemoteScreen(vm, onConnect)
                        38 -> KarmaScreen(vm, onConnect)
                        39 -> BleHidScreen(vm, onConnect)
                        40 -> ResponderScreen(vm, onConnect)
                        else -> StationSnifferScreen(vm, onConnect)
                    }
                }
            }
        }
        BottomNav(
            runningCount = commandLog.count { it.status == CmdStatus.RUNNING },
            deviceUpdate = updateAvailable,
            onHome = { tab = HOME; category = -1 },
            onDevice = { tab = 6 },
            onCmds = { showCmdLog = true },
        )
    }
    if (showCmdLog) {
        CommandLogDialog(entries = commandLog, onClear = { vm.clearCommandLog() }, onStopRunning = { vm.stopAllRunning() }, onDismiss = { showCmdLog = false })
    }
    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }
}

@Composable
private fun TopStatusBar(
    status: String,
    battery: Int,
    showBack: Boolean,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onConnect: () -> Unit,
    onHelp: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val connected = status.startsWith("Connected")
    val connecting = status.startsWith("Connecting")
    val label = when { connected -> "Connected"; connecting -> "Connecting"; else -> "Tap to connect" }
    val color = when { connected -> Color(0xFF4CAF50); connecting -> Amber; else -> cs.primary }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onBack() },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(14.dp)) {
                        val w = size.width; val h = size.height
                        drawLine(cs.onSurface, Offset(w * 0.65f, h * 0.15f), Offset(w * 0.3f, h * 0.5f), strokeWidth = 5f)
                        drawLine(cs.onSurface, Offset(w * 0.3f, h * 0.5f), Offset(w * 0.65f, h * 0.85f), strokeWidth = 5f)
                    }
                }
                Spacer(Modifier.size(4.dp))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onHome() }.padding(4.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary), 
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text("Brucia", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            }
            Spacer(Modifier.weight(1f))
            // When not connected the pill is an actionable connect shortcut.
            val pillModifier = if (!connected && !connecting) {
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onConnect() }.padding(horizontal = 6.dp, vertical = 2.dp)
            } else {
                Modifier
            }
            Row(modifier = pillModifier, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.size(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = color)
            }
            if (battery in 0..100) {
                val batColor = when { battery <= 15 -> cs.error; battery <= 40 -> Amber; else -> Color(0xFF4CAF50) }
                Spacer(Modifier.size(10.dp))
                Text("$battery%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = batColor)
            }
            // Help / wiki / donations opener (replaces the old top-bar Cmds button; the command
            // log now lives in the footer).
            Spacer(Modifier.size(10.dp))
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape).background(cs.surfaceVariant).clickable { onHelp() },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "Help", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.surfaceVariant))
    }
}

/**
 * Height of the activity window in dp, for capping dialog heights. Read it before
 * `Dialog { }`: inside the dialog content LocalWindowInfo describes the dialog window.
 * (Replaces Configuration.screenHeightDp, which Compose lint discourages.)
 */
@Composable
private fun windowHeightDp(): androidx.compose.ui.unit.Dp =
    with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

@Composable
private fun CommandLogDialog(
    entries: List<CommandLogEntry>,
    onClear: () -> Unit,
    onStopRunning: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val maxH = windowHeightDp() * 0.6f
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = maxH),
            shape = RoundedCornerShape(14.dp),
            color = cs.surface,
            tonalElevation = 4.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Command log",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = cs.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    if (entries.any { it.status == CmdStatus.RUNNING }) {
                        TextButton(onClick = onStopRunning) { Text("Stop", color = cs.error) }
                    }
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = onClear) { Text("Clear") }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(cs.surfaceVariant))
                if (entries.isEmpty()) {
                    Text(
                        "No commands yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(entries, key = { it.id }) { e -> CommandLogRow(e) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandLogRow(e: CommandLogEntry) {
    val cs = MaterialTheme.colorScheme
    val chipColor = when (e.status) {
        CmdStatus.RUNNING -> Amber
        CmdStatus.DONE -> Color(0xFF4CAF50)
        CmdStatus.FAILED -> cs.error
    }
    // Elapsed while running; true duration once finished.
    val timeLabel = when (e.status) {
        CmdStatus.RUNNING -> {
            val secs = ((System.currentTimeMillis() - e.sentAtMs) / 1000L).coerceAtLeast(0)
            "running ${secs}s"
        }
        else -> {
            val ms = ((e.doneAtMs ?: e.sentAtMs) - e.sentAtMs).coerceAtLeast(0)
            if (ms < 1000) "${ms}ms" else String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000f)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                e.text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = cs.onSurface,
            )
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(chipColor.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    e.status.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = chipColor,
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(
                timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
        val result = e.result
        if (e.status != CmdStatus.RUNNING && !result.isNullOrBlank()) {
            Text(
                result,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurfaceVariant,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(cs.surfaceVariant.copy(alpha = 0.5f)))
    }
}

@Composable
internal fun HomeDashboard(
    category: Int,
    caps: DeviceCaps?,
    evidence: CapabilityEvidence? = null,
    connected: Boolean,
    updateAvailable: Boolean = false,
    updateLabel: String = "",
    onConnect: () -> Unit,
    onOpen: (Int) -> Unit,
    onSelectCategory: (Int) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    // Top level (no category picked): a grid of icon buttons, one per category; tapping opens it.
    if (category !in toolCategories.indices) {
        var showSupport by remember { mutableStateOf(false) }
        if (showSupport) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSupport = false },
                confirmButton = {
                    TextButton(onClick = { showSupport = false }) { Text("Close") }
                },
                title = { Text("Support Brucia") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { SupportCard() } },
            )
        }
        // Wide windows: the dashboard is centered and capped (phones in portrait are narrower).
        CenteredMaxWidth(AdaptiveLayout.HomeMaxWidth) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (!connected) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                            Text("Device not connected", fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                            Spacer(Modifier.height(4.dp))
                            Text("Plug the device into this phone over USB - it connects automatically.",
                                style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
                            Spacer(Modifier.height(8.dp))
                            Text("Don't have the board yet?", style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
                            OutlinedButton(onClick = { openUrl(context, ALIEXPRESS_URL) }, modifier = Modifier.fillMaxWidth()) {
                                Text("Buy the LilyGo T-Embed CC1101")
                            }
                        }
                    }
                }
                // Search across every tool (there are ~45) - type to jump straight to one instead of
                // hunting through categories.
                var query by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text("Search tools") },
                    leadingIcon = { androidx.compose.material3.Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                if (query.isNotBlank()) {
                    val q = query.trim()
                    val matches = app.brucia.features.BuiltinFeatures.registry.features
                        .filter { it.title.contains(q, true) || it.description.contains(q, true) }
                    if (matches.isEmpty()) {
                        Text("No tools match \"$q\".", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    } else {
                        matches.forEach { tool ->
                            val available = caps == null || capsAllow(caps, tool.cap)
                            ToolCard(tool, available, updateAvailable && tool.tabId == 12, if (tool.tabId == 12) updateLabel else "", onOpen,
                                unavailableReason = CapabilityEvidence.shortReason(tool.cap, evidence))
                        }
                    }
                } else {
                    Text("Categories", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                        color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    // One cell per category, plus a trailing Support cell in the same card style, so the whole
                    // menu reads as one uniform grid.
                    val cells: List<@Composable (Modifier) -> Unit> = buildList {
                        toolCategories.forEachIndexed { idx, cat ->
                            val anyAvail = caps == null || cat.features.any { capsAllow(caps, it.cap) }
                            // Flag the category that holds the Updates tool (tab 12) when a newer app or
                            // firmware release is available, so the user sees it from the home grid.
                            val catBadge = updateAvailable && cat.features.any { it.tabId == 12 }
                            add { m -> CategoryButton(cat, anyAvail, modifier = m, badge = catBadge) { onSelectCategory(idx) } }
                        }
                        add { m -> GridCell(Icons.Filled.Palette, "Theme", "App + device colors & LED", connected, m) { onOpen(36) } }
                        add { m -> GridCell(Icons.AutoMirrored.Filled.MenuBook, "Wiki", "Guides & docs", true, m) { openUrl(context, WIKI_URL) } }
                        add { m -> GridCell(Icons.Filled.Favorite, "Support", "Sponsor / donate", true, m) { showSupport = true } }
                    }
                    // Column count follows the grid's width: 2 on phones, more tiles of at least
                    // 160dp on tablets / landscape (see AdaptiveLayout.homeGridColumns).
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = AdaptiveLayout.homeGridColumns(maxWidth.value)
                        Column {
                            cells.chunked(columns).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { cell -> cell(Modifier.weight(1f)) }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // A category is open: show its tools (subcategories).
    val shown = listOf(toolCategories[category])
    CenteredMaxWidth(AdaptiveLayout.ToolMaxWidth) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // First-run path: when nothing is connected, the tools cannot do anything, so offer an
            // obvious connect action before the cards.
            if (!connected) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            "Device not connected",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = cs.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Plug the device into this phone over USB to start using the tools.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                            Text("Connect over USB")
                        }
                    }
                }
            }
            shown.forEach { cat ->
                Text(
                    cat.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )
                cat.features.forEach { tool ->
                    // Unknown caps (disconnected) -> everything shown normally.
                    val available = caps == null || capsAllow(caps, tool.cap)
                    ToolCard(tool, available, updateAvailable && tool.tabId == 12, if (tool.tabId == 12) updateLabel else "", onOpen,
                                unavailableReason = CapabilityEvidence.shortReason(tool.cap, evidence))
                }
                Spacer(Modifier.height(10.dp))
            }
            // Support card at the bottom of the dashboard (shown only on the full, unfiltered view).
            if (category !in toolCategories.indices) {
                SupportCard()
            }
        }
    }
}

/** Brucia is free and AGPL; tasteful support links that open in the browser. */
private const val ALIEXPRESS_URL = "https://s.click.aliexpress.com/e/_c33qeCLh"
private val WIKI_URL = BrandLinks.WIKI_URL
private const val SPONSOR_URL = "https://github.com/sponsors/1vcian"
private const val DONATE_URL = "https://buymeacoffee.com/1vcian"
private const val BTC_ADDRESS = "bc1qee8a06x886ng8ce6qy84l6u6x54jnjsesgqym7"
private const val XMR_ADDRESS = "46qDyafRkn1at3E2GMwuESBeqzpt74xE8S2kjPe35EUyKiS57Ho7cLB1vD2zH4hPmjYcYzWWxSSLZAYB5Hp1yDFrKnPWkR7"

@Composable
private fun SupportCard() {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                "Support Brucia",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                color = cs.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Brucia is free, open source and ad-free. If it is useful to you, consider sponsoring to keep the fire burning.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onPrimaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { openUrl(context, SPONSOR_URL) }, modifier = Modifier.fillMaxWidth()) {
                Text("Sponsor on GitHub")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openUrl(context, DONATE_URL) }, modifier = Modifier.fillMaxWidth()) {
                Text("Buy me a coffee")
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Or donate crypto",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                color = cs.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            CryptoRow("Bitcoin", BTC_ADDRESS)
            Spacer(Modifier.height(10.dp))
            CryptoRow("Monero", XMR_ADDRESS)
        }
    }
}

/**
 * Copies plain text to the system clipboard. LocalClipboard replaces the deprecated
 * LocalClipboardManager; its setClipEntry is a suspend function, so it runs in the
 * composition's scope. Same "plain text" label the old setText(AnnotatedString) used.
 */
@Composable
private fun rememberTextCopier(sensitive: Boolean = false): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope, sensitive) {
        { text ->
            scope.launch {
                val clip = android.content.ClipData.newPlainText("plain text", text)
                // Android 13+ hides a sensitive clip from the copy preview and keyboard suggestions
                // (the extra's value is ClipDescription.EXTRA_IS_SENSITIVE; older releases ignore it).
                if (sensitive) clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                clipboard.setClipEntry(ClipEntry(clip))
            }
        }
    }
}

/** One crypto donation row: a self-generated QR, the address and a copy-to-clipboard button. */
@Composable
private fun CryptoRow(name: String, address: String) {
    val cs = MaterialTheme.colorScheme
    val copyText = rememberTextCopier()
    val context = LocalContext.current
    val qr = remember(address) { app.brucia.utils.QrCodes.encode(address, 256) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (qr != null) {
            Image(
                bitmap = qr.asImageBitmap(),
                contentDescription = "$name donation QR",
                modifier = Modifier.size(84.dp).padding(end = 12.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
            Text(
                address,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = {
                copyText(address)
                Toast.makeText(context, "$name address copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy address") }
        }
    }
}

/** Open [url] in the user's browser; silently ignores the rare case of no browser installed. */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, url.toUri())
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun CategoryButton(cat: app.brucia.features.FeatureCategory, available: Boolean, modifier: Modifier = Modifier, badge: Boolean = false, onClick: () -> Unit) =
    GridCell(categoryIcon(cat.name), cat.name,
        "${cat.features.size} tool${if (cat.features.size == 1) "" else "s"}", available, modifier, badge = badge, onClick = onClick)

/** One square icon+label tile used by the home category grid (categories and the Support cell). */
@Composable
private fun GridCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    available: Boolean,
    modifier: Modifier = Modifier,
    badge: Boolean = false,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = modifier.height(112.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (available) cs.surface else cs.surfaceVariant.copy(alpha = 0.4f),
        ),
        // Accent the whole tile when it carries an available update.
        border = if (badge) androidx.compose.foundation.BorderStroke(2.dp, cs.primary) else null,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (available) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall,
                    color = if (available) cs.onSurface else cs.onSurfaceVariant)
                Text(if (badge) "Update available" else subtitle, style = MaterialTheme.typography.labelSmall,
                    color = if (badge) cs.primary else cs.onSurfaceVariant)
            }
            if (badge) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .size(10.dp).clip(CircleShape).background(cs.primary)
                )
            }
        }
    }
}

@Composable
private fun ToolCard(tool: app.brucia.features.FeatureDescriptor, available: Boolean, badge: Boolean = false, badgeText: String = "", onOpen: (Int) -> Unit, unavailableReason: String? = null) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(tool.tabId) },
        colors = CardDefaults.cardColors(
            containerColor = if (available) cs.surface else cs.surfaceVariant.copy(alpha = 0.4f),
        ),
        // Highlight the Updates card when a newer app or firmware release is available.
        border = if (badge) androidx.compose.foundation.BorderStroke(2.dp, cs.primary) else null,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                imageVector = toolIcon(tool.title),
                contentDescription = null,
                tint = if (available) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tool.title,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (available) cs.onSurface else cs.onSurfaceVariant,
                )
                Text(tool.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                if (badge) {
                    Text(
                        (badgeText.ifBlank { "Update available" }) + " - tap to install",
                        style = MaterialTheme.typography.labelSmall, color = cs.primary,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // Instead of hiding a module-gated tool, show it greyed with what it needs.
                if (!available && tool.cap != null) {
                    Text(
                        "Needs ${capModuleName(tool.cap)} - " + (unavailableReason ?: "not detected on this device"),
                        style = MaterialTheme.typography.labelSmall, color = cs.error,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/** Shown in place of a tool page when the connected device lacks the required module. */
@Composable
private fun CapabilityDisabled(message: String, evidenceReason: String? = null) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Module not available", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onSurface)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        // CAPS d.evidence: why the firmware reports it unavailable (probed absent, busy, no pins...).
        if (evidenceReason != null) {
            Spacer(Modifier.height(8.dp))
            Text(evidenceReason, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Attach the required module and reconnect to enable this tool.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun BottomNav(runningCount: Int, deviceUpdate: Boolean, onHome: () -> Unit, onDevice: () -> Unit, onCmds: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.surfaceVariant))
        Row(
            modifier = Modifier.fillMaxWidth().background(cs.surface)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Device (left) · Home (center) · Cmds (right). Help stays on the top bar "?".
            NavButton(Icons.Filled.Memory, "Device", onDevice, dot = deviceUpdate)
            NavButton(Icons.Filled.Home, "Home", onHome)
            NavButton(Icons.Filled.Terminal, "Cmds", onCmds, badge = runningCount)
        }
    }
}

@Composable
private fun NavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    badge: Int = 0,
    dot: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.TopEnd) {
        Column(
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = label,
                tint = if (dot) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(2.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = if (dot) cs.primary else cs.onSurfaceVariant)
        }
        // Update-available dot (e.g. on Device when an app/firmware update exists).
        if (dot && badge <= 0) {
            Box(Modifier.offset(x = (-8).dp, y = 2.dp).size(9.dp).clip(CircleShape).background(cs.primary))
        }
        if (badge > 0) {
            Box(
                modifier = Modifier.offset(x = (-6).dp, y = 2.dp).size(16.dp).clip(CircleShape).background(Amber),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (badge > 9) "9+" else badge.toString(), fontSize = 9.sp,
                    fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}

/** Help sheet: wiki + donations, opened from the top-bar "?" and the footer Help button. */
@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val maxH = windowHeightDp() * 0.8f
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp)
                .heightIn(max = maxH),
            shape = RoundedCornerShape(14.dp), color = cs.surface, tonalElevation = 4.dp,
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Help & support", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, color = cs.primary)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, WIKI_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { app.brucia.BruciaLog.w("Help", "open wiki url", e) }
                    },
                ) { Text("Open the wiki") }
                Spacer(Modifier.height(16.dp))
                SupportCard()
            }
        }
    }
}

/**
 * State for the in-app text/HTML editor opened from the file browser or a template. The edits
 * live here (not in the editor composable) so the retained editor keeps them across a rotation.
 */
private class EditorState(
    val fileName: String,
    /** Device path this came from (enables a sensible "Save to device" default); null for a new/template file. */
    val devicePath: String?,
    initialText: String,
) {
    var text by mutableStateOf(initialText)
    var viewMode by mutableStateOf(false)
    /** "Save to device" target as edited; null until the user changes the default. */
    var saveTarget by mutableStateOf<String?>(null)
}

@Composable
private fun FilesScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var path by rememberSaveable { mutableStateOf("/") }
    val entries by vm.deviceEntries.collectAsState()
    val loading by vm.deviceListing.collectAsState()
    val listStatus by vm.deviceListStatus.collectAsState()
    val fileStatus by vm.deviceFileStatus.collectAsState()
    fun refresh() { if (connected) vm.listDir(path) }
    LaunchedEffect(path, connected) { refresh() }

    // Per-file action sheet, the in-app editor overlay, and the new-file/folder/template dialog.
    var selected by remember { mutableStateOf<app.brucia.files.FileEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    // Retained (not saved: it holds the whole file): an open editor and its unsaved edits survive
    // a rotation or resize.
    var editor by rememberRetained { mutableStateOf<EditorState?>(null) }
    var showNew by remember { mutableStateOf(false) }

    // SAF: let the user choose where a pulled device file is saved. The source device path is held
    // while the system document picker is up.
    // Saveable: the picker is another Activity, and ours may be recreated before the result comes back.
    var pullPendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val pullDestLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val src = pullPendingPath
        pullPendingPath = null
        if (uri != null && src != null) {
            Toast.makeText(context, "Pulling ${src.substringAfterLast('/')}...", Toast.LENGTH_SHORT).show()
            vm.pullFileToUri(src, uri) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    // When the editor is open, show it full-screen instead of the browser.
    val ed = editor
    if (ed != null) {
        FileEditorScreen(vm = vm, state = ed, currentPath = path, onClose = { editor = null; refresh() })
        return
    }
    // System Back in a subfolder goes up one level (same as the Up button); at "/" it falls through
    // to AppRoot's handler and returns to the dashboard. Registered after AppRoot's, so it wins.
    BackHandler(enabled = path != "/") {
        path = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary),  contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text("Files - browse the device SD / LittleFS", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (!connected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(enabled = path != "/", onClick = {
                path = path.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
            }) { Text("Up") }
            Spacer(Modifier.size(8.dp))
            Text(path, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
            OutlinedButton(onClick = { refresh() }) { Text("Refresh") }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showNew = true }) { Text("New / template") }
            OutlinedButton(onClick = {
                editor = EditorState("untitled.txt", null, "")
            }) { Text("New editor") }
        }
        Spacer(Modifier.height(8.dp))
        if (loading) Text("Loading...", color = cs.onSurfaceVariant)
        if (listStatus.isNotBlank()) Text(listStatus, color = cs.error, style = MaterialTheme.typography.bodySmall)
        if (fileStatus.isNotBlank()) Text(fileStatus, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries) { e ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                        if (e.isDir) path = (path.trimEnd('/') + "/" + e.name) else selected = e
                    },
                    colors = CardDefaults.cardColors(containerColor = cs.surface),
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (e.isDir) "[D]" else "   ", fontFamily = FontFamily.Monospace, color = cs.primary)
                        Spacer(Modifier.size(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                            if (!e.isDir) Text("${e.size} bytes", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        if (!e.isDir) {
                            OutlinedButton(onClick = {
                                val full = path.trimEnd('/') + "/" + e.name
                                vm.pullFile(full); Toast.makeText(context, "Pulling $full...", Toast.LENGTH_SHORT).show()
                            }) { Text("Pull") }
                        } else {
                            Text(">", color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
            if (entries.isEmpty() && !loading && connected) {
                item { Text("Empty or unreadable", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    // --- per-file action sheet ---
    val sel = selected
    if (sel != null && !sel.isDir) {
        val full = path.trimEnd('/') + "/" + sel.name
        Dialog(onDismissRequest = { selected = null }) {
            Surface(shape = RoundedCornerShape(16.dp), color = cs.surface) {
                Column(modifier = Modifier.padding(16.dp).widthIn(max = 420.dp)) {
                    Text(sel.name, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                    Text("${sel.size} bytes", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        selected = null
                        vm.pullFile(full)
                        Toast.makeText(context, "Pulling to app folder...", Toast.LENGTH_SHORT).show()
                    }) { Text("Pull (app folder)") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        pullPendingPath = full
                        selected = null
                        pullDestLauncher.launch(sel.name)
                    }) { Text("Pull to... (choose destination)") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        selected = null
                        Toast.makeText(context, "Preparing ${sel.name}...", Toast.LENGTH_SHORT).show()
                        vm.pullFileBytes(full) { bytes ->
                            if (bytes == null) { Toast.makeText(context, "Pull failed", Toast.LENGTH_SHORT).show(); return@pullFileBytes }
                            val dir = File(context.cacheDir, "open").apply { mkdirs() }
                            val f = File(dir, sel.name)
                            try { f.writeBytes(bytes); openWith(context, f, app.brucia.files.FileKinds.mime(sel.name)) }
                            catch (e: Exception) { Toast.makeText(context, "Open failed: ${e.message}", Toast.LENGTH_SHORT).show() }
                        }
                    }) { Text(if (app.brucia.files.FileKinds.isPcap(sel.name)) "Open with... (Wireshark etc.)" else "Open with...") }
                    if (app.brucia.files.FileKinds.isEditable(sel.name)) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                            selected = null
                            Toast.makeText(context, "Loading ${sel.name}...", Toast.LENGTH_SHORT).show()
                            vm.pullFileBytes(full) { bytes ->
                                if (bytes == null) { Toast.makeText(context, "Load failed", Toast.LENGTH_SHORT).show(); return@pullFileBytes }
                                editor = EditorState(sel.name, full, String(bytes, Charsets.UTF_8))
                            }
                        }) { Text("Edit") }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        confirmDelete = full
                        selected = null
                    }) { Text("Delete") }
                    Spacer(Modifier.height(10.dp))
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { selected = null }) { Text("Cancel") }
                }
            }
        }
    }

    confirmDelete?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    vm.deleteDeviceFile(target)
                    Toast.makeText(context, "Deleting ${target.substringAfterLast('/')}...", Toast.LENGTH_SHORT).show()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            title = { Text("Delete from the device?") },
            text = { Text("$target will be removed from the device storage. This cannot be undone.") },
        )
    }

    // --- new file / folder / template dialog ---
    if (showNew) {
        var newName by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { showNew = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = cs.surface) {
                Column(modifier = Modifier.padding(16.dp).widthIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text("New in $path", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("File / folder name") }, singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(enabled = connected && newName.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                        val p = path.trimEnd('/') + "/" + newName.trim()
                        showNew = false
                        vm.pushFile(p, ByteArray(0)) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show(); refresh() }
                    }) { Text("Create empty file on device") }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(enabled = connected && newName.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                        val p = path.trimEnd('/') + "/" + newName.trim()
                        showNew = false
                        vm.makeDeviceDir(p)
                        Toast.makeText(context, "Creating folder...", Toast.LENGTH_SHORT).show()
                    }) { Text("Create folder on device") }
                    Spacer(Modifier.height(12.dp))
                    Text("New from template (opens in editor)", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, color = cs.primary)
                    Spacer(Modifier.height(4.dp))
                    app.brucia.files.FileTemplates.all.forEach { t ->
                        OutlinedButton(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), onClick = {
                            showNew = false
                            val body = try {
                                context.assets.open(t.asset).bufferedReader().use { it.readText() }
                            } catch (e: Exception) { "" }
                            editor = EditorState(t.fileName, path.trimEnd('/') + "/" + t.fileName, body)
                        }) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(t.title, fontWeight = FontWeight.SemiBold)
                                Text(t.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { showNew = false }) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * In-app editor (monospace, editable) for text/JS/HTML/etc. HTML additionally gets a View mode that
 * renders the current text in a sandboxed WebView (network loads blocked, no file/content access).
 * Save targets: the phone (SAF), the device (transactional upload), or an external app (Open with).
 */
@Composable
private fun FileEditorScreen(vm: WardriveViewModel, state: EditorState, currentPath: String, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    var text by state::text
    var viewMode by state::viewMode
    val isHtml = app.brucia.files.FileKinds.isHtml(state.fileName)
    val devicePath = state.saveTarget ?: state.devicePath ?: (currentPath.trimEnd('/') + "/" + state.fileName)

    val saveToPhone = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(context, "Saved to phone", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler { onClose() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary),  contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Editor", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        Text(state.fileName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (isHtml) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SegButton("Edit", !viewMode, Modifier) { viewMode = false }
                SegButton("View", viewMode, Modifier) { viewMode = true }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (isHtml && viewMode) {
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
                update = { wv -> wv.loadDataWithBaseURL(null, text, "text/html", "UTF-8", null) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = devicePath, onValueChange = { state.saveTarget = it },
            label = { Text("Device path") }, singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = { saveToPhone.launch(state.fileName) }) { Text("Save to phone") }
            Button(enabled = connected, modifier = Modifier.weight(1f), onClick = {
                vm.pushFile(devicePath, text.toByteArray(Charsets.UTF_8)) { _, msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
            }) { Text("Save to device") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            val dir = File(context.cacheDir, "open").apply { mkdirs() }
            val f = File(dir, state.fileName)
            try { f.writeText(text); openWith(context, f, app.brucia.files.FileKinds.mime(state.fileName)) }
            catch (e: Exception) { Toast.makeText(context, "Open failed: ${e.message}", Toast.LENGTH_SHORT).show() }
        }) { Text("Open with...") }
    }
}

@Composable
private fun StationSnifferScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val stations by vm.stations.collectAsState()
    val sniffing by vm.sniffing.collectAsState()
    // Stop the wifi.sta stream when leaving the tab so the device radio does not keep sniffing.
    // LeaveEffect, not DisposableEffect: a rotation keeps the sniff running; leaving stops it.
    LeaveEffect { if (vm.streamMayRun("wifi.sta")) vm.stopStationSniff() }

    // One LazyColumn so the whole screen scrolls (controls = header item, stations = items).
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        item {
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary),  contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text("Station sniffer - nearby Wi-Fi clients (wifi.sta stream)", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        DeviceCard(device)
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
            Spacer(Modifier.height(12.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = connected || sniffing,
                modifier = Modifier.weight(1f),
                onClick = { if (sniffing) vm.stopStationSniff() else vm.startStationSniff() }
            ) { Text(if (sniffing) "Stop" else "Start") }
            OutlinedButton(
                enabled = stations.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val f = vm.exportStationsCsv()
                    if (f != null) shareFile(context, f, "text/csv")
                    else Toast.makeText(context, "Nothing captured yet", Toast.LENGTH_SHORT).show()
                }
            ) { Text("Export CSV") }
            OutlinedButton(
                enabled = stations.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = { vm.clearStations() }
            ) { Text("Clear") }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${stations.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = cs.primary)
            Spacer(Modifier.size(6.dp))
            Text("stations", color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        }
        }
        items(stations) { s ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surface),
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(s.mac, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                            val vendor = app.brucia.wardrive.OuiLookup.vendor(s.mac)
                            if (vendor != null) {
                                Text(vendor, style = MaterialTheme.typography.bodySmall, color = cs.primary)
                            }
                            Text(
                                "bssid ${s.bssid.ifEmpty { "?" }}",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace,
                            )
                            Row {
                                Text("ch ${s.ch}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                                Spacer(Modifier.size(8.dp))
                                Text("x${s.count}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            }
                        }
                        RssiPill(s.rssi)
                    }
                }
            }
        if (stations.isEmpty()) {
            item {
                Text(
                    if (sniffing) "Sniffing... bring Wi-Fi clients nearby." else "No stations yet. Tap Start to sniff nearby Wi-Fi clients.",
                    color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Wi-Fi probe-request profiling: Start/Stop drives the "wifi.probe" stream, the deduped
 * {client MAC, SSID, rssi, count} rows can be grouped by SSID ("who wants this network") or by
 * client ("what this device is looking for"), and exported to CSV.
 */
@Composable
private fun WifiProbeScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val probes by vm.probes.collectAsState()
    val probing by vm.probing.collectAsState()
    var byClient by remember { mutableStateOf(false) }
    // Stop the wifi.probe stream when leaving the tab so the device radio does not keep sniffing.
    LeaveEffect { if (vm.streamMayRun("wifi.probe")) vm.stopProbeSniff() }

    // Group by SSID (networks and who seeks them) or by client (devices and what they seek).
    // Hoisted above the LazyColumn because remember() cannot be called in LazyListScope.
    val groups: List<Pair<String, List<ProbeRequest>>> = remember(probes, byClient) {
        if (byClient) {
            probes.groupBy { it.mac }.entries
                .sortedByDescending { e -> e.value.maxOf { it.lastSeen } }
                .map { it.key to it.value.sortedByDescending { p -> p.count } }
        } else {
            probes.groupBy { it.ssid.ifEmpty { "(broadcast / wildcard)" } }.entries
                .sortedByDescending { e -> e.value.sumOf { it.count } }
                .map { it.key to it.value.sortedByDescending { p -> p.rssi } }
        }
    }
    // One LazyColumn so the whole screen scrolls (controls = header item, groups = items).
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        item {
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary),  contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text("Probe profiling - who is looking for which networks (wifi.probe stream)", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        DeviceCard(device)
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
            Spacer(Modifier.height(12.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = connected || probing,
                modifier = Modifier.weight(1f),
                onClick = { if (probing) vm.stopProbeSniff() else vm.startProbeSniff() }
            ) { Text(if (probing) "Stop" else "Start") }
            OutlinedButton(
                enabled = probes.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val f = vm.exportProbesCsv()
                    if (f != null) shareFile(context, f, "text/csv")
                    else Toast.makeText(context, "Nothing captured yet", Toast.LENGTH_SHORT).show()
                }
            ) { Text("Export CSV") }
            OutlinedButton(
                enabled = probes.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = { vm.clearProbes() }
            ) { Text("Clear") }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SegButton("Group by SSID", !byClient, Modifier) { byClient = false }
            SegButton("Group by client", byClient, Modifier) { byClient = true }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${probes.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = cs.primary)
            Spacer(Modifier.size(6.dp))
            Text("requests", color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        }
        }
        items(groups) { (header, rows) ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = cs.surface),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                header,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = if (byClient) FontFamily.Monospace else FontFamily.Default,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${rows.size}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        }
                        rows.forEach { p ->
                            val detail = if (byClient) p.ssid.ifEmpty { "(broadcast)" } else p.mac
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant,
                                    fontFamily = if (byClient) FontFamily.Default else FontFamily.Monospace,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("x${p.count}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                                Spacer(Modifier.size(8.dp))
                                RssiPill(p.rssi)
                            }
                        }
                    }
                }
            }
        if (groups.isEmpty()) {
            item {
                Text(
                    if (probing) "Sniffing... bring Wi-Fi clients nearby." else "No probe requests yet. Tap Start to profile nearby clients.",
                    color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * "Monitor my network": capture raw 802.11 frames from a network the user owns (SUB
 * "wifi.monitor"), show a live Wireshark-style packet list, and export a real pcap.
 *
 * Decryption is intentionally NOT performed on-device: the exported pcap (linktype 105) is
 * opened in Wireshark, which does the CCMP decryption from the supplied PSK. The PSK field
 * here only feeds the export workflow and an optional on-device PMK derivation convenience.
 */
@Composable
private fun JamDetectCard(vm: WardriveViewModel, connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val active by vm.jamDetectActive.collectAsState()
    val status by vm.jamDetectStatus.collectAsState()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text("Jam / deauth detector", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Text("Hops the 2.4GHz channels and counts deauth/disassoc frames - a spike means someone is deauth-flooding the air.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(enabled = connected && !active, modifier = Modifier.fillMaxWidth(),
                onClick = { vm.jamDetect(15) }) { Text(if (active) "Listening..." else "Detect jamming (15s)") }
            if (status.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(status, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurface) }
        }
    }
}

@Composable
private fun WifiMonitorScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val frames by vm.monitorFrames.collectAsState()
    val monitoring by vm.monitoring.collectAsState()
    val aps by vm.aps.collectAsState()

    var channel by rememberSaveable { mutableStateOf<Int?>(1) }
    var targetBssid by rememberSaveable { mutableStateOf<String?>(null) }
    var targetSsid by rememberSaveable { mutableStateOf("") }
    var psk by remember { mutableStateOf("") }
    var pickAp by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Int?>(null) }

    // Stop the wifi.monitor stream when leaving the tab so the device radio stops capturing.
    LeaveEffect { if (vm.streamMayRun("wifi.monitor")) vm.stopMonitor() }

    val eapolCount = remember(frames) { frames.count { it.info.isEapol } }
    val pmkHex = remember(psk, targetSsid) {
        if (targetSsid.isNotEmpty()) vm.derivePmkHex(psk, targetSsid) else null
    }
    val firstTs = frames.firstOrNull()?.tsMicros ?: 0L

    // One LazyColumn: controls are a header item and the captured frames follow as items,
    // so the whole screen scrolls and no control is pushed off-screen.
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        item {
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary),  contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text("Monitor my network - live 802.11 packet list and pcap export", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        JamDetectCard(vm, connected)
        Spacer(Modifier.height(8.dp))
        // Authorized-use note.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cs.errorContainer.copy(alpha = 0.5f)),
        ) {
            Text(
                "MONITOR YOUR OWN NETWORK. Capture only networks you own or are explicitly authorized to test. The PSK is used to decrypt the exported capture in Wireshark.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onErrorContainer,
                modifier = Modifier.padding(12.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        if (!connected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
            Spacer(Modifier.height(10.dp))
        }

        // Channel selector 1..13.
        Text("Channel", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..13).forEach { ch ->
                SegButton("$ch", channel == ch, Modifier) { channel = ch }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Target AP picker (reuse the already-scanned wardrive AP list).
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { pickAp = !pickAp }) { Text(if (pickAp) "Hide APs" else "Pick target AP") }
            Spacer(Modifier.size(8.dp))
            if (targetBssid != null) {
                Column(Modifier.weight(1f)) {
                    Text(targetSsid.ifEmpty { "(hidden)" }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text("$targetBssid", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = { targetBssid = null; targetSsid = "" }) { Text("Clear") }
            } else {
                Text("No target (capture all on channel)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
        if (pickAp) {
            Box(Modifier.fillMaxWidth().height(170.dp)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (aps.isEmpty()) {
                        Text("No scanned APs yet. Run a Wardrive scan first.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    aps.sortedByDescending { it.rssi }.forEach { ap ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                                targetBssid = ap.bssid
                                targetSsid = ap.ssid
                                if (ap.channel in 1..13) channel = ap.channel
                                pickAp = false
                            },
                            colors = CardDefaults.cardColors(containerColor = cs.surface),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(ap.ssid.ifEmpty { "(hidden)" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Text("${ap.bssid}  ch ${ap.channel}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                                }
                                RssiPill(ap.rssi)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // PSK field (for Wireshark export decryption).
        OutlinedTextField(
            value = psk,
            onValueChange = { psk = it },
            label = { Text("Your network password (PSK)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Used for Wireshark export decryption. Open the exported pcap in Wireshark and add this PSK (802.11 > Decryption keys, wpa-pwd) to view decrypted payloads; Wireshark performs the CCMP decryption.",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (pmkHex != null) {
            Text(
                "PMK (for Wireshark wpa-psk key): $pmkHex",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = connected || monitoring,
                modifier = Modifier.weight(1f),
                onClick = { if (monitoring) vm.stopMonitor() else vm.startMonitor(channel, targetBssid) }
            ) { Text(if (monitoring) "Stop" else "Start") }
            OutlinedButton(
                enabled = frames.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val f = vm.exportMonitorPcap()
                    if (f != null) shareFile(context, f, "application/vnd.tcpdump.pcap")
                    else Toast.makeText(context, "Nothing captured yet", Toast.LENGTH_SHORT).show()
                }
            ) { Text("Export pcap") }
            OutlinedButton(
                enabled = frames.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = {
                    val f = vm.exportMonitorPcap()
                    if (f != null) openWith(context, f, "application/vnd.tcpdump.pcap")
                    else Toast.makeText(context, "Nothing captured yet", Toast.LENGTH_SHORT).show()
                }
            ) { Text("Open pcap") }
            OutlinedButton(
                enabled = frames.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onClick = { vm.clearMonitor(); expanded = null }
            ) { Text("Clear") }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${frames.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = cs.primary)
            Spacer(Modifier.size(6.dp))
            Text("frames", color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.size(14.dp))
            Text("$eapolCount", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = cs.primary)
            Spacer(Modifier.size(6.dp))
            Text("EAPOL (handshake)", color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        }
        }

        items(frames.asReversed(), key = { it.index }) { fr ->
                val isOpen = expanded == fr.index
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                        expanded = if (isOpen) null else fr.index
                    },
                    colors = CardDefaults.cardColors(containerColor = cs.surface),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("#${fr.index}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.width(52.dp))
                            Text(
                                fr.info.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (fr.info.isEapol) cs.primary else cs.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text("%.3fs".format((fr.tsMicros - firstTs) / 1_000_000.0), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            Spacer(Modifier.size(8.dp))
                            Text("${fr.len}B", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            Spacer(Modifier.size(8.dp))
                            RssiPill(fr.rssi)
                        }
                        Text(
                            "dst ${fr.info.addr1.ifEmpty { "-" }}  src ${fr.info.addr2.ifEmpty { "-" }}",
                            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "bssid ${fr.info.addr3.ifEmpty { "-" }}  ch ${fr.ch}",
                            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace,
                        )
                        if (isOpen) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "type ${fr.info.type} subtype ${fr.info.subtype}  toDS ${if (fr.info.toDs) 1 else 0} fromDS ${if (fr.info.fromDs) 1 else 0}",
                                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                                Text(
                                    app.brucia.wifi.Dot11Frame.hexDump(fr.raw),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = cs.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        if (frames.isEmpty()) {
            item {
                Text(
                    if (monitoring) "Capturing... generate traffic on your network." else "No frames yet. Pick a channel (and optionally a target AP), then tap Start.",
                    color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun RemoteScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val frame by vm.remoteFrame.collectAsState()
    // From the ViewModel: survives rotation/resize with the session it describes.
    val running by vm.remoteRunning.collectAsState()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary),  contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text("Remote control - drive the whole device from the app", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (!connected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "Leave the device on its normal menu (not Brucia/companion), then Start.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = connected && !running, modifier = Modifier.weight(1f), onClick = { vm.startRemote() }) { Text("Start") }
            OutlinedButton(enabled = running, modifier = Modifier.weight(1f), onClick = { vm.stopRemote() }) { Text("Stop") }
        }
        Spacer(Modifier.height(12.dp))
        // Mirrored device screen.
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Black)) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                key(frame) {
                    val bmp = vm.remoteBitmap()
                    if (bmp != null && running) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Device screen",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text(if (running) "Waiting for screen..." else "Start to mirror the device screen", color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        // D-pad: drives the real Bruce UI via `nav`.
        val btn = @Composable { label: String, dir: String, mod: Modifier ->
            Button(enabled = running, onClick = { vm.remoteNav(dir) }, modifier = mod) { Text(label) }
        }
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            btn("Up", "up", Modifier.width(120.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                btn("Prev", "prev", Modifier.width(96.dp))
                btn("OK", "select", Modifier.width(96.dp))
                btn("Next", "next", Modifier.width(96.dp))
            }
            Spacer(Modifier.height(8.dp))
            btn("Down", "down", Modifier.width(120.dp))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = running, onClick = { vm.remoteNav("esc") }) { Text("Back") }
                OutlinedButton(enabled = running, onClick = { vm.remoteNav("prevpage") }) { Text("PrevPg") }
                OutlinedButton(enabled = running, onClick = { vm.remoteNav("nextpage") }) { Text("NextPg") }
            }
        }
    }
}

@Composable
private fun IrBrowseRow(title: String, subtitle: String, cs: androidx.compose.material3.ColorScheme, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = cs.surface),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Text(">", color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun IrDeviceRow(d: app.brucia.ir.CatalogDevice, cs: androidx.compose.material3.ColorScheme, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = cs.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(d.label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Text("${d.category.replace('_', ' ')} - ${d.buttons.size} buttons", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun IrScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val devices = remember { IrCatalog.load(context) }
    var buttons by rememberRetained { mutableStateOf(IrParser.samples) }
    var paste by rememberSaveable { mutableStateOf("") }
    var browsing by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selCategory by rememberSaveable { mutableStateOf<String?>(null) }
    var selBrand by rememberSaveable { mutableStateOf<String?>(null) }
    // 0 = Remotes (on-screen telecomandini), 1 = Catalog (raw button list), 2 = Raw / device actions.
    var irTab by rememberSaveable { mutableIntStateOf(0) }
    // The device whose on-screen remote is open; null shows the picker (quick TVs + browse).
    var selectedRemote by remember { mutableStateOf<CatalogDevice?>(null) }
    var remoteBrowse by remember { mutableStateOf(false) }
    val learned by vm.irLearned.collectAsState()
    val savedIr by vm.savedIr.collectAsState()
    LaunchedEffect(learned) { if (learned.isNotEmpty()) buttons = learned }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val parsed = IrParser.parse(text)
                if (parsed.isNotEmpty()) {
                    buttons = parsed
                    browsing = false
                    Toast.makeText(context, "Loaded ${parsed.size} codes", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "No codes found in file", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Read failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Hoisted so the browse lists can be emitted as items() of the single outer LazyColumn.
    val filtered = remember(devices, query) {
        if (query.isBlank()) emptyList()
        else devices.filter {
            it.brand.contains(query, true) || it.model.contains(query, true) ||
                it.category.contains(query, true)
        }
    }
    val cats = remember(devices) { devices.groupBy { it.category }.toList().sortedByDescending { it.second.size } }
    val brands = remember(devices, selCategory) {
        devices.filter { it.category == selCategory }.groupBy { it.brand }.toList().sortedBy { it.first.lowercase() }
    }
    val models = remember(devices, selCategory, selBrand) {
        devices.filter { it.category == selCategory && it.brand == selBrand }.sortedBy { it.model.lowercase() }
    }

    // A handful of ready-to-use TV remotes surfaced up front so the default case shows real
    // telecomandini immediately, not a raw list. Prefer well-known brands; fall back to the
    // first few TVs if those are missing.
    val tvDevices = remember(devices) { devices.filter { it.category.equals("TVs", true) } }
    val quickTv = remember(tvDevices) {
        val wanted = listOf("Samsung", "LG", "Sony", "Philips", "Panasonic", "Vizio", "TCL", "Hisense", "Sharp", "Toshiba", "Sanyo", "JVC")
        val picked = wanted.mapNotNull { br -> tvDevices.firstOrNull { it.brand.equals(br, true) } }
        if (picked.size >= 4) picked else tvDevices.take(8)
    }

    // The single send path reused from the catalog list: raw buttons go through ir_tx_raw,
    // protocol buttons through the CLI passthrough (ir tx ...).
    val transmit: (IrButton) -> Unit = { b ->
        if (b.raw) {
            val (khz, csv) = b.toTxRawArgs()
            vm.irTxRaw(khz, csv)
        } else {
            vm.sendCommand(b.toCli())
        }
    }

    // One LazyColumn: the controls are a header item and the catalog/button lists follow as items,
    // so the whole screen scrolls and nothing is pushed off-screen.
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        item {
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary), 
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Brucia",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = cs.primary,
            )
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text(
            "IR remotes - transmit through the device",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        DeviceCard(device)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Text(if (connected) "Reconnect over USB" else "Connect over USB")
        }
        Spacer(Modifier.height(12.dp))
        // Segmented control: Remotes / Catalog / Raw.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tabs = listOf("Remotes", "Catalog", "Raw")
            tabs.forEachIndexed { i, label ->
                if (irTab == i) {
                    Button(modifier = Modifier.weight(1f), onClick = { irTab = i }) { Text(label) }
                } else {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { irTab = i }) { Text(label) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        when (irTab) {
            0 -> {
                val sel = selectedRemote
                if (sel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { selectedRemote = null }) { Text("< Devices") }
                        Spacer(Modifier.width(10.dp))
                        Text(sel.label, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1)
                    }
                    IrRemote(
                        title = sel.label,
                        mapping = IrRemoteMap.mapRemote(sel.buttons),
                        enabled = connected,
                        onTransmit = transmit,
                    )
                } else {
                    Text(
                        "Tap a remote to open it. Each key transmits its IR code through the connected device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { remoteBrowse = !remoteBrowse; query = ""; selCategory = null; selBrand = null },
                    ) { Text(if (remoteBrowse) "Show TV remotes" else "Browse all devices (${devices.size})") }
                    Spacer(Modifier.height(10.dp))
                    if (remoteBrowse) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search brand / model / category") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (query.isBlank() && selCategory != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { selCategory = null; selBrand = null }) { Text("Categories") }
                                selCategory?.let {
                                    Text("/", color = cs.onSurfaceVariant)
                                    TextButton(onClick = { selBrand = null }) { Text(it.replace('_', ' ')) }
                                }
                                selBrand?.let { Text("/ $it", color = cs.onSurfaceVariant) }
                            }
                        }
                        Text(
                            when {
                                query.isNotBlank() -> "${filtered.size} devices"
                                selCategory == null -> "${cats.size} categories"
                                selBrand == null -> "${brands.size} brands"
                                else -> "${models.size} models"
                            },
                            color = cs.onSurfaceVariant,
                        )
                    } else {
                        Text("TV remotes", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    }
                }
            }
            1 -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(modifier = Modifier.weight(1f), onClick = { browsing = !browsing }) {
                        Text(if (browsing) "Close catalog" else "Catalog (${devices.size})")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { importLauncher.launch(arrayOf("*/*")) }
                    ) { Text("Import .ir") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        vm.learnIr()
                        Toast.makeText(context, "Point a remote at the device and press a button (10s)", Toast.LENGTH_LONG).show()
                    }
                ) { Text("Learn a remote (ir rx)") }
                Spacer(Modifier.height(12.dp))

                if (browsing) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search brand / model / category") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (query.isBlank() && (selCategory != null)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { selCategory = null; selBrand = null }) { Text("Categories") }
                            selCategory?.let {
                                Text("/", color = cs.onSurfaceVariant)
                                TextButton(onClick = { selBrand = null }) { Text(it.replace('_', ' ')) }
                            }
                            selBrand?.let { Text("/ $it", color = cs.onSurfaceVariant) }
                        }
                    }
                    Text(
                        when {
                            query.isNotBlank() -> "${filtered.size} devices"
                            selCategory == null -> "${cats.size} categories"
                            selBrand == null -> "${brands.size} brands"
                            else -> "${models.size} models"
                        },
                        color = cs.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        value = paste,
                        onValueChange = { paste = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Paste Flipper .ir content") },
                        minLines = 3,
                        maxLines = 5,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            enabled = paste.isNotBlank(),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val b = IrParser.parse(paste)
                                if (b.isNotEmpty()) {
                                    buttons = b
                                    Toast.makeText(context, "Loaded ${b.size} codes", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "No codes found", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) { Text("Load") }
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { buttons = IrParser.samples; paste = "" }
                        ) { Text("Samples") }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("${buttons.size} buttons", color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                }
            }
            else -> {
                IrActionsSection(vm, connected)
            }
        }
        }
        }

        // --- List bodies that belong to the outer LazyColumn ---
        if (irTab == 0) {
            if (selectedRemote == null) {
                if (remoteBrowse) {
                    when {
                        query.isNotBlank() ->
                            items(filtered) { d -> IrDeviceRow(d, cs) { selectedRemote = d } }
                        selCategory == null ->
                            items(cats) { (cat, list) -> IrBrowseRow(cat.replace('_', ' '), "${list.size} devices", cs) { selCategory = cat } }
                        selBrand == null ->
                            items(brands) { (brand, list) -> IrBrowseRow(brand, "${list.size} models", cs) { selBrand = brand } }
                        else ->
                            items(models) { d -> IrBrowseRow(d.model, "${d.buttons.size} buttons", cs) { selectedRemote = d } }
                    }
                } else {
                    items(quickTv) { d -> IrDeviceRow(d, cs) { selectedRemote = d } }
                }
            }
        } else if (irTab == 1) {
            if (browsing) {
                when {
                    // Flat search across everything when the user types.
                    query.isNotBlank() ->
                        items(filtered) { d -> IrDeviceRow(d, cs) { buttons = d.buttons; browsing = false; Toast.makeText(context, "${d.label}: ${d.buttons.size} buttons", Toast.LENGTH_SHORT).show() } }
                    // Level 1: categories.
                    selCategory == null ->
                        items(cats) { (cat, list) -> IrBrowseRow(cat.replace('_', ' '), "${list.size} devices", cs) { selCategory = cat } }
                    // Level 2: brands within the category.
                    selBrand == null ->
                        items(brands) { (brand, list) -> IrBrowseRow(brand, "${list.size} models", cs) { selBrand = brand } }
                    // Level 3: models for category + brand.
                    else ->
                        items(models) { d -> IrBrowseRow(d.model, "${d.buttons.size} buttons", cs) { buttons = d.buttons; browsing = false; Toast.makeText(context, "${d.label}: ${d.buttons.size} buttons", Toast.LENGTH_SHORT).show() } }
                }
            } else {
                items(buttons) { b ->
                    IrButtonCard(
                        button = b,
                        enabled = connected,
                        onSend = {
                            vm.sendCommand(b.toCli())
                            Toast.makeText(context, "Sending ${b.name} (device reply in the console)", Toast.LENGTH_SHORT).show()
                        },
                        onReplayRaw = if (b.raw) {
                            {
                                val (khz, csv) = b.toTxRawArgs()
                                vm.irTxRaw(khz, csv)
                                Toast.makeText(context, "Sending raw ${b.name} (device reply in the console)", Toast.LENGTH_SHORT).show()
                            }
                        } else null,
                    )
                }
            }
        }
    }
}

/**
 * A reusable on-screen remote control ("telecomandino"): power, volume/channel rockers,
 * mute, a D-pad with OK, a number pad and Input/Menu/Home/Back. Each key transmits its
 * mapped IR code on tap (via [onTransmit]) and flashes briefly; keys with no mapping for
 * the selected device are shown disabled.
 */
@Composable
private fun IrRemote(
    title: String,
    mapping: Map<RemoteKey, IrButton>,
    enabled: Boolean,
    onTransmit: (IrButton) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var flashKey by remember(title) { mutableStateOf<RemoteKey?>(null) }
    var flashTick by remember(title) { mutableIntStateOf(0) }
    var lastName by remember(title) { mutableStateOf("") }
    LaunchedEffect(flashTick) { if (flashTick > 0) { delay(650); flashKey = null } }

    val tap: (RemoteKey, IrButton) -> Unit = { key, b ->
        onTransmit(b)
        lastName = b.name
        flashKey = key
        flashTick++
        Toast.makeText(context, "Sending ${b.name} (device reply in the console)", Toast.LENGTH_SHORT).show()
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                if (flashKey != null) "Sending $lastName" else "${mapping.size} keys mapped",
                style = MaterialTheme.typography.bodySmall,
                color = if (flashKey != null) cs.primary else cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            // Power + Mute
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RkButton("POWER", RemoteKey.POWER, mapping, enabled, flashKey, strong = true, modifier = Modifier.weight(1f), onTap = tap)
                RkButton("MUTE", RemoteKey.MUTE, mapping, enabled, flashKey, modifier = Modifier.weight(1f), onTap = tap)
            }
            Spacer(Modifier.height(12.dp))

            // Volume rocker | Channel rocker
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("VOL", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    RkButton("+", RemoteKey.VOL_UP, mapping, enabled, flashKey, modifier = Modifier.fillMaxWidth(), onTap = tap)
                    Spacer(Modifier.height(6.dp))
                    RkButton("-", RemoteKey.VOL_DOWN, mapping, enabled, flashKey, modifier = Modifier.fillMaxWidth(), onTap = tap)
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CH", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    RkButton("+", RemoteKey.CH_UP, mapping, enabled, flashKey, modifier = Modifier.fillMaxWidth(), onTap = tap)
                    Spacer(Modifier.height(6.dp))
                    RkButton("-", RemoteKey.CH_DOWN, mapping, enabled, flashKey, modifier = Modifier.fillMaxWidth(), onTap = tap)
                }
            }
            Spacer(Modifier.height(14.dp))

            // D-pad
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                RkButton("↑", RemoteKey.UP, mapping, enabled, flashKey, modifier = Modifier.weight(1f), onTap = tap)
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RkButton("←", RemoteKey.LEFT, mapping, enabled, flashKey, modifier = Modifier.weight(1f), onTap = tap)
                RkButton("OK", RemoteKey.OK, mapping, enabled, flashKey, strong = true, modifier = Modifier.weight(1f), onTap = tap)
                RkButton("→", RemoteKey.RIGHT, mapping, enabled, flashKey, modifier = Modifier.weight(1f), onTap = tap)
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                RkButton("↓", RemoteKey.DOWN, mapping, enabled, flashKey, modifier = Modifier.weight(1f), onTap = tap)
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))

            // Input / Menu / Home / Back
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RkButton("INPUT", RemoteKey.INPUT, mapping, enabled, flashKey, modifier = Modifier.weight(1f), onTap = tap)
                RkButton("MENU", RemoteKey.MENU, mapping, enabled, flashKey, modifier = Modifier.weight(1f), onTap = tap)
                RkButton("HOME", RemoteKey.HOME, mapping, enabled, flashKey, modifier = Modifier.weight(1f), onTap = tap)
                RkButton("BACK", RemoteKey.BACK, mapping, enabled, flashKey, modifier = Modifier.weight(1f), onTap = tap)
            }
            Spacer(Modifier.height(14.dp))

            // Number pad
            val rows = listOf(
                listOf("1" to RemoteKey.NUM_1, "2" to RemoteKey.NUM_2, "3" to RemoteKey.NUM_3),
                listOf("4" to RemoteKey.NUM_4, "5" to RemoteKey.NUM_5, "6" to RemoteKey.NUM_6),
                listOf("7" to RemoteKey.NUM_7, "8" to RemoteKey.NUM_8, "9" to RemoteKey.NUM_9),
            )
            rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, key) ->
                        RkButton(label, key, mapping, enabled, flashKey, modifier = Modifier.weight(1f), onTap = tap)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.weight(1f))
                RkButton("0", RemoteKey.NUM_0, mapping, enabled, flashKey, modifier = Modifier.weight(1f), onTap = tap)
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One key of an [IrRemote]. Disabled when the device has no code mapped to [key]. */
@Composable
private fun RkButton(
    label: String,
    key: RemoteKey,
    mapping: Map<RemoteKey, IrButton>,
    enabled: Boolean,
    flashKey: RemoteKey?,
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    onTap: (RemoteKey, IrButton) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val mapped = mapping[key]
    val isFlash = flashKey == key
    val container = when {
        isFlash -> cs.primary
        strong -> cs.primary
        else -> cs.surface
    }
    val content = if (isFlash || strong) cs.onPrimary else cs.onSurface
    Button(
        onClick = { mapped?.let { onTap(key, it) } },
        enabled = enabled && mapped != null,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        colors = BruciaButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = cs.surface.copy(alpha = 0.35f),
            disabledContentColor = cs.onSurfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Text(
            label,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun IrButtonCard(
    button: IrButton,
    enabled: Boolean,
    onSend: () -> Unit,
    onReplayRaw: (() -> Unit)? = null,
) {
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
                Text(button.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                val detail = if (button.raw) "raw - ${button.frequency} Hz"
                    else "${button.protocol}  ${button.address} ${button.command}"
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (onReplayRaw != null) {
                OutlinedButton(enabled = enabled, onClick = onReplayRaw) { Text("Replay raw") }
                Spacer(Modifier.size(8.dp))
            }
            Button(enabled = enabled, onClick = onSend) { Text("Send") }
        }
    }
}

/**
 * The fixed-contract device IR actions, grouped and clearly separated from the
 * universal catalog / paste / learn flow: TV-B-Gone (ir_tvbgone), Learn IR receive
 * (ir_rx) with a save-to-learned step, and raw replay (ir_tx_raw, via the learned list).
 */
@Composable
private fun IrActionsSection(vm: WardriveViewModel, connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val tvStatus by vm.irTvBGoneStatus.collectAsState()
    val rxStatus by vm.irRxStatus.collectAsState()
    val rxResult by vm.irRxResult.collectAsState()
    val device by vm.device.collectAsState()
    // A LITE firmware compiles ir_tvbgone / ir_jammer out (features.ir_cmds:false); receive and raw
    // replay fall back to Bruce's own `ir` CLI in the ViewModel, so only these two are disabled.
    val irCmdsOmitted = device?.features?.omits(app.brucia.wardrive.DeviceFeatures.IR_CMDS) == true
    var region by rememberSaveable { mutableStateOf("na") }
    LeaveEffect { vm.stopIrJammer(silent = true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                "Device IR actions",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = cs.primary,
            )
            Spacer(Modifier.height(10.dp))

            // --- TV-B-Gone ---
            Text("Region", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val naSel = region == "na"
                if (naSel) {
                    Button(modifier = Modifier.weight(1f), onClick = { region = "na" }) { Text("NA") }
                } else {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { region = "na" }) { Text("NA") }
                }
                val euSel = region == "eu"
                if (euSel) {
                    Button(modifier = Modifier.weight(1f), onClick = { region = "eu" }) { Text("EU") }
                } else {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { region = "eu" }) { Text("EU") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = connected && !irCmdsOmitted,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    vm.irTvBGone(region)
                    Toast.makeText(context, "TV-B-Gone (${region.uppercase()}) started", Toast.LENGTH_SHORT).show()
                },
            ) { Text("TV-B-Gone (turn off TVs)") }
            Text(
                "Authorized use only. This sweeps power-off codes for many TVs - use it only on displays you are permitted to control.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            if (irCmdsOmitted) {
                Text(app.brucia.wardrive.DeviceFeatures.OMITTED_REASON, style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
            if (tvStatus.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(tvStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurface)
            }

            Spacer(Modifier.height(14.dp))

            // --- IR jammer ---
            val irJamming by vm.irJamming.collectAsState()
            val irJamStatus by vm.irJammerStatus.collectAsState()
            Button(
                enabled = connected && (irJamming || !irCmdsOmitted),
                modifier = Modifier.fillMaxWidth(),
                colors = BruciaButtonDefaults.buttonColors(containerColor = if (irJamming) Color(0xFFD32F2F) else cs.primary),
                onClick = { if (irJamming) vm.stopIrJammer() else vm.startIrJammer() },
            ) { Text(if (irJamming) "Stop IR jammer" else "IR jammer (flood IR)") }
            Text(
                "Authorized use only. Emits IR noise in bursts until you Stop; whether a nearby receiver is disturbed is not verified.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            if (irCmdsOmitted) {
                Text(app.brucia.wardrive.DeviceFeatures.OMITTED_REASON, style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
            if (irJamStatus.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(irJamStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurface)
            }

            Spacer(Modifier.height(14.dp))

            // --- Learn IR (receive) ---
            OutlinedButton(
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.irReceiveOnce() },
            ) { Text("Learn IR (receive)") }
            if (rxStatus.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(rxStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurface)
            }
            val result = rxResult
            if (result != null) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            vm.saveLearnedIr(result)
                            Toast.makeText(context, "Saved ${result.name}", Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("Save to learned") }
                    if (result.raw) {
                        OutlinedButton(
                            enabled = connected,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val (khz, csv) = result.toTxRawArgs()
                                vm.irTxRaw(khz, csv)
                                Toast.makeText(context, "Sending raw ${result.name} (device reply in the console)", Toast.LENGTH_SHORT).show()
                            },
                        ) { Text("Replay raw") }
                    }
                }
            }
        }
    }
}

private val BRUTE_PROTOCOLS = listOf("CAME 12bit", "NICE 12bit", "Ansonic 12bit", "Holtek 12bit", "Linear 10bit", "Chamberlain 9bit")

@Composable
private fun SubGhzBruteforceCard(vm: WardriveViewModel, connected: Boolean, freqHz: Long) {
    val cs = MaterialTheme.colorScheme
    val active by vm.subBruteActive.collectAsState()
    val status by vm.subBruteStatus.collectAsState()
    var proto by rememberSaveable { mutableIntStateOf(0) }
    var secs by rememberSaveable { mutableStateOf("20") }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text("Brute force", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Text("Transmits codes of a fixed-code protocol in sequence on ${"%.2f".format(freqHz / 1_000_000.0)} MHz for the chosen time (gates/remotes you own); it may end before every code is sent, and the reply reports how many were. Authorized use only.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("Protocol", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            BRUTE_PROTOCOLS.chunked(2).forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { c, name ->
                        val idx = r * 2 + c
                        val sel = proto == idx
                        if (sel) Button(onClick = { proto = idx }, modifier = Modifier.weight(1f)) { Text(name, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                        else OutlinedButton(onClick = { proto = idx }, modifier = Modifier.weight(1f)) { Text(name, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
            }
            OutlinedTextField(secs, { secs = it.filter { ch -> ch.isDigit() }.take(2) }, label = { Text("Seconds (1-30)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(enabled = connected && !active, modifier = Modifier.fillMaxWidth(),
                onClick = { vm.subghzBruteforce(proto, freqHz, 0, (secs.toIntOrNull() ?: 20).coerceIn(1, 30)) }) {
                Text(if (active) "Running..." else "Start brute force")
            }
            if (status.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SubGhzJammerCard(vm: WardriveViewModel, connected: Boolean, freqHz: Long) {
    val cs = MaterialTheme.colorScheme
    val jamming by vm.subghzJamming.collectAsState()
    val jamStatus by vm.subghzJammerStatus.collectAsState()
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Jammer", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Text(
                "Floods the selected frequency (${"%.2f".format(freqHz / 1_000_000.0)} MHz) with noise, in bursts, until you Stop. Authorized / lawful use only.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
                colors = BruciaButtonDefaults.buttonColors(containerColor = if (jamming) Color(0xFFD32F2F) else cs.primary),
                onClick = { if (jamming) vm.stopSubghzJammer() else vm.startSubghzJammer((freqHz / 1000).toInt()) },
            ) { Text(if (jamming) "Stop jammer" else "Start jammer") }
            if (jamStatus.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(jamStatus, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SubGhzScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val signals by vm.subghz.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    // Follows the real SUB outcome: an ERR (e.g. 503 receiver init failed) or timeout turns it off.
    val listening by vm.subghzListening.collectAsState()
    val listenError by vm.subghzRawError.collectAsState()
    var freqHz by rememberSaveable { mutableLongStateOf(433_920_000L) }
    var craftHex by rememberSaveable { mutableStateOf("") }
    val savedRf by vm.savedRf.collectAsState()
    val wave by vm.rfWave.collectAsState()
    val waveCompleteness by vm.rfWaveCompleteness.collectAsState()
    val capture by vm.rfCapture.collectAsState()
    val context = LocalContext.current
    val freqs = listOf(315_000_000L to "315", 433_920_000L to "433.92", 868_350_000L to "868.35", 915_000_000L to "915")
    val importedSub by vm.importedSub.collectAsState()
    val subImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSub(uri)
    }
    // Stop the subghz.raw/spectrum streams, any recording and the jammer when leaving the tool so
    // the device RMT RX does not keep running. A rotation or resize is not leaving (LeaveEffect):
    // Listen keeps running and the recreated screen re-binds to vm.subghzListening.
    LeaveEffect {
        vm.stopRfCapture()
        if (vm.streamMayRun("subghz.raw")) vm.setSubghz(false)
        if (vm.streamMayRun("subghz.spectrum")) vm.stopSpectrum()
        vm.stopSubghzJammer(silent = true)
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary), 
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Brucia",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = cs.primary,
            )
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text(
            "Sub-GHz - capture on device, decode on phone",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        DeviceCard(device)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Text(if (connected) "Reconnect over USB" else "Connect over USB")
        }
        Spacer(Modifier.height(12.dp))
        StreamToggleRow("Listen", "subghz.raw stream", checked = listening, enabled = connected,
            onCheckedChange = { vm.setSubghz(it) })
        if (listenError.isNotEmpty() && !listening) {
            Text("Listen failed: $listenError", color = cs.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        SpectrumSection(vm, connected)
        Spacer(Modifier.height(12.dp))
        SubGhzJammerCard(vm, connected, freqHz)
        Spacer(Modifier.height(12.dp))
        SubGhzBruteforceCard(vm, connected, freqHz)
        Spacer(Modifier.height(12.dp))
        // Record the raw subghz.raw timing stream to a CSV on the phone.
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Record to CSV", fontWeight = FontWeight.SemiBold, color = cs.primary)
                        Text(
                            if (capture.capturing) "Recording - ${capture.samples} samples"
                            else "${capture.samples} samples captured",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                        if (capture.atBufferLimit > 0) {
                            Text(
                                "${capture.atBufferLimit} filled the device buffer (possibly truncated)",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                            )
                        }
                    }
                    Button(
                        enabled = connected || capture.capturing,
                        onClick = {
                            if (capture.capturing) {
                                vm.stopRfCapture()
                            } else {
                                vm.startRfCapture()
                            }
                        }
                    ) { Text(if (capture.capturing) "Stop" else "Start") }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = capture.samples > 0,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val f = vm.exportRfCsv()
                        if (f != null) shareFile(context, f, "text/csv")
                        else Toast.makeText(context, "Nothing captured yet", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("Export / Share CSV") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            freqs.forEach { (hz, label) ->
                SegButton("$label MHz", freqHz == hz, Modifier) { freqHz = hz }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
            onClick = { vm.rfCapture(freqHz); Toast.makeText(context, "Capturing on ${freqHz / 1_000_000.0} MHz (rf rx)...", Toast.LENGTH_SHORT).show() }
        ) { Text("Capture & save (rf rx)") }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = { vm.decodeSample() }) {
                Text("Decode sample")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = signals.isNotEmpty(),
                onClick = { vm.clearSubghz() }
            ) { Text("Clear") }
        }
        Spacer(Modifier.height(12.dp))
        // De Bruijn brute force: open every fixed N-bit OOK code in minimal time.
        var dbBits by rememberSaveable { mutableIntStateOf(12) }
        var dbTe by rememberSaveable { mutableIntStateOf(400) }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("De Bruijn sequence", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Text(
                    "Compute the shortest ${dbBits}-bit De Bruijn sweep on the phone. Note: the device " +
                        "subghz tx sends a single fixed code and cannot carry a full sweep, so Transmit " +
                        "refuses with a reason and sends nothing.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Frequency (MHz)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    freqs.forEach { (hz, label) -> SegButton("$label", freqHz == hz, Modifier) { freqHz = hz } }
                }
                Spacer(Modifier.height(8.dp))
                Text("Code length (bits)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeBruijn.OFFERED_ORDERS.forEach { n -> SegButton("$n", dbBits == n, Modifier) { dbBits = n } }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = dbBits.toString(),
                    onValueChange = { s -> s.toIntOrNull()?.let { dbBits = it.coerceIn(1, DeBruijn.MAX_ORDER) } },
                    label = { Text("Custom order (1-${DeBruijn.MAX_ORDER})") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("Pulse width te (us)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(200, 300, 400, 500).forEach { t -> SegButton("${t}us", dbTe == t, Modifier) { dbTe = t } }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = dbTe.toString(),
                    onValueChange = { s -> s.toIntOrNull()?.let { dbTe = it.coerceIn(50, 2000) } },
                    label = { Text("Custom te (50-2000 us)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Sequence size, so the scale (and why the device cannot carry it) is clear.
                val seqBits = 1L shl dbBits
                val fitsDeviceKey = seqBits <= DeBruijn.DEVICE_TX_KEY_BITS
                Spacer(Modifier.height(6.dp))
                Text(
                    "= $seqBits bits" +
                        if (!fitsDeviceKey) "  (exceeds the ${DeBruijn.DEVICE_TX_KEY_BITS}-bit device code - not transmittable)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!fitsDeviceKey) Amber else cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val reason = vm.transmitDeBruijn(dbBits, freqHz, dbTe, 1)
                        Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
                    }
                ) { Text("Transmit de Bruijn") }
                Text(
                    "The device transmit path cannot carry a De Bruijn sweep; Transmit refuses and sends nothing.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
        }
        if (wave.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Waveform (${wave.size} edges)" +
                    if (waveCompleteness == app.brucia.subghz.RawCompleteness.UNKNOWN_AT_LIMIT) " - possibly truncated (device buffer full)" else "",
                color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Waveform(wave, Modifier.fillMaxWidth().height(90.dp).padding(10.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        // Flipper .sub import / export.
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Sub-GHz files (.sub)", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Text(
                    "Import a Flipper/Bruce .sub to decode and transmit, or export the " +
                        "last decoded/captured signal.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { subImportLauncher.launch(arrayOf("*/*")) }
                    ) { Text("Import .sub") }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        enabled = signals.isNotEmpty() || wave.isNotEmpty(),
                        onClick = {
                            val f = vm.exportSub(signals.firstOrNull(), freqHz)
                            if (f != null) shareFile(context, f, "application/octet-stream")
                            else Toast.makeText(context, "Nothing to export yet", Toast.LENGTH_SHORT).show()
                        }
                    ) { Text("Export .sub") }
                }
                val sub = importedSub
                if (sub != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(SubFile.summary(sub), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                    val txCmd = remember(sub) { SubFile.transmitCommand(sub) }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = connected && txCmd != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            val sent = vm.transmitSub()
                            Toast.makeText(
                                context,
                                if (sent != null) "Transmitting..." else "Cannot transmit this signal",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) { Text("Transmit") }
                    if (txCmd == null) {
                        Text(
                            "RAW signal with no recognised protocol - transmit over USB is " +
                                "unavailable (needs the file on device storage).",
                            style = MaterialTheme.typography.bodySmall, color = Amber,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Bounded lists rendered inline (the whole screen is one verticalScroll container;
        // a LazyColumn here would be a nested same-axis scrollable).
        if (savedRf.isNotEmpty()) {
            Text("Saved signals (${savedRf.size})", color = cs.onSurfaceVariant)
            // Replay only captures stored with a replay command; others (a protocol `rf tx` cannot
            // reproduce, or RAW) keep their text but offer no Replay that would send something else.
            savedRf.forEach { srf ->
                SavedRow(srf, onReplay = if (srf.payload.startsWith("rf tx")) ({ vm.replaySaved(srf) }) else null,
                    onDelete = { vm.deleteSaved(srf.id) })
            }
        }
        if (signals.isNotEmpty()) {
            Text("Decoded on phone (${signals.size})", color = cs.onSurfaceVariant)
            signals.forEach { sig -> SignalCard(sig) }
        }
    }
}

/**
 * Sub-GHz spectrum section: Start/Stop drives the "subghz.spectrum" stream, sweeps are collected
 * into a rolling history and drawn as a waterfall (rows = sweeps over time, x = frequency,
 * colour = RSSI) plus a current RSSI-vs-frequency line.
 */
@Composable
private fun SpectrumSection(vm: WardriveViewModel, connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val latest by vm.spectrum.collectAsState()
    val history by vm.spectrumHistory.collectAsState()
    val active by vm.spectrumActive.collectAsState()
    val spectrumError by vm.spectrumError.collectAsState()
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Spectrum waterfall", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text(
                        "subghz.spectrum stream - sweeps over time, colour = RSSI",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                Button(
                    enabled = connected || active,
                    onClick = { if (active) vm.stopSpectrum() else vm.startSpectrum() },
                ) { Text(if (active) "Stop" else "Start") }
            }
            if (spectrumError.isNotEmpty() && !active) {
                Spacer(Modifier.height(6.dp))
                Text("Spectrum failed: $spectrumError", color = cs.error, style = MaterialTheme.typography.bodySmall)
            }
            val cur = latest
            if (history.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (active) "Waiting for sweeps..." else "Tap Start to sweep the sub-GHz band.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(10.dp))
                SpectrumWaterfall(history, Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(6.dp)))
                if (cur != null && cur.rssi.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    Text("Current sweep", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    SpectrumLine(cur, Modifier.fillMaxWidth().height(70.dp).padding(top = 2.dp))
                }
                if (cur != null && cur.freqs.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    val lo = cur.freqs.first() / 1_000_000.0
                    val hi = cur.freqs.last() / 1_000_000.0
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(String.format(java.util.Locale.ROOT, "%.2f MHz", lo), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text("${cur.rssi.size} pts - ${history.size} sweeps", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        Text(String.format(java.util.Locale.ROOT, "%.2f MHz", hi), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { vm.clearSpectrum() }, modifier = Modifier.fillMaxWidth()) { Text("Clear") }
            }
        }
    }
}

/** Map a 0..1 normalised RSSI to a cold -> warm -> hot colour ramp. */
private fun spectrumColor(v: Float): Color {
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

/** Waterfall: one row per sweep (oldest at top), x = frequency bin, colour = normalised RSSI. */
@Composable
private fun SpectrumWaterfall(history: List<SpectrumSweep>, modifier: Modifier) {
    Canvas(modifier = modifier) {
        if (history.isEmpty()) return@Canvas
        var mn = Int.MAX_VALUE
        var mx = Int.MIN_VALUE
        for (s in history) for (r in s.rssi) { if (r < mn) mn = r; if (r > mx) mx = r }
        val range = (mx - mn).coerceAtLeast(1).toFloat()
        val rowH = size.height / history.size
        history.forEachIndexed { idx, sweep ->
            val n = sweep.rssi.size
            if (n == 0) return@forEachIndexed
            val y = idx * rowH
            val cellW = size.width / n
            for (i in 0 until n) {
                val v = (sweep.rssi[i] - mn) / range
                drawRect(
                    color = spectrumColor(v),
                    topLeft = Offset(i * cellW, y),
                    size = Size(cellW + 1f, rowH + 1f),
                )
            }
        }
    }
}

/** Current sweep as an RSSI-vs-frequency polyline (x = frequency bin, y = RSSI, inverted). */
@Composable
private fun SpectrumLine(sweep: SpectrumSweep, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val n = sweep.rssi.size
        if (n < 2) return@Canvas
        var mn = Int.MAX_VALUE
        var mx = Int.MIN_VALUE
        for (r in sweep.rssi) { if (r < mn) mn = r; if (r > mx) mx = r }
        val range = (mx - mn).coerceAtLeast(1).toFloat()
        val stepX = size.width / (n - 1)
        val pad = size.height * 0.08f
        val usable = size.height - pad * 2
        fun yOf(i: Int) = pad + (1f - (sweep.rssi[i] - mn) / range) * usable
        var prev = Offset(0f, yOf(0))
        for (i in 1 until n) {
            val cur = Offset(i * stepX, yOf(i))
            drawLine(color, prev, cur, strokeWidth = 2f)
            prev = cur
        }
    }
}

@Composable
private fun SignalCard(sig: DecodedSignal) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sig.protocol, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                val rolling = sig.classification == "rolling"
                val tag = sig.classification.uppercase()
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (rolling) Amber else cs.primary,
                )
            }
            Text(
                "0x${sig.codeHex}",
                style = MaterialTheme.typography.titleMedium,
                color = cs.primary,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                "${sig.bits} bits  -  TE ${sig.teMicros} us  -  x${sig.repeats}",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            if (sig.serialHex.isNotEmpty()) {
                Text(
                    "serial 0x${sig.serialHex}" + if (sig.button >= 0) "  -  button ${sig.button}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            if (sig.note.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(sig.note, style = MaterialTheme.typography.bodySmall, color = Amber)
            }
        }
    }
}

@Composable
private fun ConsoleScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val log by vm.console.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var cmd by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary), 
                contentDescription = null,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "Brucia",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = cs.primary,
            )
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text(
            "Console - run any Bruce CLI command, output on the phone",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        if (!connected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
            Spacer(Modifier.height(8.dp))
        }
        // Command palette: every device CLI group, so all the device's choices are here.
        var group by rememberSaveable { mutableIntStateOf(0) }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CliCatalog.groups.forEachIndexed { i, g ->
                SegButton(g.name, group == i, Modifier) { group = i }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CliCatalog.groups[group].commands.forEach { c ->
                OutlinedButton(
                    enabled = connected,
                    onClick = {
                        if (c.template.contains("<")) cmd = c.template // needs args: fill the input
                        else vm.sendConsole(c.template)               // no args: run now
                    },
                ) { Text(c.template.substringBefore(" <").let { if (it.length > 22) it.take(22) else it }) }
            }
        }
        CliCatalog.groups[group].commands.getOrNull(0)?.let {
            Text(
                "Tap a command to run it or fill the console; <...> are arguments to replace.",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(log) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (line.startsWith("> ")) cs.primary else cs.onSurface,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                modifier = Modifier.weight(1f),
                label = { Text("command") },
                singleLine = true,
            )
            Button(
                enabled = connected && cmd.isNotBlank(),
                onClick = { vm.sendConsole(cmd.trim()); cmd = "" }
            ) { Text("Run") }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.clearConsole() }, modifier = Modifier.weight(1f)) { Text("Clear") }
            // Export the app log (logcat + swallowed errors across ROMs) for GitHub issues: save a
            // copy into the public Download/Brucia folder (easy to find) and open the share sheet.
            OutlinedButton(
                onClick = {
                    val path = app.brucia.BruciaLog.exportToDownloads(context, 2000)
                    val f = app.brucia.BruciaLog.exportTail(context, 2000)
                    val msg = if (path != null) "Saved to $path" else "No log yet"
                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                    if (f != null) shareFile(context, f, "text/plain")
                },
                modifier = Modifier.weight(1f),
            ) { Text("Export log") }
        }
    }
}

// Example JS snippets offered on the Scripts screen; loaded into the editor on tap. BruceJS has no
// global print(): console.log() is what script_run returns as output.
internal val scriptExamples = listOf(
    "Hello" to "console.log(\"Hello from Brucia\");",
    "Loop" to "for (var i = 0; i < 5; i++) {\n  console.log(\"line \" + i);\n}",
    "Device" to "console.log(\"board: \" + device.getName());\nconsole.log(\"free heap: \" + device.getFreeHeapSize());",
)

@Composable
private fun ScriptsScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val output by vm.scriptOutput.collectAsState()
    val running by vm.scriptBusy.collectAsState()
    val stopping by vm.scriptStopping.collectAsState()
    var src by rememberSaveable { mutableStateOf(scriptExamples[0].second) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary),  contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text(
            "Scripts - write JavaScript and run it on the device",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        if (!connected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
            Spacer(Modifier.height(8.dp))
        }
        Text("Examples", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            scriptExamples.forEach { (name, code) ->
                OutlinedButton(onClick = { src = code }) { Text(name) }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = src,
            onValueChange = { src = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            label = { Text("JavaScript source") },
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // While a script runs, Run turns into Stop: the device ends the script and the run's
            // reply shows the outcome; a new run is possible only after that reply.
            if (running || stopping) {
                Button(
                    enabled = connected && running && !stopping,
                    onClick = { vm.scriptStop() },
                    modifier = Modifier.weight(1f),
                ) { Text(if (stopping) "Stopping..." else "Stop") }
            } else {
                Button(
                    enabled = connected && src.isNotBlank(),
                    onClick = { vm.scriptRun(src) },
                    modifier = Modifier.weight(1f),
                ) { Text("Run") }
            }
            OutlinedButton(
                onClick = { vm.clearScriptOutput() },
                modifier = Modifier.weight(1f),
            ) { Text("Clear output") }
        }
        Spacer(Modifier.height(8.dp))
        Text("Output", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(cs.surface)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(
                output.ifBlank { "No output yet." },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurface,
            )
        }
    }
}

@Composable
private fun UtilitiesScreen() {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val copyText = rememberTextCopier()
    val copySecret = rememberTextCopier(sensitive = true)
    fun copy(label: String, text: String, secret: Boolean = false) {
        if (text.isEmpty()) return
        if (secret) copySecret(text) else copyText(text)
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary),  contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
        }
        Text(
            "Utilities - offline phone tools, no device needed",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        UtilCard("Password generator") {
            var length by rememberSaveable { mutableStateOf("16") }
            var lower by rememberSaveable { mutableStateOf(true) }
            var upper by rememberSaveable { mutableStateOf(true) }
            var digits by rememberSaveable { mutableStateOf(true) }
            var symbols by rememberSaveable { mutableStateOf(false) }
            var result by remember { mutableStateOf("") }
            OutlinedTextField(
                value = length,
                onValueChange = { v -> length = v.filter { it.isDigit() }.take(3) },
                label = { Text("Length") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            ToggleRow("Lowercase a-z", lower) { lower = it }
            ToggleRow("Uppercase A-Z", upper) { upper = it }
            ToggleRow("Digits 0-9", digits) { digits = it }
            ToggleRow("Symbols", symbols) { symbols = it }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val set = app.brucia.utils.PhoneUtils.charset(lower, upper, digits, symbols)
                    val len = length.toIntOrNull() ?: 0
                    result = if (set.isEmpty()) "Select at least one character set"
                    else app.brucia.utils.PhoneUtils.generatePassword(len.coerceIn(1, 128), set)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Generate") }
            if (result.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ResultRow(result) { copy("Password", result, secret = true) }
            }
        }

        UtilCard("Hash calculator") {
            var input by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Text to hash") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (input.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                for ((name, value) in listOf(
                    "MD5" to app.brucia.utils.PhoneUtils.md5(input),
                    "SHA-1" to app.brucia.utils.PhoneUtils.sha1(input),
                    "SHA-256" to app.brucia.utils.PhoneUtils.sha256(input),
                )) {
                    Text(name, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    ResultRow(value) { copy(name, value) }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        UtilCard("Base64 encode / decode") {
            var input by rememberSaveable { mutableStateOf("") }
            var output by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Text") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { output = app.brucia.utils.PhoneUtils.base64Encode(input) }, modifier = Modifier.weight(1f)) { Text("Encode") }
                Button(
                    onClick = {
                        output = try { app.brucia.utils.PhoneUtils.base64Decode(input) } catch (_: Exception) { "Invalid base64" }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Decode") }
            }
            if (output.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ResultRow(output) { copy("Result", output) }
            }
        }

        UtilCard("MAC vendor lookup") {
            var mac by rememberSaveable { mutableStateOf("") }
            var vendor by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = mac,
                onValueChange = { mac = it },
                label = { Text("MAC / BSSID") },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    vendor = "Looking up..."
                    Thread {
                        app.brucia.wardrive.OuiLookup.ensureLoaded(context)
                        val v = app.brucia.wardrive.OuiLookup.vendor(mac)
                        vendor = v ?: "Unknown vendor"
                    }.start()
                },
                enabled = mac.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Look up") }
            if (vendor.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(vendor, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            }
        }

        UtilCard("Wi-Fi QR generator") {
            var ssid by rememberSaveable { mutableStateOf("") }
            var pass by remember { mutableStateOf("") }
            var auth by rememberSaveable { mutableStateOf("WPA") }
            var qr by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
            var payload by remember { mutableStateOf("") }
            OutlinedTextField(value = ssid, onValueChange = { ssid = it }, label = { Text("SSID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in listOf("WPA", "WEP", "nopass")) {
                    val active = auth == a
                    OutlinedButton(
                        onClick = { auth = a },
                        modifier = Modifier.weight(1f),
                        colors = if (active) BruciaButtonDefaults.outlinedButtonColors(containerColor = cs.primaryContainer) else BruciaButtonDefaults.outlinedButtonColors(),
                    ) { Text(if (a == "nopass") "Open" else a) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    payload = app.brucia.utils.PhoneUtils.wifiQrPayload(ssid, pass, auth)
                    qr = app.brucia.utils.QrCodes.encode(payload, 512)
                },
                enabled = ssid.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Generate QR") }
            qr?.let { bmp ->
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Wi-Fi QR code",
                        modifier = Modifier.size(220.dp).background(Color.White).padding(8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                ResultRow(payload) { copy("Wi-Fi string", payload, secret = auth != "nopass") }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Small titled card wrapper used by the Utilities tools. */
@Composable
private fun UtilCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A monospaced, selectable result line with a copy button. */
@Composable
private fun ResultRow(value: String, onCopy: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = cs.onSurface,
        )
        TextButton(onClick = onCopy) { Text("Copy") }
    }
}

@Composable
private fun ToolsScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val device by vm.device.collectAsState()
    val caps = device?.caps
    val ibuttonStatus by vm.ibuttonStatus.collectAsState()
    val ibuttonBusy by vm.ibuttonBusy.collectAsState()
    val clockStatus by vm.clockStatus.collectAsState()
    val speakerStatus by vm.speakerStatus.collectAsState()
    val speakerFile by vm.speakerFilePlaying.collectAsState()
    val micStatus by vm.micStatus.collectAsState()
    val espnowStatus by vm.espnowStatus.collectAsState()
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
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary),  contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text(
            "Tools - iButton and device clock",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        if (!connected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
            Spacer(Modifier.height(8.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = cs.surface),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text("iButton", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                if (connected && caps?.ibutton != true) {
                    Text("No confirmed iButton interface", color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                if (ibuttonBusy) {
                    OutlinedButton(onClick = { vm.ibuttonStop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                } else {
                    Button(
                        enabled = connected && caps?.ibutton == true,
                        onClick = { vm.ibuttonRead() },
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
                        enabled = connected && caps?.ibutton == true && hex.isNotBlank() && !ibuttonBusy,
                        onClick = { vm.ibuttonWrite(hex.trim()) },
                    ) { Text("Write") }
                }
                if (ibuttonStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(ibuttonStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
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
                        enabled = connected,
                        onClick = { vm.clockGet() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Get time") }
                    Button(
                        enabled = connected,
                        onClick = { vm.clockSyncFromPhone() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Sync from phone") }
                }
                if (clockStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(clockStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
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
                if (caps != null && !caps.speaker) {
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
                        enabled = connected && toneFreq.toIntOrNull() != null && toneMs.toIntOrNull() != null,
                        onClick = { vm.speakerTone(toneFreq.toInt(), toneMs.toInt()) },
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
                            enabled = connected && playPath.isNotBlank(),
                            onClick = { vm.speakerPlay(playPath.trim()) },
                        ) { Text("Play file") }
                    }
                    if (speakerFile.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { vm.speakerStopFile() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Stop ($speakerFile)")
                        }
                    }
                    if (speakerStatus.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(speakerStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                    }
                }
            }
        }

        // Mic: fixed-duration recording to the device storage. Shown only with a mic.
        if (caps == null || caps.mic) {
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
                            enabled = connected && recSecs.toIntOrNull() != null,
                            onClick = { vm.micRecord(recSecs.toInt()) },
                        ) { Text("Record") }
                    }
                    if (micStatus.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(micStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
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
                    enabled = connected && espnowMsg.isNotBlank(),
                    onClick = { vm.espnowSend(espnowMsg.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Broadcast (ESP-NOW)") }
                if (espnowStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(espnowStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** BLE HID tool: the device acts as a Bluetooth keyboard/presenter the phone drives (parity). */
@Composable
private fun BleHidScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val hid by vm.bleHidStatus.collectAsState()
    val connected = status == "Connected"
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(connected) { if (connected) vm.bleHidRefresh() }
    val on = hid == "connected" || hid == "advertising"
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(cs.primary), contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("BLE Remote", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text("The device becomes a Bluetooth keyboard/presenter you drive from the phone. Start it, pair \"Brucia\" from your PC/phone's Bluetooth, then use the keys.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = cs.primaryContainer)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Device not connected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text("Status: $hid", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = cs.onSurface)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = connected && !on, onClick = { vm.bleHidBegin() }, modifier = Modifier.weight(1f)) { Text("Start") }
                    OutlinedButton(enabled = connected, onClick = { vm.bleHidRefresh() }, modifier = Modifier.weight(1f)) { Text("Refresh") }
                    OutlinedButton(enabled = connected && on, onClick = { vm.bleHidEnd() }, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
                Spacer(Modifier.height(14.dp))
                Text("Presenter", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                val keys = listOf("prev" to "< Prev", "next" to "Next >", "start" to "Start (F5)", "esc" to "Esc", "pageup" to "Pg Up", "pagedown" to "Pg Dn")
                keys.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (k, label) ->
                            OutlinedButton(enabled = connected, onClick = { vm.bleHidKey(k) }, modifier = Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text("Type text", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("text to type") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Button(enabled = connected && text.isNotBlank(), onClick = { vm.bleHidType(text) }, modifier = Modifier.fillMaxWidth()) { Text("Type") }
            }
        }
    }
}

/** Responder tool (LLMNR/NBT-NS poisoning): time-bounded, headless. Authorized use; needs Wi-Fi joined. */
@Composable
private fun ResponderScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val rStatus by vm.responderStatus.collectAsState()
    val connected = status == "Connected"
    val durations = listOf(30, 60, 120, 300)
    var secs by rememberSaveable { mutableIntStateOf(60) }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(cs.primary), contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Responder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text("Answers LLMNR/NBT-NS name queries on the LAN with the device's IP, so victims resolve names to the device (classic Responder). Join the device to the target Wi-Fi first with the Connect tool. Authorized/lab use only.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = cs.primaryContainer)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Device not connected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text("Duration", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    durations.forEach { d ->
                        val sel = secs == d
                        OutlinedButton(onClick = { secs = d }, modifier = Modifier.weight(1f),
                            colors = if (sel) BruciaButtonDefaults.outlinedButtonColors(containerColor = cs.primaryContainer) else BruciaButtonDefaults.outlinedButtonColors()) {
                            Text("${d}s", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val rRunning by vm.responderRunning.collectAsState()
                if (!rRunning) {
                    Button(enabled = connected, onClick = { vm.startResponder(secs) }, modifier = Modifier.fillMaxWidth()) { Text("Start (${secs}s)") }
                } else {
                    Button(onClick = { vm.stopResponder() }, modifier = Modifier.fillMaxWidth(), colors = BruciaButtonDefaults.buttonColors(containerColor = cs.error)) { Text("Stop") }
                }
                if (rStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(rStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** Karma tool (evil-twin probe-response lure): time-bounded, headless. Authorized use only. */
@Composable
private fun KarmaScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val kStatus by vm.karmaStatus.collectAsState()
    val connected = status == "Connected"
    val durations = listOf(30, 60, 120, 300)
    var secs by rememberSaveable { mutableIntStateOf(60) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(cs.primary), contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Karma", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text(
            "Answers nearby clients' probe requests with matching fake APs to lure them (evil twin). Authorized/lab use only - run it on networks and clients you own. The device screen shows Karma.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = cs.primaryContainer)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Device not connected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text("Duration", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    durations.forEach { d ->
                        val sel = secs == d
                        OutlinedButton(
                            onClick = { secs = d }, modifier = Modifier.weight(1f),
                            colors = if (sel) BruciaButtonDefaults.outlinedButtonColors(containerColor = cs.primaryContainer) else BruciaButtonDefaults.outlinedButtonColors(),
                        ) { Text("${d}s", style = MaterialTheme.typography.labelMedium) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val kRunning by vm.karmaRunning.collectAsState()
                if (!kRunning) {
                    Button(enabled = connected, onClick = { vm.startKarma(secs) }, modifier = Modifier.fillMaxWidth()) { Text("Start (${secs}s)") }
                } else {
                    Button(onClick = { vm.stopKarma() }, modifier = Modifier.fillMaxWidth(), colors = BruciaButtonDefaults.buttonColors(containerColor = cs.error)) { Text("Stop") }
                }
                if (kStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(kStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** Brucegotchi tool (Pwnagotchi parity): time-bounded WPA-handshake collector. Authorized use. */
@Composable
private fun BrucegotchiScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val gStatus by vm.brucegotchiStatus.collectAsState()
    val connected = status == "Connected"
    val durations = listOf(30, 60, 120, 300)
    var secs by rememberSaveable { mutableIntStateOf(60) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.ic_flame),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(cs.primary),
                contentDescription = null, modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("Brucegotchi", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text(
            "Pwnagotchi-style roam: the device hops channels, nudges clients and captures WPA handshakes to /BrucePCAP/handshakes for later cracking. The device screen shows the gotchi. Authorized/lab use only - run it on networks you own.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Device not connected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text("Duration", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    durations.forEach { d ->
                        val sel = secs == d
                        OutlinedButton(
                            onClick = { secs = d }, modifier = Modifier.weight(1f),
                            colors = if (sel) BruciaButtonDefaults.outlinedButtonColors(containerColor = cs.primaryContainer) else BruciaButtonDefaults.outlinedButtonColors(),
                        ) { Text("${d}s", style = MaterialTheme.typography.labelMedium) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val running by vm.brucegotchiRunning.collectAsState()
                if (!running) {
                    Button(
                        enabled = connected,
                        onClick = { vm.startBrucegotchi(secs) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start (${secs}s)") }
                } else {
                    Button(
                        onClick = { vm.stopBrucegotchi() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = BruciaButtonDefaults.buttonColors(containerColor = cs.error),
                    ) { Text("Stop") }
                }
                if (gStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(gStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** Wi-Fi join tool (Bruce "Connect to Wifi" parity): join the device to an AP. */
@Composable
private fun WifiJoinScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val joinStatus by vm.wifiJoinStatus.collectAsState()
    val connected = status == "Connected"
    var ssid by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    LaunchedEffect(connected) { if (connected) vm.wifiStatus() }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.ic_flame),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(cs.primary),
                contentDescription = null, modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("Wi-Fi Connect", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text(
            "Join the device to a Wi-Fi network (station mode). Useful for device-side networking; the companion link stays on USB.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Device not connected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                OutlinedTextField(
                    value = ssid, onValueChange = { ssid = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("SSID") }, singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("password (blank = open)") }, singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = connected && ssid.isNotBlank(),
                    onClick = { vm.wifiJoin(ssid.trim(), pass) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Connect") }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = connected, onClick = { vm.wifiStatus() }, modifier = Modifier.weight(1f)) { Text("Status") }
                    OutlinedButton(enabled = connected, onClick = { vm.wifiLeave() }, modifier = Modifier.weight(1f)) { Text("Disconnect") }
                }
                if (joinStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(joinStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** Wi-Fi MAC tool (Bruce "Change MAC" parity): view, set or randomize the device STA MAC. */
@Composable
private fun MacScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val wifiMac by vm.wifiMac.collectAsState()
    val connected = status == "Connected"
    var macField by rememberSaveable { mutableStateOf("") }
    // Pull the current MAC once when the device is connected.
    LaunchedEffect(connected) { if (connected) vm.getWifiMac() }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.ic_flame),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(cs.primary),
                contentDescription = null, modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("Wi-Fi MAC", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text(
            "View, set a custom, or randomize the device's Wi-Fi station MAC. The custom MAC is saved and re-applied on boot.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Device not connected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                Text("Current MAC", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(
                    wifiMac.ifBlank { "-" },
                    style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, color = cs.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(enabled = connected, onClick = { vm.getWifiMac() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh")
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = macField,
                    onValueChange = { macField = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("aa:bb:cc:dd:ee:ff") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = connected && macField.isNotBlank(),
                    onClick = { vm.setWifiMac(macField.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Set MAC") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = connected,
                    onClick = { vm.setWifiMac("random") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Randomize") }
            }
        }
    }
}

/** Custom IR remote builder: a grid of buttons taught one by one (ir_rx), tap to replay (ir tx). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun IrRemoteScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val keys by vm.remoteKeys.collectAsState()
    val capStatus by vm.remoteCapture.collectAsState()
    val connected = status == "Connected"
    var showAdd by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var deleteIdx by remember { mutableIntStateOf(-1) }

    if (showAdd) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAdd = false },
            confirmButton = {
                TextButton(enabled = connected, onClick = { vm.captureRemoteKey(label); showAdd = false; label = "" }) { Text("Capture") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
            title = { Text("Add a button") },
            text = {
                Column {
                    Text("Name the button, then point a remote at the device and press it once (10s window).",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true,
                        label = { Text("Button name (e.g. Power)") })
                }
            },
        )
    }
    if (deleteIdx >= 0) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleteIdx = -1 },
            confirmButton = { TextButton(onClick = { vm.deleteRemoteKey(deleteIdx); deleteIdx = -1 }) { Text("Delete", color = cs.error) } },
            dismissButton = { TextButton(onClick = { deleteIdx = -1 }) { Text("Cancel") } },
            title = { Text("Delete button?") },
            text = { Text(keys.getOrNull(deleteIdx)?.name ?: "") },
        )
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(cs.primary), contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("IR Remote", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text("Build your own remote: capture each button once, then tap to send. Long-press a button to delete. Saved on the phone.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = cs.primaryContainer)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Device not connected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
                }
            }
        }
        if (keys.isEmpty()) {
            Text("No buttons yet. Tap \"Add button\" below and capture one from any IR remote.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 20.dp))
        } else {
            keys.chunked(3).forEachIndexed { rowIdx, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEachIndexed { colIdx, b ->
                        val idx = rowIdx * 3 + colIdx
                        Card(
                            modifier = Modifier.weight(1f).height(64.dp).combinedClickable(
                                enabled = connected, onClick = { vm.pressRemoteKey(b) }, onLongClick = { deleteIdx = idx },
                            ),
                            colors = CardDefaults.cardColors(containerColor = cs.surface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, cs.primary.copy(alpha = 0.5f)),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(b.name, style = MaterialTheme.typography.labelLarge, color = cs.onSurface,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(4.dp))
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(enabled = connected, onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("Add button (capture)") }
        if (capStatus.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(capStatus, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

/** Standalone ESP-NOW broadcast tool (also reachable from the Audio screen). No module needed. */
@Composable
private fun EspnowScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val espnowStatus by vm.espnowStatus.collectAsState()
    val connected = status == "Connected"
    var espnowMsg by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.ic_flame),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(cs.primary),
                contentDescription = null, modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("ESP-NOW", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text(
            "Broadcast a short message over ESP-NOW (2.4GHz, no extra module). A Bruce device in 'Connect' receive mode on the same Wi-Fi channel may receive it; broadcasts are not acknowledged, so delivery is not confirmed.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Device not connected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
                }
            }
        }
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                OutlinedTextField(
                    value = espnowMsg,
                    onValueChange = { espnowMsg = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("message") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = connected && espnowMsg.isNotBlank(),
                    onClick = { vm.espnowSend(espnowMsg.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Broadcast (ESP-NOW)") }
                if (espnowStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(espnowStatus, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Write an export (app external files dir, or internal files when no external storage is mounted:
 * both FileProvider paths), keep only the newest few of its kind, and share it. A failure shows a
 * message instead of crashing the app.
 */
private fun exportAndShare(context: android.content.Context, prefix: String, ext: String, mime: String, text: () -> String) {
    try {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        shareFile(context, app.brucia.report.ExportFiles.write(dir, prefix, ext, text()), mime)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

private fun shareFile(context: android.content.Context, file: java.io.File, mime: String) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "app.brucia.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share ${file.name}"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Share failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/**
 * Open [file] in an external app via the FileProvider (content:// Uri) using ACTION_VIEW, so a
 * .pcap goes to Wireshark / a pcap viewer, an .html to a browser, etc. A Share action is offered
 * alongside in the chooser, and if nothing can view the type we fall back to a plain Share sheet.
 */
private fun openWith(context: android.content.Context, file: java.io.File, mime: String) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "app.brucia.fileprovider", file)
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(view, "Open ${file.name}")
        chooser.putExtra(android.content.Intent.EXTRA_INITIAL_INTENTS, arrayOf(share))
        context.startActivity(chooser)
    } catch (e: Exception) {
        // No app could view it (or another failure): fall back to the Share sheet.
        shareFile(context, file, mime)
    }
}

@Composable
private fun SavedRow(item: SavedItem, onReplay: (() -> Unit)?, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                if (item.subtitle.isNotEmpty()) {
                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            if (onReplay != null) {
                OutlinedButton(onClick = onReplay) { Text("Replay") }
                Spacer(Modifier.size(6.dp))
            }
            OutlinedButton(onClick = onDelete) { Text("Del") }
        }
    }
}

@Composable
private fun NfcScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val last by vm.lastNfc.collectAsState()
    val saved by vm.savedNfc.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var analyze by rememberSaveable { mutableStateOf(false) }
    var ndefUrl by rememberSaveable { mutableStateOf("") }
    val mifare by vm.mifare.collectAsState()
    val mifareUserKeys by vm.mifareUserKeys.collectAsState()
    val ndefStatus by vm.nfcNdefStatus.collectAsState()
    val emvStatus by vm.nfcEmvStatus.collectAsState()
    val uidStatus by vm.nfcUidStatus.collectAsState()
    val advStatus by vm.nfcAdvStatus.collectAsState()
    var uidHex by rememberSaveable { mutableStateOf("") }
    val keyImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.importMifareKeys(uri); Toast.makeText(context, "Importing keys...", Toast.LENGTH_SHORT).show() }
    }
    // Tag-overwriting actions ask first: "ndef" (rfid ndef writes the Brucia URL record onto the tag)
    // and "erase" (rfid erase). Saveable so a rotation keeps the open dialog.
    var confirmWrite by rememberSaveable { mutableStateOf("") }
    if (confirmWrite.isNotEmpty()) {
        val ndef = confirmWrite == "ndef"
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmWrite = "" },
            confirmButton = {
                TextButton(enabled = connected, onClick = {
                    if (ndef) vm.nfcNdef() else vm.nfcErase()
                    confirmWrite = ""
                }) { Text(if (ndef) "Write" else "Erase", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWrite = "" }) { Text("Cancel") } },
            title = { Text(if (ndef) "Write NDEF to the tag?" else "Erase the tag?") },
            text = {
                Text(
                    if (ndef) "Overwrites the NDEF data on the tag on the reader with a URL record (the Brucia project URL). " +
                        "It does not read the tag. Use Read tag to see what is on it."
                    else "Erases the data on the tag on the reader. This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text("NFC / RFID - read, clone, emulate, analyze, NDEF (needs a PN532 module)", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        DeviceCard(device)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Text(if (connected) "Reconnect over USB" else "Connect over USB")
        }
        Spacer(Modifier.height(12.dp))

        // Tag actions - primary, clearly labeled, each with a one-line description.
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Tag actions", fontWeight = FontWeight.SemiBold, color = cs.primary)
                if (!connected) {
                    Text("Connect the device to enable tag actions.", style = MaterialTheme.typography.bodySmall, color = cs.error)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.nfcRead(); Toast.makeText(context, "Place a tag on the reader...", Toast.LENGTH_SHORT).show() }
                ) { Text("Read tag") }
                Text("Scan a tag and read its UID / data.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.nfcClone(); Toast.makeText(context, "Cloning tag...", Toast.LENGTH_SHORT).show() }
                ) { Text("Clone tag") }
                Text("Copy a source tag, then write it onto a blank magic/UID-writable tag.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.nfcEmulate(); Toast.makeText(context, "Emulating tag...", Toast.LENGTH_SHORT).show() }
                ) { Text("Emulate tag") }
                Text("Emulate the last read tag with the PN532. Partial: it reproduces the UID only in part and serves NDEF only, so a reader may not accept it.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        // Key dictionary attack (MIFARE Classic) - prominent, phone hosts the dictionary.
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Key dictionary attack (MIFARE)", fontWeight = FontWeight.SemiBold, color = cs.primary)
                // LITE firmware compiles mifare_keycheck out (features.mifare_keycheck:false).
                val keycheckOmitted = device?.features?.omits(app.brucia.wardrive.DeviceFeatures.MIFARE_KEYCHECK) == true
                Text(
                    "Try the well-known MIFARE Classic default keys against each sector. The phone hosts the dictionary; the device tests the keys in batches.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = connected && !mifare.running && !keycheckOmitted,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.runMifareKeyCheck(); Toast.makeText(context, "Running key attack...", Toast.LENGTH_SHORT).show() }
                    ) { Text(if (mifare.running) "Running..." else "Run key attack") }
                    OutlinedButton(
                        enabled = !mifare.running,
                        modifier = Modifier.weight(1f),
                        onClick = { keyImportLauncher.launch(arrayOf("*/*")) }
                    ) { Text("Import keys") }
                }
                if (keycheckOmitted) {
                    Text(app.brucia.wardrive.DeviceFeatures.OMITTED_REASON, style = MaterialTheme.typography.bodySmall, color = cs.error)
                }
                if (mifare.running) {
                    TextButton(onClick = { vm.stopMifareKeyCheck() }) { Text("Stop batches") }
                }
                if (mifareUserKeys.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("${mifareUserKeys.size} imported key(s)", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.clearMifareUserKeys() }) { Text("Clear") }
                    }
                }
                if (mifare.running || mifare.totalBatches > 0 || mifare.message.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (mifare.running) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        val progress = if (mifare.totalBatches > 0) "batch ${mifare.batch}/${mifare.totalBatches}  -  " else ""
                        Text("$progress${mifare.message}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                if (mifare.found.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Recovered keys (${mifare.found.size})", fontWeight = FontWeight.SemiBold, color = Color(0xFF4CAF50), style = MaterialTheme.typography.titleSmall)
                    mifare.found.sortedWith(compareBy({ it.sector }, { it.type })).forEach { f ->
                        Text(
                            "sector ${f.sector}  key ${f.type}:  ${f.key}",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurface, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                } else if (mifare.completed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (mifare.exhaustive) "No sector keys found (all sectors tested)."
                        else "No sector keys recovered; the scan was not exhaustive.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                }
                if (mifare.uid.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Card UID: ${mifare.uid}", style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Secondary actions.
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("More", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = { vm.nfcWrite() }) { Text("Write") }
                    OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = { vm.nfcInfo() }) { Text("Info") }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = { vm.nfcSave() }) { Text("Save") }
                    OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = { confirmWrite = "erase" }) { Text("Erase...") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(enabled = connected, modifier = Modifier.fillMaxWidth(), onClick = { confirmWrite = "ndef" }) { Text("Write NDEF URL...") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(enabled = last != null, modifier = Modifier.fillMaxWidth(), onClick = { analyze = true }) { Text("Analyze security") }
            }
        }
        Spacer(Modifier.height(12.dp))

        // NDEF URL builder.
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Build NDEF URL", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = ndefUrl,
                    onValueChange = { ndefUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("URL (e.g. https://1vcian.me)") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = ndefUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val tlv = NdefBuilder.tlv(NdefBuilder.uri(ndefUrl.trim()))
                        val f = java.io.File(context.getExternalFilesDir(null), "brucia_ndef.txt")
                        f.writeText(NdefBuilder.hex(tlv))
                        shareFile(context, f, "text/plain")
                    }
                ) { Text("Build NDEF (share hex)") }
            }
        }
        Spacer(Modifier.height(12.dp))

        // --- Advanced NFC --------------------------------------------------------------
        Text("Advanced NFC", fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))

        // NDEF push (tap to send) - shares vm.nfcNdefPush with the Spam hub tile.
        NfcNdefPushCard(connected = connected, status = ndefStatus, onPush = vm::nfcNdefPush)
        Spacer(Modifier.height(12.dp))

        // Read bank card (EMV) - masked, read-only.
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Read bank card (EMV)", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Text(
                    "Reads a contactless card's masked PAN, expiry and scheme. Masked / read-only - no track data or CVV.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = connected,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.nfcEmvRead(); Toast.makeText(context, "Hold a card on the reader...", Toast.LENGTH_SHORT).show() }
                ) { Text("Read bank card") }
                if (emvStatus.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(emvStatus, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Write UID (magic card).
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Write UID (magic card)", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Text(
                    "Set the UID of a magic / UID-writable card. Authorized use only - do not clone a credential you are not permitted to.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = uidHex,
                    onValueChange = { uidHex = it.filter { c -> c.isLetterOrDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hex UID (e.g. 04A1B2C3)") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = connected && uidHex.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.nfcWriteUid(uidHex); Toast.makeText(context, "Writing UID...", Toast.LENGTH_SHORT).show() }
                ) { Text("Write UID") }
                if (uidStatus.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(uidStatus, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Amiibo + MIFARE nested / mfkey32 - simple command+status tiles. If the firmware
        // replies ok:false "not supported", that message is just shown as-is.
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Amiibo / MIFARE attacks", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Text(
                    "Amiibo emulate/dump, the MIFARE nested / mfkey32 key-recovery attacks, SRIX / ST25TB " +
                        "(ISO 14443-B) read via PN532, and 125 kHz LF read (boards with the UART LF reader). " +
                        "Nested and mfkey32 require an external PN532Killer sniffer; the onboard reader does " +
                        "the dictionary key-check.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled = false, modifier = Modifier.weight(1f), onClick = { vm.nfcAmiibo("emulate") }) { Text("Amiibo emulate") }
                    OutlinedButton(enabled = false, modifier = Modifier.weight(1f), onClick = { vm.nfcAmiibo("dump") }) { Text("Amiibo dump") }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled = false, modifier = Modifier.weight(1f), onClick = { vm.mifareNested() }) { Text("Nested") }
                    OutlinedButton(enabled = false, modifier = Modifier.weight(1f), onClick = { vm.mifareMfkey32() }) { Text("mfkey32") }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = { vm.srixRead() }) { Text("SRIX read") }
                    OutlinedButton(enabled = connected && device?.caps?.lfRfid == true, modifier = Modifier.weight(1f), onClick = { vm.rfid125Read() }) { Text("125kHz read") }
                }
                if (advStatus.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(advStatus, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Results and saved cards (inline: the whole screen is one verticalScroll container).
        last?.let { text ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
        val text = last
        if (analyze && text != null) {
            val findings = NfcSecurity.analyze(text)
            Text("Security findings (${findings.size})", color = cs.onSurfaceVariant)
            findings.forEach { fnd -> FindingRow(fnd) }
        }
        if (saved.isNotEmpty()) {
            Text("Saved cards (${saved.size})", color = cs.onSurfaceVariant)
            saved.forEach { c -> SavedRow(c, onReplay = null, onDelete = { vm.deleteSaved(c.id) }) }
        }
    }
}

// Shared NDEF-push tile. Both the NFC screen and the Spam hub render this and call the same
// vm.nfcNdefPush(type, payload) via [onPush], so the push behaviour can never drift between
// the two entry points. [status] is the shared vm.nfcNdefStatus line.
@Composable
private fun NfcNdefPushCard(
    connected: Boolean,
    status: String,
    onPush: (String, String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val types = listOf("url", "text", "contact")
    var type by rememberSaveable { mutableStateOf("url") }
    var payload by rememberSaveable { mutableStateOf("") }
    val label = when (type) {
        "url" -> "URL (e.g. https://1vcian.me)"
        "text" -> "Text"
        else -> "Contact: Name;+phone"
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("NDEF push (tap to send)", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Text(
                "Emit an NDEF record to a nearby reader or phone on tap: a URL, plain text or a contact card.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { t ->
                    val selected = t == type
                    val caption = when (t) { "url" -> "URL"; "text" -> "Text"; else -> "Contact" }
                    if (selected) {
                        Button(onClick = { type = t }, modifier = Modifier.weight(1f)) { Text(caption) }
                    } else {
                        OutlinedButton(onClick = { type = t }, modifier = Modifier.weight(1f)) { Text(caption) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = payload,
                onValueChange = { payload = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(label) },
                singleLine = type != "contact",
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = connected && payload.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = { onPush(type, payload); Toast.makeText(context, "Pushing NDEF - tap a device...", Toast.LENGTH_SHORT).show() }
            ) { Text("Send") }
            if (!connected) {
                Text("Connect the device to push NDEF.", style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(top = 4.dp))
            }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun BadUsbScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scripts = remember { DuckyLibrary.load(context) }
    val copyText = rememberTextCopier()
    var expanded by remember { mutableIntStateOf(-1) }
    val transportKind by vm.transportKind.collectAsState()
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    // BadUSB makes the device enumerate as a USB HID keyboard to a target host. Over the USB
    // companion link the device's only USB port is already the serial pipe to this phone, so it
    // cannot act as a HID at the same time. Disable it for the USB mode; it stays usable in
    // standalone mode (device alone) or over a future wireless transport.
    val usbMode = transportKind == "usb"

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
        Text("BadUSB payloads - unlimited library on the phone", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
            Spacer(Modifier.height(12.dp))
        }
        if (usbMode) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Not available over USB", fontWeight = FontWeight.Bold, color = cs.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "While the phone is connected over USB, the device's USB port is the link to this app, so it cannot also enumerate as a HID keyboard. Use BadBLE below to run a payload over BLE HID instead, or run BadUSB in standalone mode on the device.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Payload library (reference) - ${scripts.size} payloads", color = cs.onSurfaceVariant)
        } else {
            Text("${scripts.size} payloads", color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Real device-execution path: run a payload over BLE HID (works even while USB is the
            // link to this app, since it does not need the device's USB port).
            item {
                BadBleSection(vm, connected = connected)
                Spacer(Modifier.height(10.dp))
            }
            itemsIndexed(scripts) { i, sc ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { expanded = if (expanded == i) -1 else i },
                    colors = CardDefaults.cardColors(containerColor = cs.surface),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(sc.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                        Text("${sc.category} - ${sc.os}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        if (expanded == i) {
                            Spacer(Modifier.height(6.dp))
                            Text(sc.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Text(sc.script, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = {
                                copyText(sc.script)
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy script") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BleMediaCard(vm: WardriveViewModel, connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val status by vm.bleMediaStatus.collectAsState()
    var mac by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text("Media control hijack", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Text("Connect to a paired BLE audio device (speaker/earbuds you own) and inject media commands (play/pause/skip). Authorized use only.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(mac, { mac = it }, label = { Text("Target MAC (AA:BB:CC:DD:EE:FF)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(enabled = connected, modifier = Modifier.fillMaxWidth(), onClick = { vm.bleMedia(mac) }) { Text("Send media hijack") }
            if (status.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
    }
}

@Composable
private fun BleIbeaconCard(vm: WardriveViewModel, connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val active by vm.ibeaconActive.collectAsState()
    val status by vm.ibeaconStatus.collectAsState()
    var uuid by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text("iBeacon", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Text("Broadcasts an Apple iBeacon advertisement frame. Whether any app or OS reacts to it depends on the receiver's settings and has not been verified here. Leave the UUID blank for a default.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(uuid, { uuid = it }, label = { Text("Proximity UUID (optional)") },
                singleLine = true, enabled = !active, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = connected, modifier = Modifier.fillMaxWidth(),
                colors = BruciaButtonDefaults.buttonColors(containerColor = if (active) Color(0xFFD32F2F) else cs.primary),
                onClick = { if (active) vm.stopIbeacon() else vm.startIbeacon(uuid) },
            ) { Text(if (active) "Stop iBeacon" else "Broadcast iBeacon") }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BleScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val devices by vm.ble.collectAsState()
    val spamStatus by vm.bleSpamStatus.collectAsState()
    val spamActive by vm.bleSpamActive.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    // From the ViewModel (follows the SUB outcome), so a recreated screen shows the running scan.
    val scanning by vm.bleScanning.collectAsState()
    // Stop the ble.scan stream and any GATT exploration when leaving the tool so the device BLE
    // radio does not keep scanning / stays disconnected. A rotation is not leaving (LeaveEffect).
    LeaveEffect {
        if (vm.streamMayRun("ble.scan")) vm.setBleScan(false)
        vm.gattStop()
        vm.stopIbeacon(silent = true)
        // The advertising burst loop otherwise keeps broadcasting with no visible Stop.
        vm.stopBleSpam()
    }

    // Single scroll container: header, controls and the device list are all items of one
    // LazyColumn (no nested same-axis scrollables).
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
                Spacer(Modifier.weight(1f))
                StatusPill(connected)
            }
            Text("BLE scan and spam - device scans/broadcasts, phone lists and resolves vendors", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        item { DeviceCard(device) }
        item {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text(if (connected) "Reconnect over USB" else "Connect over USB")
            }
        }
        item {
            StreamToggleRow("Scan", "ble.scan stream", checked = scanning, enabled = connected,
                onCheckedChange = { vm.setBleScan(it) })
        }
        // BLE spam (Apple ProximityPair advertisements) - authorized testing only. Continuous,
        // abortable: Start launches the short-burst loop, Stop ends it (shared BlePlatformSpamCard).
        item {
            BlePlatformSpamCard(
                title = "BLE spam (Apple ProximityPair)",
                desc = "Broadcasts fake Apple ProximityPair advertisements continuously until you Stop. Whether a nearby device shows a pairing prompt depends on its OS version and settings and has not been verified. Authorized testing only.",
                target = "ios", connected = connected, spamStatus = spamStatus, active = spamActive,
                onStart = vm::startBleSpam, onStop = { vm.stopBleSpam() },
            )
        }
        // BLE Fast Pair spam (Android) - authorized testing only.
        item {
            BlePlatformSpamCard(
                title = "Fast Pair spam (Android)",
                desc = "Broadcasts fake Google Fast Pair advertisements continuously until you Stop. Whether a nearby Android device shows a pairing popup depends on its OS version and settings and has not been verified. Authorized testing only.",
                target = "android", connected = connected, spamStatus = spamStatus, active = spamActive,
                onStart = vm::startBleSpam, onStop = { vm.stopBleSpam() },
            )
        }
        // BadBLE: real device-execution path for Ducky payloads over BLE HID.
        item { BadBleSection(vm, connected = connected) }
        // GATT explorer: shown once a device has been picked for exploration.
        item { GattExplorerCard(vm) }
        item { BleIbeaconCard(vm, connected) }
        item { BleMediaCard(vm, connected) }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${devices.size} devices", color = cs.onSurfaceVariant)
                OutlinedButton(enabled = devices.isNotEmpty(), onClick = { vm.clearBle() }) { Text("Clear") }
            }
        }
        // LITE firmware compiles the GATT explorer out (features.ble_gatt:false): no Explore button.
        val gattOmitted = device?.features?.omits(app.brucia.wardrive.DeviceFeatures.BLE_GATT) == true
        if (gattOmitted) {
            item {
                Text("GATT explorer: " + app.brucia.wardrive.DeviceFeatures.OMITTED_REASON,
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        items(devices) { d ->
            BleCard(d, connected = connected, onExplore = if (gattOmitted) null else ({ vm.gattConnect(d.mac) }))
        }
    }
}

@Composable
private fun BleCard(d: BleDevice, connected: Boolean = false, onExplore: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val vendor = OuiResolver.vendor(d.mac)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(d.name.ifEmpty { "(no name)" }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        d.mac + (if (vendor != null) "  $vendor" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                RssiPill(d.rssi)
            }
            if (onExplore != null) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(enabled = connected, onClick = onExplore, modifier = Modifier.fillMaxWidth()) {
                    Text("Explore (GATT)")
                }
            }
        }
    }
}

// GATT explorer: once a device is picked (vm.gattConnect), shows the discovered service ->
// characteristic tree streamed via EVENT ble.gatt. Each readable characteristic gets a Read
// button (ble_gatt_read) whose value is shown inline. Hidden until a device is being explored.
@Composable
private fun GattExplorerCard(vm: WardriveViewModel) {
    val cs = MaterialTheme.colorScheme
    val mac by vm.gattMac.collectAsState()
    val status by vm.gattStatus.collectAsState()
    val connecting by vm.gattConnecting.collectAsState()
    val services by vm.gattServices.collectAsState()
    val reads by vm.gattReads.collectAsState()
    val m = mac ?: return
    // Collapsed service UUIDs (everything expanded by default).
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("GATT explorer", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text(m, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
                OutlinedButton(onClick = { vm.gattStop() }) { Text("Close") }
            }
            if (connecting) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting...", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (services.isEmpty() && !connecting) {
                Spacer(Modifier.height(6.dp))
                Text("No services discovered yet.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            for (svc in services) {
                val open = !collapsed.contains(svc.uuid)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        collapsed = if (open) collapsed + svc.uuid else collapsed - svc.uuid
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (open) "-" else "+", fontWeight = FontWeight.Bold, color = cs.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Service ${svc.uuid}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (open) {
                    for (ch in svc.characteristics) {
                        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp)) {
                            Text(ch.uuid, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text(
                                "props: " + ch.props.ifBlank { "-" },
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                            if (ch.readable) {
                                OutlinedButton(onClick = { vm.gattRead(m, ch.uuid) }) { Text("Read") }
                            }
                            val v = reads[ch.uuid]
                            if (v != null) {
                                Text(
                                    "value: $v",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = cs.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// BadBLE: run a DuckyScript over BLE HID (vm.bleBadble). Reuses the bundled DuckyLibrary as a
// payload source plus a free-text editor. Shared by the BLE and BadUSB screens so the two never
// drift. Authorized use only.
@Composable
private fun BadBleSection(vm: WardriveViewModel, connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scripts = remember { DuckyLibrary.load(context) }
    val status by vm.badbleStatus.collectAsState()
    var script by rememberSaveable { mutableStateOf("") }
    var pickerOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("BadBLE (Ducky over BLE)", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Text(
                "Runs a DuckyScript keystroke-injection payload over BLE HID to a target host. Authorized use only - only run against devices you own or are permitted to test.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            if (!connected) {
                Text("Connect the device to run BadBLE.", style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { pickerOpen = !pickerOpen }) {
                Text(if (pickerOpen) "Hide library" else "Load from library (${scripts.size})")
            }
            if (pickerOpen) {
                Spacer(Modifier.height(6.dp))
                for (sc in scripts) {
                    Text(
                        "${sc.name}  (${sc.category} - ${sc.os})",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clickable {
                            script = sc.script; pickerOpen = false
                        }.padding(vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = script,
                onValueChange = { script = it },
                label = { Text("DuckyScript") },
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = connected && script.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.bleBadble(script); Toast.makeText(context, "Running over BLE...", Toast.LENGTH_SHORT).show() },
            ) { Text("Run over BLE") }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

// --- Spam hub -------------------------------------------------------------------------------
// One place that gathers every spam / popup / flood attack, organised by the TARGET platform;
// the hub picks the transport. These are a SECOND entry point that calls the SAME ViewModel
// methods as the native BLE and Wi-Fi screens (startBleSpam / startBeaconSpam), so the two
// never drift. Not capability-gated away (BLE / Wi-Fi are always offered); individual BLE
// tiles surface the firmware ACK error if a target is unsupported.
@Composable
private fun SpamScreen(vm: WardriveViewModel, onConnect: () -> Unit, filter: String? = null) {
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val spamStatus by vm.bleSpamStatus.collectAsState()
    val spamActive by vm.bleSpamActive.collectAsState()
    val ndefStatus by vm.nfcNdefStatus.collectAsState()

    // filter: null = full hub (all transports), or "ble" / "wifi" / "nfc" for a focused screen.
    val showBle = filter == null || filter == "ble"
    val showWifi = filter == null || filter == "wifi"
    val showNfc = filter == null || filter == "nfc"
    val heading = when (filter) {
        "ble" -> "Bluetooth (BLE) spam"
        "wifi" -> "Wi-Fi spam"
        "nfc" -> "NFC spam"
        else -> "Spam hub - grouped by transport"
    }
    // Leaving the screen ends the BLE advertising burst loop (like the IR/Sub-GHz/NRF loops), so it
    // never keeps broadcasting with no visible Stop. A rotation is not leaving (LeaveEffect).
    if (showBle) LeaveEffect { vm.stopBleSpam() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
                Spacer(Modifier.weight(1f))
                StatusPill(connected)
            }
            Text("$heading. Authorized testing only.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        item {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text(if (connected) "Reconnect over USB" else "Connect over USB")
            }
        }

        // --- Bluetooth (BLE) spam ------------------------------------------------------------
        if (showBle) {
            if (filter == null) item { SpamSectionHeader("Bluetooth (BLE)") }
            item {
                BlePlatformSpamCard(
                    title = "Apple / iOS / Mac",
                    desc = "BLE Continuity / ProximityPair advertisements (fake AirPods, AppleTV, Setup-new-device). Receiver reaction depends on OS version and settings; not verified.",
                    target = "ios", connected = connected, spamStatus = spamStatus, active = spamActive, onStart = vm::startBleSpam, onStop = { vm.stopBleSpam() },
                )
            }
            item {
                BlePlatformSpamCard(
                    title = "Android",
                    desc = "BLE Fast Pair advertisements. Receiver reaction depends on OS version and settings; not verified.",
                    target = "android", connected = connected, spamStatus = spamStatus, active = spamActive, onStart = vm::startBleSpam, onStop = { vm.stopBleSpam() },
                )
            }
            item {
                BlePlatformSpamCard(
                    title = "Windows / PC",
                    desc = "BLE Swift Pair advertisements. Receiver reaction depends on OS version and settings; not verified.",
                    target = "windows", connected = connected, spamStatus = spamStatus, active = spamActive, onStart = vm::startBleSpam, onStop = { vm.stopBleSpam() },
                )
            }
            item {
                BlePlatformSpamCard(
                    title = "Samsung",
                    desc = "BLE EasySetup / Buds advertisements. Receiver reaction depends on OS version and settings; not verified.",
                    target = "samsung", connected = connected, spamStatus = spamStatus, active = spamActive, onStart = vm::startBleSpam, onStop = { vm.stopBleSpam() },
                )
            }
            item {
                BlePlatformSpamCard(
                    title = "All BLE platforms",
                    desc = "Fires every BLE target in one run (iOS, Android, Windows, Samsung).",
                    target = "all", connected = connected, spamStatus = spamStatus, active = spamActive, onStart = vm::startBleSpam, onStop = { vm.stopBleSpam() },
                )
            }
        }

        // --- Wi-Fi spam ----------------------------------------------------------------------
        if (showWifi) {
            if (filter == null) item { SpamSectionHeader("Wi-Fi") }
            // The exact same beacon-spam flow (SSID list + secs) as the Wardrive tab, via BeaconSpamCard.
            item { BeaconSpamCard(vm, connected = connected) }
        }

        // --- NFC spam ------------------------------------------------------------------------
        if (showNfc) {
            if (filter == null) item { SpamSectionHeader("NFC") }
            // Real NDEF push, sharing the exact same vm.nfcNdefPush as the NFC screen.
            item { NfcNdefPushCard(connected = connected, status = ndefStatus, onPush = vm::nfcNdefPush) }
        }
    }
}

/** Section header inside the Spam hub, so the list is grouped by transport (BLE / Wi-Fi / NFC). */
@Composable
private fun SpamSectionHeader(title: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = cs.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

// A single BLE pairing-popup target tile. The button is a Start/Stop toggle driven by the shared
// [active] flag; Start launches the abortable spam loop ([onStart]), Stop cancels it ([onStop]).
// The "Copies / advertisements" field sets how many advertisements are blasted per burst (1..100).
// [spamStatus] is the shared status line from the ViewModel. Reused by the Spam hub and the native
// BLE screen so the behaviour never drifts.
@Composable
private fun BlePlatformSpamCard(
    title: String,
    desc: String,
    target: String,
    connected: Boolean,
    spamStatus: String,
    active: Boolean,
    onStart: (String, Int) -> Unit,
    onStop: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var copiesText by rememberSaveable { mutableStateOf(DEFAULT_SPAM_COPIES.toString()) }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = cs.primary)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = copiesText,
                onValueChange = { copiesText = it.filter { c -> c.isDigit() }.take(3) },
                singleLine = true,
                label = { Text("Copies / advertisements (1-100)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (active) {
                        onStop()
                    } else {
                        val copies = (copiesText.toIntOrNull() ?: DEFAULT_SPAM_COPIES).coerceIn(1, 100)
                        onStart(target, copies)
                        Toast.makeText(context, "$title spam: $copies copies (Stop to end)", Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text(if (active) "Stop" else "Start") }
            if (!connected) {
                Text("Connect the device to run this attack.", style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(top = 4.dp))
            }
            if (spamStatus.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(spamStatus, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Authorized / lawful use only. Broadcasting pairing popups may be illegal without permission.",
                style = MaterialTheme.typography.bodySmall, color = cs.error,
            )
        }
    }
}

@Composable
private fun Nrf24Screen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val channels by vm.nrf24Channels.collectAsState()
    val devices by vm.nrf24Devices.collectAsState()
    val scanning by vm.nrf24Scanning.collectAsState()
    val sniffing by vm.nrf24Sniffing.collectAsState()
    val nrfStatus by vm.nrf24Status.collectAsState()
    val jamming by vm.nrf24Jamming.collectAsState()
    val jammerStatus by vm.nrf24JammerStatus.collectAsState()
    val mjRunning by vm.nrf24MjRunning.collectAsState()
    val mjStatus by vm.nrf24MjStatus.collectAsState()
    val mjDevices by vm.nrf24MjDevices.collectAsState()
    var jammerSecsText by rememberSaveable { mutableStateOf("10") }
    var mjSecsText by rememberSaveable { mutableStateOf("10") }
    var mjText by rememberSaveable { mutableStateOf("") }
    // Clear the local run flags when leaving the tab.
    LeaveEffect { vm.stopNrf24Scan(); vm.stopNrf24Sniff(); vm.stopNrf24Jammer(); vm.stopNrf24MouseJack() }

    // Unknown caps (disconnected) -> show controls normally; only a connected device that
    // reports nrf24 = false hides them.
    val hasModule = device?.caps?.nrf24 != false

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
                Spacer(Modifier.weight(1f))
                StatusPill(connected)
            }
            Text("2.4GHz / NRF24L01 - scanner, ESB sniffer and MouseJack", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        item { DeviceCard(device) }
        if (!hasModule) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No NRF24L01 module", fontWeight = FontWeight.Bold, color = cs.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Plug in an NRF24L01 module to use 2.4GHz features.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
            return@LazyColumn
        }
        item {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text(if (connected) "Reconnect over USB" else "Connect over USB")
            }
        }
        if (nrfStatus.isNotEmpty()) {
            item { Text(nrfStatus, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
        // --- 2.4GHz scanner ---
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("2.4GHz scanner", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text("Per-channel RF activity via nrf24_scan.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = connected || scanning,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { if (scanning) vm.stopNrf24Scan() else vm.startNrf24Scan() },
                    ) { Text(if (scanning) "Stop" else "Start scan") }
                    Spacer(Modifier.height(8.dp))
                    if (channels.isEmpty()) {
                        Text(
                            if (scanning) "Scanning..." else "No channel activity yet.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    } else {
                        val maxLevel = (channels.maxOfOrNull { it.level } ?: 1).coerceAtLeast(1)
                        channels.forEach { ch ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                Text("ch ${ch.ch}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.width(44.dp))
                                Box(
                                    Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceVariant),
                                ) {
                                    Box(
                                        Modifier.fillMaxWidth(ch.level.toFloat() / maxLevel)
                                            .height(10.dp).clip(RoundedCornerShape(4.dp)).background(cs.primary),
                                    )
                                }
                                Spacer(Modifier.size(6.dp))
                                Text("${ch.level}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.width(32.dp))
                            }
                        }
                    }
                }
            }
        }
        // --- ESB sniffer ---
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("ESB sniffer", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text("Discover Enhanced ShockBurst devices via nrf24_sniff.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = connected || sniffing,
                            modifier = Modifier.weight(1f),
                            onClick = { if (sniffing) vm.stopNrf24Sniff() else vm.startNrf24Sniff() },
                        ) { Text(if (sniffing) "Stop" else "Start sniff") }
                        OutlinedButton(
                            enabled = devices.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            onClick = { vm.clearNrf24Devices() },
                        ) { Text("Clear") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("${devices.size} devices", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        items(devices) { dev -> Nrf24DeviceCard(dev) }
        if (devices.isEmpty()) {
            item {
                Text(
                    if (sniffing) "Sniffing... bring ESB devices nearby." else "No devices yet. Tap Start sniff.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
        }
        // --- 2.4GHz jammer (authorized) ---
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("2.4GHz jammer (authorized)", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text(
                        "Floods the 2.4GHz band for the chosen duration via nrf24_jammer. Transmitting noise is illegal without authorization - use only on equipment and spectrum you are allowed to test.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                    if (!connected) {
                        Text("Connect the device to enable the jammer.", style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = jammerSecsText,
                        onValueChange = { jammerSecsText = it.filter { c -> c.isDigit() }.take(4) },
                        singleLine = true,
                        enabled = connected && !jamming,
                        label = { Text("Duration (seconds)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = connected || jamming,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (jamming) {
                                vm.stopNrf24Jammer()
                                Toast.makeText(context, "Jammer stopped", Toast.LENGTH_SHORT).show()
                            } else {
                                vm.startNrf24Jammer()
                                Toast.makeText(context, "Jamming...", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) { Text(if (jamming) "Stop" else "Start") }
                    if (jammerStatus.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(jammerStatus, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        // --- MouseJack ---
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("MouseJack", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text(
                        "Scans for vulnerable 2.4GHz wireless mice/keyboards (nrf24_mousejack) and injects optional keystrokes. Discovered devices appear below. Authorized testing only.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                    if (!connected) {
                        Text("Connect the device to enable MouseJack.", style = MaterialTheme.typography.bodySmall, color = cs.error, modifier = Modifier.padding(top = 2.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = mjSecsText,
                        onValueChange = { mjSecsText = it.filter { c -> c.isDigit() }.take(4) },
                        singleLine = true,
                        enabled = connected && !mjRunning,
                        label = { Text("Duration (seconds)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = mjText,
                        onValueChange = { mjText = it },
                        singleLine = true,
                        enabled = connected && !mjRunning,
                        label = { Text("Inject text (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = connected || mjRunning,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (mjRunning) {
                                vm.stopNrf24MouseJack()
                                Toast.makeText(context, "MouseJack stopped", Toast.LENGTH_SHORT).show()
                            } else {
                                vm.startNrf24MouseJack(mjText)
                                Toast.makeText(context, "MouseJack...", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) { Text(if (mjRunning) "Stop" else "Scan + inject") }
                    if (mjStatus.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(mjStatus, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("${mjDevices.size} mousejackable devices", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        items(mjDevices) { dev -> Nrf24MjDeviceCard(dev) }
        if (mjDevices.isEmpty()) {
            item {
                Text(
                    if (mjRunning) "Scanning for mousejackable devices..." else "No mousejackable devices discovered yet.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Nrf24MjDeviceCard(d: Nrf24MjDevice) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(d.addr, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                Row {
                    Text("ch ${d.ch}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    if (d.type.isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        Text(d.type, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Nrf24DeviceCard(d: Nrf24Device) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(d.addr, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                Row {
                    Text("ch ${d.ch}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    Text("x${d.count}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
            RssiPill(d.rssi)
        }
    }
}

@Composable
private fun LoRaScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val packets by vm.loraPackets.collectAsState()
    val loraStatus by vm.loraStatus.collectAsState()
    val receiving by vm.loraReceiving.collectAsState()
    var freqText by rememberSaveable { mutableStateOf("868.0") }
    var bwText by rememberSaveable { mutableStateOf("") }
    var sfText by rememberSaveable { mutableStateOf("") }
    var crText by rememberSaveable { mutableStateOf("") }
    var sendText by rememberSaveable { mutableStateOf("") }
    var recvSecsText by rememberSaveable { mutableStateOf("20") }
    LeaveEffect { vm.stopLoraRecv() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
                Spacer(Modifier.weight(1f))
                StatusPill(connected)
            }
            Text("LoRa - configure the radio, send and receive packets", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        item { DeviceCard(device) }
        item {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text(if (connected) "Reconnect over USB" else "Connect over USB")
            }
        }
        if (loraStatus.isNotEmpty()) {
            item { Text(loraStatus, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
        // --- Radio configuration ---
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Configuration", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text("Frequency in MHz, with optional bandwidth, spreading factor and coding rate.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = freqText,
                        onValueChange = { freqText = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                        singleLine = true,
                        enabled = connected,
                        label = { Text("Frequency (MHz)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = bwText,
                            onValueChange = { bwText = it.take(10) },
                            singleLine = true, enabled = connected,
                            label = { Text("BW") },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = sfText,
                            onValueChange = { sfText = it.take(4) },
                            singleLine = true, enabled = connected,
                            label = { Text("SF") },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = crText,
                            onValueChange = { crText = it.take(4) },
                            singleLine = true, enabled = connected,
                            label = { Text("CR") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = connected && freqText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { vm.loraConfig(freqText, bwText, sfText, crText) },
                    ) { Text("Apply config") }
                }
            }
        }
        // --- Send ---
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Send", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sendText,
                        onValueChange = { sendText = it },
                        singleLine = true, enabled = connected,
                        label = { Text("Message") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = connected && sendText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { vm.loraSend(sendText) },
                    ) { Text("Send") }
                }
            }
        }
        // --- Receive ---
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Receive", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text("Listen for 1–120 seconds. Stop cancels the app wait; the device finishes its listen window.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = recvSecsText,
                        onValueChange = { recvSecsText = it.filter { c -> c.isDigit() }.take(4) },
                        singleLine = true,
                        enabled = connected && !receiving,
                        label = { Text("Duration (seconds)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    val recvSecs = recvSecsText.toIntOrNull()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = receiving || (connected && recvSecs != null && recvSecs in 1..120),
                            modifier = Modifier.weight(1f),
                            onClick = { if (receiving) vm.stopLoraRecv() else vm.loraRecv(recvSecs ?: 20) },
                        ) { Text(if (receiving) "Stop waiting" else "Start receive") }
                        OutlinedButton(
                            enabled = packets.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            onClick = { vm.clearLoraPackets() },
                        ) { Text("Clear") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("${packets.size} packets", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
        items(packets.reversed()) { p -> LoraPacketCard(p) }
        if (packets.isEmpty()) {
            item {
                Text(
                    if (receiving) "Listening..." else "No packets received yet.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoraPacketCard(p: LoraPacket) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(p.data.ifBlank { "(empty)" }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
            Row {
                Text("rssi ${p.rssi}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                Text("snr ${p.snr}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EthernetScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val ethStatus by vm.ethStatus.collectAsState()
    val ethMessage by vm.ethMessage.collectAsState()
    var configText by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
                Spacer(Modifier.weight(1f))
                StatusPill(connected)
            }
            Text("Ethernet - wired network status and configuration", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        item { DeviceCard(device) }
        item {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text(if (connected) "Reconnect over USB" else "Connect over USB")
            }
        }
        if (ethMessage.isNotEmpty()) {
            item { Text(ethMessage, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
        // --- Status ---
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Status", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = connected,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { vm.ethStatus() },
                    ) { Text("Refresh status") }
                    Spacer(Modifier.height(8.dp))
                    val s = ethStatus
                    if (s == null) {
                        Text("Tap Refresh status to query the link.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    } else {
                        Text("Link: ${if (s.link) "up" else "down"}", color = cs.onSurface, style = MaterialTheme.typography.bodyMedium)
                        Text("IP: ${s.ip.ifBlank { "-" }}", color = cs.onSurface, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        if (s.detail.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(s.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        // --- Configuration ---
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Configuration", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text("eth_config arguments: dhcp, or static <ip> <gw> <mask> [dns]; blank means dhcp. The device brings the W5500 up with them, reports the link below and stops it again: the settings are a link test, not kept afterwards.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = configText,
                        onValueChange = { configText = it },
                        singleLine = true, enabled = connected,
                        label = { Text("eth_config arguments") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = connected,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { vm.ethConfig(configText) },
                    ) { Text("Apply config") }
                }
            }
        }
        // --- Attacks (authorized use only) ---
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Attacks", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Text(
                        "Wired-LAN floods against a switch you are authorized to test. DHCP starvation " +
                            "exhausts the DHCP pool; MAC flooding fills the switch CAM table. Each runs " +
                            "for a bounded 10s.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = { vm.ethDhcpStarve() }) { Text("DHCP starve") }
                        OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = { vm.ethMacFlood() }) { Text("MAC flood") }
                    }
                }
            }
        }
    }
}

// Phone-side automation: build, save and run macros (ordered sequences of device commands with a
// pause after each). The phone does the sequencing the device's tiny interpreter cannot.
@Composable
private fun MacroScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val macros by vm.macros.collectAsState()
    val macroStatus by vm.macroStatus.collectAsState()
    val running by vm.macroRunning.collectAsState()

    var name by rememberSaveable { mutableStateOf("") }
    var cmd by rememberSaveable { mutableStateOf("") }
    var delayMs by rememberSaveable { mutableStateOf("500") }
    var steps by rememberRetained { mutableStateOf(listOf<app.brucia.automation.MacroStep>()) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f)); StatusPill(connected)
        }
        Text("Macros - chain device commands into one-tap automations", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Text(if (connected) "Reconnect over USB" else "Connect over USB")
        }
        if (macroStatus.isNotEmpty()) {
            Text(macroStatus, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, fontFamily = FontFamily.Monospace)
        }
        if (running) {
            Button(onClick = { vm.stopMacro() }, modifier = Modifier.fillMaxWidth(),
                colors = BruciaButtonDefaults.buttonColors(containerColor = cs.error)) { Text("Stop macro") }
            Text("Stops the sequence; a command already sent may continue on the device.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }

        // --- Builder ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("New macro", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Macro name") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = cmd, onValueChange = { cmd = it }, singleLine = true,
                        label = { Text("Command (e.g. uptime)") }, modifier = Modifier.weight(2f))
                    OutlinedTextField(value = delayMs, onValueChange = { delayMs = it.filter { c -> c.isDigit() } }, singleLine = true,
                        label = { Text("Wait ms (0–60000)") }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(enabled = cmd.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                    steps = steps + app.brucia.automation.MacroStep(cmd.trim(), (delayMs.toIntOrNull() ?: 500).coerceIn(0, 60_000))
                    cmd = ""
                }) { Text("Add step") }
                if (steps.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    steps.forEachIndexed { i, s ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}. ${s.cmd}  (${s.delayMs}ms)", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurface)
                            TextButton(enabled = i > 0, onClick = { steps = app.brucia.automation.Macro.moveStep(steps, i, -1) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) { Text("Up") }
                            OutlinedButton(onClick = { steps = steps.filterIndexed { j, _ -> j != i } },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text("x") }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    val saveName = name.trim().ifBlank { app.brucia.automation.Macro.defaultName(macros) }
                    Button(enabled = steps.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                        vm.saveMacro(app.brucia.automation.Macro(saveName, steps))
                        name = ""; steps = emptyList()
                    }) { Text(if (macros.any { it.name == saveName }) "Save (replaces \"$saveName\")" else "Save macro") }
                }
            }
        }

        // --- Saved macros ---
        if (macros.isNotEmpty()) {
            Text("Saved macros", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            macros.forEachIndexed { i, m ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(m.name, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        Text("${m.steps.size} step${if (m.steps.size == 1) "" else "s"}", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = connected && !running, modifier = Modifier.weight(1f), onClick = { vm.runMacro(m) }) { Text("Run") }
                            // Load it into the builder above; saving under the same name replaces it.
                            OutlinedButton(onClick = { name = m.name; steps = m.steps }) { Text("Edit") }
                            OutlinedButton(onClick = { vm.deleteMacro(i) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}

// Audio sources for the FM transmitter. DEVICE_MIC uses the firmware mic (fm_audio_begin
// <freq> mic); the others stream 16 kHz mono PCM from the phone as fm_audio_data chunks.
private enum class FmSource(val label: String) {
    FILE("Audio file"), TONE("Tone/test"), PHONE_MIC("Phone mic"), DEVICE_MIC("Device mic")
}

@Composable
private fun FmScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    val streaming by vm.fmStreaming.collectAsState()
    val fmStatus by vm.fmStatus.collectAsState()
    val level by vm.fmLevel.collectAsState()
    val elapsedMs by vm.fmElapsedMs.collectAsState()

    var freqText by rememberSaveable { mutableStateOf("90000") }
    var toneText by rememberSaveable { mutableStateOf("440") }
    var source by rememberSaveable { mutableStateOf(FmSource.TONE) }

    // Stop any transmission when the user leaves the tab.
    LeaveEffect { vm.fmStop() }

    val freqKHz = freqText.toIntOrNull()
    val freqValid = freqKHz != null && freqKHz in 87500..108000

    fun startPhoneMic() {
        val f = freqKHz ?: return
        vm.fmStartPhoneMic(f)
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startPhoneMic()
        else Toast.makeText(context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val f = freqKHz
        if (uri != null && f != null) vm.fmStartFile(uri, f)
    }

    fun start() {
        val f = freqKHz ?: return
        when (source) {
            FmSource.FILE -> fileLauncher.launch(arrayOf("audio/*"))
            FmSource.TONE -> vm.fmStartTone(f, (toneText.toDoubleOrNull() ?: 440.0))
            FmSource.PHONE_MIC -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) startPhoneMic() else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            FmSource.DEVICE_MIC -> vm.fmStartDeviceMic(f)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary),  contentDescription = null, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(8.dp))
                Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
                Spacer(Modifier.weight(1f))
                StatusPill(connected)
            }
            Text("Si4713 FM transmitter (optional module). The digital audio routing to the Si4713 has not been verified on hardware.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        item { DeviceCard(device) }
        item {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                Text(if (connected) "Reconnect over USB" else "Connect over USB")
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Transmit frequency", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = freqText,
                        onValueChange = { freqText = it.filter { c -> c.isDigit() } },
                        singleLine = true,
                        enabled = !streaming,
                        isError = freqText.isNotEmpty() && !freqValid,
                        label = { Text("Frequency (kHz, 87500-108000)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (freqText.isNotEmpty() && !freqValid) {
                        Text("Enter a value between 87500 and 108000 kHz.", style = MaterialTheme.typography.bodySmall, color = cs.error)
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Audio source", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Spacer(Modifier.height(8.dp))
                    FmSource.values().forEach { s ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !streaming) { source = s }
                                .padding(vertical = 4.dp),
                        ) {
                            Box(
                                Modifier.size(14.dp).clip(CircleShape)
                                    .background(if (source == s) cs.primary else cs.surfaceVariant)
                            )
                            Spacer(Modifier.size(10.dp))
                            Column {
                                Text(s.label, color = cs.onSurface)
                                val hint = when (s) {
                                    FmSource.FILE -> "Pick a file; decoded to 16 kHz mono and streamed."
                                    FmSource.TONE -> "Stream a generated sine tone."
                                    FmSource.PHONE_MIC -> "Capture this phone's mic and stream it."
                                    FmSource.DEVICE_MIC -> "Use the device's own mic (no phone audio)."
                                }
                                Text(hint, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                    if (source == FmSource.TONE) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = toneText,
                            onValueChange = { toneText = it.filter { c -> c.isDigit() } },
                            singleLine = true,
                            enabled = !streaming,
                            label = { Text("Tone frequency (Hz)") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Capturing other apps' audio (AudioPlaybackCapture / MediaProjection) is not wired up.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Button(
                enabled = (connected && freqValid) || streaming,
                modifier = Modifier.fillMaxWidth(),
                onClick = { if (streaming) vm.fmStop() else start() },
            ) { Text(if (streaming) "Stop" else "Start transmitting") }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Status", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(fmStatus.ifBlank { "Idle." }, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    if (streaming) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Level", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.width(44.dp))
                            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(4.dp)).background(cs.primary))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        val secs = elapsedMs / 1000
                        Text("Elapsed ${secs / 60}:${(secs % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Text(
                "Transmitting on FM broadcast frequencies is regulated and illegal in most regions without a licence. Authorized / lawful use only.",
                style = MaterialTheme.typography.bodySmall, color = cs.error,
            )
        }
    }
}

/**
 * Session report tool: the phone aggregates everything captured this session into a Markdown
 * report (shareable as .md or .txt) and a heuristic insights summary. Everything here is pure
 * on-phone analysis of data the device streamed - "the phone is the brain".
 */
@Composable
private fun ReportScreen(vm: WardriveViewModel) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    // Collect the streams so the preview and insights recompute as capture data arrives.
    val aps by vm.aps.collectAsState()
    val stations by vm.stations.collectAsState()
    val probes by vm.probes.collectAsState()
    val ble by vm.ble.collectAsState()
    val subghz by vm.subghz.collectAsState()
    val nrf24 by vm.nrf24Devices.collectAsState()
    val nrf24Mj by vm.nrf24MjDevices.collectAsState()
    val monitor by vm.monitorFrames.collectAsState()
    val mitm by app.brucia.mitm.MitmLog.txns.collectAsState()
    val handshakeInfo by vm.handshakeInfo.collectAsState()
    val crack by vm.crack.collectAsState()

    // Key the (re)build on a cheap signature of the data so scrolling does not rebuild the report.
    val sig = listOf(
        aps.size, stations.size, probes.size, ble.size, subghz.size, nrf24.size, nrf24Mj.size,
        monitor.size, mitm.size, handshakeInfo?.length ?: 0, crack.result?.length ?: 0,
    ).joinToString(",")
    val report = remember(sig) { vm.buildFullReport() }
    val insights = remember(sig) { vm.buildInsights() }
    val hasData = aps.isNotEmpty() || stations.isNotEmpty() || probes.isNotEmpty() ||
        ble.isNotEmpty() || subghz.isNotEmpty() || nrf24.isNotEmpty() || nrf24Mj.isNotEmpty() ||
        monitor.isNotEmpty() || mitm.isNotEmpty() || handshakeInfo != null || crack.result != null

    // One LazyColumn so the whole screen scrolls (controls = header item, report lines = items).
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        item {
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary),  contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
        }
        Text("Session report - the phone aggregates everything captured", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = hasData,
                modifier = Modifier.weight(1f),
                onClick = { exportAndShare(context, "brucia_report_", "md", "text/markdown") { report } }
            ) { Text("Generate report (.md)") }
            OutlinedButton(
                enabled = hasData,
                modifier = Modifier.weight(1f),
                onClick = { exportAndShare(context, "brucia_report_", "txt", "text/plain") { report } }
            ) { Text("Export .txt") }
        }
        Spacer(Modifier.height(12.dp))

        // Heuristic insights (clearly labelled - not ML).
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Insights (heuristic)", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Text(
                    "Rule-of-thumb analysis of the captured data - not machine learning. Hints only.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (insights.isEmpty()) {
                    Text("Nothing captured yet. Run a scan to populate insights.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                } else {
                    for (i in insights) {
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text("- ", color = cs.primary, style = MaterialTheme.typography.bodySmall)
                            Text(i, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Text("Report preview", fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        }
        }
        items(report.split("\n")) { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
        }
    }
}

private val THEME_SWATCHES = listOf(
    0xFF6D1A to "Ember", 0xFF9800 to "Orange", 0xFFC107 to "Amber", 0xFFEB3B to "Yellow",
    0xCDDC39 to "Lime", 0x4CAF50 to "Green", 0x009688 to "Teal", 0x00BCD4 to "Cyan",
    0x03A9F4 to "LightBlue", 0x2196F3 to "Blue", 0x3F51B5 to "Indigo", 0x673AB7 to "DeepPurple",
    0x9C27B0 to "Purple", 0xE91E63 to "Magenta", 0xFF4081 to "Pink", 0xF44336 to "Red",
    0x795548 to "Brown", 0x607D8B to "Slate", 0xBDBDBD to "Grey", 0xFFFFFF to "White",
)

// Onboard LED animations (match src/core/led_control.h effect ids).
private val LED_EFFECTS = listOf(
    "Solid", "Breathe", "Color cycle", "Color wheel", "Chase", "Chase tail",
    "Rainbow chase", "Rainbow breathe", "Disco", "Fire",
)

/** Standalone Theme tool: app + device theme colors, LED color/brightness/animation. */
@Composable
private fun ThemeScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(R.drawable.ic_flame),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(cs.primary),
                contentDescription = null, modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("Theme & LED", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Spacer(Modifier.height(12.dp))
        if (!connected) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = cs.primaryContainer),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Device not connected", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = cs.onPrimaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
                }
            }
        }
        ThemeLedCard(vm, connected)
    }
}

@Composable
private fun ThemeLedCard(vm: WardriveViewModel, connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val status by vm.themeStatus.collectAsState()
    val device by vm.device.collectAsState()
    val devLedBright = device?.ledBright ?: -1
    val devLedEffect = device?.ledEffect ?: 0
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text("Theme & LED", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Text("Set the device UI color - the app matches it (the device always wins). Also sets the onboard LED.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text("App + device theme", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            THEME_SWATCHES.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (rgb, _) ->
                        Box(
                            Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF000000.toInt() or rgb))
                                .clickable(enabled = connected) { vm.setDeviceTheme(rgb) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Text("LED color", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            THEME_SWATCHES.chunked(10).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (rgb, _) ->
                        Box(
                            Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF000000.toInt() or rgb))
                                .clickable(enabled = connected) { vm.setDeviceLedColor(rgb) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            // LED brightness (initialised from the device's real value via CAPS)
            var ledBright by remember(devLedBright) { mutableFloatStateOf((if (devLedBright >= 0) devLedBright else 50).toFloat()) }
            Text("LED brightness: ${ledBright.toInt()}%", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Slider(
                value = ledBright, onValueChange = { ledBright = it },
                onValueChangeFinished = { vm.setLedBright(ledBright.toInt()) },
                valueRange = 0f..100f, enabled = connected,
            )

            // LED animation picker
            var effectMenu by remember { mutableStateOf(false) }
            var effectIdx by remember(devLedEffect) { mutableIntStateOf(devLedEffect.coerceIn(0, LED_EFFECTS.size - 1)) }
            Text("LED animation", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Box {
                OutlinedButton(enabled = connected, onClick = { effectMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(LED_EFFECTS.getOrElse(effectIdx) { "Solid" })
                }
                androidx.compose.material3.DropdownMenu(expanded = effectMenu, onDismissRequest = { effectMenu = false }) {
                    LED_EFFECTS.forEachIndexed { i, name ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(name) }, onClick = {
                            effectIdx = i; effectMenu = false; vm.setLedEffect(i)
                        })
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Colors/LED apply live; tap Save to keep them on the device after a reboot.",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Button(enabled = connected, onClick = { vm.saveDeviceConfig() }, modifier = Modifier.fillMaxWidth()) {
                Text("Save to device")
            }
            if (status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
    }
}

@Composable
private fun DeviceScreen(vm: WardriveViewModel, onConnect: () -> Unit, updateLabel: String = "", onOpenUpdates: () -> Unit = {}) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val out by vm.deviceOut.collectAsState()
    val connected = status == "Connected"
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    // Lite mode: save power by turning the device screen/LED off while the phone
    // drives it. Both auto-restore if the connection drops, so it never stays dark. The state lives
    // in the ViewModel, so a rotation shows what the device is doing instead of resetting to on.
    val outputs by vm.deviceOutputs.collectAsState()
    var confirmPower by remember { mutableStateOf<String?>(null) }
    confirmPower?.let { action ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmPower = null },
            confirmButton = {
                TextButton(onClick = {
                    confirmPower = null
                    if (action == "reboot") vm.powerReboot() else vm.powerSleep()
                }) { Text(if (action == "reboot") "Reboot" else "Sleep") }
            },
            dismissButton = { TextButton(onClick = { confirmPower = null }) { Text("Cancel") } },
            title = { Text(if (action == "reboot") "Reboot the device?" else "Put the device to sleep?") },
            text = { Text("The companion link drops; running device operations end.") },
        )
    }
    val screenOn = outputs.screenOn
    val ledOn = outputs.ledOn

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text("Device - info, diagnostics, power", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        // Update shortcut - highlighted when an app/firmware update is available.
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onOpenUpdates() },
            colors = CardDefaults.cardColors(containerColor = if (updateLabel.isNotEmpty()) cs.primaryContainer else cs.surface),
            border = if (updateLabel.isNotEmpty()) androidx.compose.foundation.BorderStroke(2.dp, cs.primary) else null,
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Icons.Filled.SystemUpdate, contentDescription = null,
                    tint = if (updateLabel.isNotEmpty()) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(26.dp))
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Updates", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium,
                        color = if (updateLabel.isNotEmpty()) cs.onPrimaryContainer else cs.onSurface)
                    Text(updateLabel.ifBlank { "App and device firmware" },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (updateLabel.isNotEmpty()) cs.primary else cs.onSurfaceVariant,
                        fontWeight = if (updateLabel.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal)
                }
                androidx.compose.material3.Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))

        // Screen / LED controls FIRST so they are the first thing seen on this tab.
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("Screen & LED (lite mode)", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Text(
                    "Turn the device screen or LED off to save power while the phone drives it. Auto-restores if the connection drops.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Device screen", fontWeight = FontWeight.Medium)
                        Text(
                            if (screenOn) "On - turn off to save power" else "Off (lite mode)",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
                    Switch(checked = screenOn, enabled = connected, onCheckedChange = { vm.setDeviceScreen(it) })
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Device LED", fontWeight = FontWeight.Medium)
                        Text(
                            if (ledOn) "On - turn off to save power" else "Off (lite mode)",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
                    Switch(checked = ledOn, enabled = connected, onCheckedChange = { vm.setDeviceLed(it) })
                }
                Spacer(Modifier.height(10.dp))
                val devScrBright = device?.screenBrightShown ?: -1
                var scrBright by remember(devScrBright) { mutableFloatStateOf((devScrBright.takeIf { it >= 0 } ?: 100).toFloat()) }
                Text("Screen brightness: ${scrBright.toInt()}%", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Slider(
                    value = scrBright, onValueChange = { scrBright = it },
                    onValueChangeFinished = { vm.setScreenBright(scrBright.toInt()) },
                    valueRange = 0f..100f, enabled = connected && screenOn,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        ThemeLedCard(vm, connected)
        Spacer(Modifier.height(12.dp))

        DeviceCard(device)
        Spacer(Modifier.height(12.dp))
        CapabilityEvidenceCard(device)
        Spacer(Modifier.height(12.dp))
        DiagnosticsCard(vm, device)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
            Text(if (connected) "Reconnect over USB" else "Connect over USB")
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = { vm.deviceCmd("info") }) { Text("Info") }
            OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = { vm.deviceCmd("free") }) { Text("Memory") }
            OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = { vm.deviceCmd("uptime") }) { Text("Uptime") }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = { vm.deviceCmd("i2c") }) { Text("I2C scan") }
            OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = { confirmPower = "sleep" }) { Text("Sleep") }
            OutlinedButton(enabled = connected, modifier = Modifier.weight(1f), onClick = { confirmPower = "reboot" }) { Text("Reboot") }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { exportAndShare(context, "brucia_report_", "md", "text/markdown") { vm.buildReport() } }
        ) { Text("Generate security report") }
        Spacer(Modifier.height(8.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { exportAndShare(context, "brucia_session_", "json", "application/json") { vm.buildSessionJson() } }
        ) { Text("Export session JSON") }
        Spacer(Modifier.height(12.dp))
        if (out.isNotEmpty()) {
            // Rendered inline (not a nested LazyColumn) so the whole screen is one scroll container.
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Text(
                    out,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        Text(
            "${BrandLinks.NAME} ${BuildConfig.VERSION_NAME} - AGPL-3.0 - authorized research only - ${BrandLinks.REPO_DISPLAY}",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun Waveform(timings: IntArray, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        if (timings.isEmpty()) return@Canvas
        // Draw an OOK square wave: even index = mark (high), odd = space (low).
        val n = timings.size.coerceAtMost(600)
        var total = 0L
        for (i in 0 until n) total += timings[i].coerceAtLeast(1)
        if (total <= 0) return@Canvas
        val w = size.width
        val hi = size.height * 0.15f
        val lo = size.height * 0.85f
        var x = 0f
        var y = if (0 % 2 == 0) hi else lo
        for (i in 0 until n) {
            val segW = w * (timings[i].coerceAtLeast(1).toFloat() / total)
            val level = if (i % 2 == 0) hi else lo
            // vertical edge to this level
            drawLine(color, Offset(x, y), Offset(x, level), strokeWidth = 2f)
            // horizontal run
            drawLine(color, Offset(x, level), Offset(x + segW, level), strokeWidth = 2f)
            x += segW
            y = level
        }
    }
}

@Composable
private fun CrackScreen(vm: WardriveViewModel) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val wordlists by vm.wordlists.collectAsState()
    val bundled by vm.bundledWordlists.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val installedNames = wordlists.map { it.name }.toSet()
    val crack by vm.crack.collectAsState()
    val target by vm.handshakeInfo.collectAsState()
    val rules by vm.rules.collectAsState()
    var pmkidText by rememberSaveable { mutableStateOf("") }
    var pullPath by rememberSaveable { mutableStateOf("/BrucePCAP/handshakes/") }
    LaunchedEffect(Unit) { vm.refreshWordlists() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "wordlist_${System.currentTimeMillis()}.txt"
            vm.importWordlist(uri, name)
            Toast.makeText(context, "Importing $name...", Toast.LENGTH_SHORT).show()
        }
    }
    val pcapLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.importPcap(uri); Toast.makeText(context, "Parsing handshake...", Toast.LENGTH_SHORT).show() }
    }
    val hs by vm.hsFlow.collectAsState()
    val captureList by vm.captureList.collectAsState()
    var manualExpanded by remember { mutableStateOf(false) }

    // Single scroll container for the whole screen: header, controls and the wordlist
    // sections are all items of one LazyColumn (no nested same-axis scrollables).
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Text(
                "WPA/WPA2 dictionary attack - heavy crypto on the phone (what Flipper cannot do)",
                color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
            )
        }

        // How it works - one clear explanation of the self-driven flow.
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("How to crack a Wi-Fi password", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "1. In the Wardrive tab, tap an AP, then \"Capture + crack\". Brucia deauths it, " +
                            "captures the handshake, pulls it to the phone and loads it here automatically - no paths, no manual pull.\n" +
                            "2. Pick a wordlist below and tap Run.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
            }
        }

        // Guided capture-and-crack flow status (shown once a flow has started).
        if (hs.step != "idle") {
            item {
                val stepColor = when (hs.step) {
                    "ready" -> Color(0xFF4CAF50)
                    "failed" -> cs.error
                    else -> cs.primary
                }
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Handshake capture", fontWeight = FontWeight.SemiBold, color = stepColor, modifier = Modifier.weight(1f))
                            if (hs.active) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(hs.message, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        if (hs.active) {
                            TextButton(onClick = { vm.cancelHandshakeFlow() }) { Text("Cancel import") }
                        } else {
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = { vm.resetHandshakeFlow() }) { Text("Dismiss") }
                        }
                    }
                }
            }
        }

        // Crack status card.
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    when {
                        crack.result != null -> {
                            Text("Passphrase found", fontWeight = FontWeight.Bold, color = Color(0xFF4CAF50))
                            Text(crack.result!!, fontFamily = FontFamily.Monospace, color = cs.primary, style = MaterialTheme.typography.titleMedium)
                        }
                        crack.running -> Text("Cracking (${crack.note})...", fontWeight = FontWeight.SemiBold)
                        crack.finished -> Text("No match in tested candidates (${crack.note})", color = cs.onSurfaceVariant)
                        crack.note.isNotBlank() -> Text(crack.note, fontWeight = FontWeight.SemiBold, color = cs.error)
                        else -> Text("Idle - load a handshake and run a wordlist, or try the demo", color = cs.onSurfaceVariant)
                    }
                    if (crack.running || crack.finished) {
                        Text(
                            "tried ${crack.tried}${if (crack.total > 0) "/${crack.total}" else ""}  -  ${crack.keysPerSec} keys/s",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            Text("Target: ${target ?: "none loaded (Run uses a demo handshake)"}", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }

        // Primary run controls.
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !crack.running, modifier = Modifier.weight(1f), onClick = { vm.crackBuiltin() }) { Text("Built-in list") }
                OutlinedButton(enabled = !crack.running, modifier = Modifier.weight(1f), onClick = { vm.demoCrack() }) { Text("Demo") }
                OutlinedButton(enabled = crack.running, modifier = Modifier.weight(1f), onClick = { vm.cancelCrack() }) { Text("Stop") }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Apply mangling rules (expand wordlist)", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Switch(checked = rules, onCheckedChange = { vm.setRules(it) })
            }
        }

        // Manual fallback (import a pcap, pull a path by hand, paste a PMKID/22000 line).
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { manualExpanded = !manualExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Manual import (fallback)", fontWeight = FontWeight.SemiBold, color = cs.primary)
                            Text("Import a .pcap, pull a device path, or paste a PMKID / 22000 line", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                        Text(if (manualExpanded) "Hide" else "Show", color = cs.primary, style = MaterialTheme.typography.labelLarge)
                    }
                    if (manualExpanded) {
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(modifier = Modifier.weight(1f), onClick = { pcapLauncher.launch(arrayOf("*/*")) }) { Text("Import .pcap") }
                            Button(modifier = Modifier.weight(1f), onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Wordlist") }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pullPath,
                            onValueChange = { pullPath = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Device .pcap path") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { vm.pullFile(pullPath.trim()); Toast.makeText(context, "Pulling ${pullPath.trim()}...", Toast.LENGTH_SHORT).show() },
                            ) { Text("Pull") }
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = { vm.listCaptures() },
                            ) { Text("List captures") }
                        }
                        if (captureList.isNotBlank()) {
                            Text(captureList, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pmkidText,
                            onValueChange = { pmkidText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("PMKID (16800) or hashcat 22000 (WPA*...)") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            enabled = pmkidText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { vm.importPmkid(pmkidText.trim()) }
                        ) { Text("Load target") }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val line = vm.exportHashcat()
                                if (line != null) {
                                    val f = java.io.File(context.getExternalFilesDir(null), "brucia_${System.currentTimeMillis()}.hc22000")
                                    f.writeText(line + "\n")
                                    shareFile(context, f, "text/plain")
                                } else {
                                    Toast.makeText(context, "Load a handshake or PMKID first", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) { Text("Export hashcat 22000") }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text("Download real wordlists", color = cs.primary, fontWeight = FontWeight.SemiBold)
            Text("rockyou and SecLists, straight to the phone", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        items(vm.wordlistCatalog) { entry ->
                CatalogRow(
                    entry = entry,
                    progress = downloads[entry.name],
                    installed = installedNames.contains(entry.name),
                    onDownload = { vm.downloadWordlist(entry.name, entry.url) },
                    onCancel = { vm.cancelDownload(entry.name) },
                    onRun = { vm.crackWithWordlist(entry.name) },
                )
            }
            item {
                Spacer(Modifier.height(10.dp))
                Text("Offline quick list", color = cs.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
            items(bundled) { b -> BundledWordlistRow(b, onRun = { vm.crackBundled(b.asset, b.title) }) }
            item {
                Spacer(Modifier.height(10.dp))
                Text("Imported wordlists (${wordlists.size})", color = cs.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                if (wordlists.isEmpty()) {
                    Text("Download above, or import any .txt", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        items(wordlists) { wl -> WordlistRow(wl, onRun = { vm.crackWithWordlist(wl.name) }, onDelete = { vm.deleteWordlist(wl.name) }) }
    }
}

@Composable
private fun CatalogRow(
    entry: app.brucia.wordlist.DownloadableWordlist,
    progress: app.brucia.wordlist.DownloadProgress?,
    installed: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRun: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    Text("${entry.entries} entries  -  ~${entry.approxBytes / 1_000_000}MB", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                when {
                    progress?.running == true -> OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    installed -> OutlinedButton(onClick = onRun) { Text("Crack") }
                    else -> Button(onClick = onDownload) { Text("Download") }
                }
            }
            Text(entry.description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            if (progress?.running == true) {
                Spacer(Modifier.height(6.dp))
                val frac = if (progress.total > 0) (progress.downloaded.toFloat() / progress.total).coerceIn(0f, 1f) else 0f
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth(),
                    color = cs.primary,
                )
                Text(
                    "${progress.downloaded / 1_000_000}MB" + if (progress.total > 0) " / ${progress.total / 1_000_000}MB" else "",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            }
            if (progress?.error != null) {
                Text("Download failed: ${progress.error}", style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
        }
    }
}

/**
 * Renders a GitHub release body (Markdown) as readable lines inside a highlighted box, so the user
 * sees what an update contains (e.g. "Added central logging, fixed the wake-lock bug") before
 * tapping update. Light cleanup only - no full Markdown engine.
 */
@Composable
private fun ReleaseNotes(tag: String, notes: String, cs: androidx.compose.material3.ColorScheme) {
    val lines = remember(notes) {
        notes.replace("\r\n", "\n").split("\n")
            .map { raw ->
                var s = raw.trim()
                s = s.trimStart('#', ' ')                            // headings
                s = s.replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")      // bold
                s = s.replace(Regex("`(.*?)`"), "$1")                 // inline code
                s = s.replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")   // links -> text
                if (s.startsWith("- ") || s.startsWith("* ")) s = "•  " + s.substring(2)
                s
            }
            .filter { it.isNotBlank() && !it.matches(Regex("^[-*_ ]{3,}$")) } // drop blanks + hr rules
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceVariant).padding(10.dp),
    ) {
        Text("What's new in $tag", style = MaterialTheme.typography.labelMedium, color = cs.primary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        lines.take(40).forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
        }
    }
}

@Composable
private fun UpdatesScreen(vm: WardriveViewModel, onFlash: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val appUpdate by vm.releaseUpdates.collectAsState()
    val flash by vm.flash.collectAsState()
    val flashLog by vm.flashLog.collectAsState()
    val connected = status == "Connected"
    val currentApp = BuildConfig.VERSION_NAME

    val download by vm.appDownload.collectAsState()
    val boards = appUpdate.boards
    val selectedBoard = appUpdate.selectedBoard
    var boardMenu by remember { mutableStateOf(false) }

    // Check once on first open so the cards show versions without a manual tap.
    LaunchedEffect(Unit) {
        if (appUpdate.latest.isEmpty() && !appUpdate.checking) vm.checkUpdates(currentApp)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary),  contentDescription = null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(8.dp))
            Text("Brucia", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            StatusPill(connected)
        }
        Text("Updates - app and device firmware", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        // --- App OTA card ----------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cs.surface),
            // Highlight the card when a newer app version is available.
            border = if (appUpdate.newer) androidx.compose.foundation.BorderStroke(2.dp, cs.primary) else null,
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("App", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    if (appUpdate.newer) {
                        Spacer(Modifier.width(8.dp))
                        Text("UPDATE", color = cs.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Text("Installed: $currentApp", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Text(
                    "Latest: " + (appUpdate.latest.ifEmpty { "-" }),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                // What's new: the GitHub release notes (body), so the user reads what the update
                // contains before tapping Download & install.
                if (appUpdate.newer && appUpdate.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    ReleaseNotes(appUpdate.latest, appUpdate.notes, cs)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    enabled = !appUpdate.checking && !download.downloading && flash.phase !in listOf("download", "flash"),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.checkUpdates(currentApp) },
                ) { Text(if (appUpdate.checking) "Checking..." else "Check for app update") }

                if (appUpdate.newer && appUpdate.apkUrl != null) {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = !download.downloading,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            // One tap: installs if allowed, else opens the one-time grant and
                            // auto-resumes the install when the user returns (see Activity onResume).
                            if (!AppUpdater.canInstall(context)) {
                                Toast.makeText(context, "Allow installing apps from Brucia, then it continues automatically.", Toast.LENGTH_LONG).show()
                            }
                            vm.requestAppInstall(context)
                        },
                    ) { Text(if (download.downloading) "Downloading..." else "Download & install ${appUpdate.latest}") }
                    if (!appUpdate.apkIntegrity.verified) {
                        Text("Unverified: this release lists no SHA-256 digest for the APK.",
                            style = MaterialTheme.typography.labelSmall, color = cs.error, modifier = Modifier.padding(top = 4.dp))
                    }
                }

                if (download.downloading) {
                    Spacer(Modifier.height(6.dp))
                    val frac = if (download.total > 0) (download.downloaded.toFloat() / download.total).coerceIn(0f, 1f) else 0f
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { frac }, modifier = Modifier.fillMaxWidth(), color = cs.primary,
                    )
                    TextButton(onClick = { vm.cancelAppDownload() }) { Text("Cancel download") }
                }

                Spacer(Modifier.height(6.dp))
                val appStatus: Pair<String, Color>? = when {
                    appUpdate.error != null -> "Error: ${appUpdate.error}" to cs.error
                    download.error != null -> "Error: ${download.error}" to cs.error
                    appUpdate.checking -> null
                    appUpdate.latest.isEmpty() -> null
                    appUpdate.newer -> "Update available." to cs.primary
                    else -> "Up to date." to cs.onSurfaceVariant
                }
                if (appStatus != null) {
                    Text(appStatus.first, style = MaterialTheme.typography.bodySmall, color = appStatus.second)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // --- Device firmware card -------------------------------------------
        val flashing = flash.phase == "download" || flash.phase == "flash"
        // A firmware update is available when we know the installed fw and the latest tag is newer.
        val fwNewer = AppUpdater.firmwareUpdateNeeded(device?.fw.orEmpty(), appUpdate.latest,
            device?.features?.verifiedFiles == true,
            appUpdate.firmwareUrl != null && AppUpdater.supportsInAppFirmware(device?.board.orEmpty()))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cs.surface),
            border = if (fwNewer) androidx.compose.foundation.BorderStroke(2.dp, cs.primary) else null,
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Device firmware", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    if (fwNewer) {
                        Spacer(Modifier.width(8.dp))
                        Text("UPDATE", color = cs.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "Installed: " + (device?.fw?.ifEmpty { "unknown" } ?: "connect the device first"),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                Text(
                    "Latest: " + (appUpdate.latest.ifEmpty { "-" }),
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                // Same release notes as the app (one release holds both the APK and the firmware bin),
                // shown here too so the user sees what a firmware flash will bring.
                if (fwNewer && appUpdate.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    ReleaseNotes(appUpdate.latest, appUpdate.notes, cs)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Warning: keep the device connected over USB. Do NOT unplug it while flashing.",
                    style = MaterialTheme.typography.bodySmall, color = cs.error, fontWeight = FontWeight.SemiBold,
                )
                if (connected) {
                    Text(
                        "Flashing takes over the USB port and will disconnect companion mode.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // Board picker: default auto (connected T-Embed) or a specific per-board image.
                Text("Board image", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Box {
                    OutlinedButton(enabled = !flashing && !appUpdate.checking && boards.isNotEmpty(), onClick = { boardMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedBoard?.label ?: "Auto (connected device)")
                    }
                    androidx.compose.material3.DropdownMenu(expanded = boardMenu, onDismissRequest = { boardMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Auto (connected device)") },
                            onClick = { vm.selectBoard(null); boardMenu = false })
                        boards.forEach { b ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(b.label) },
                                onClick = { vm.selectBoard(b); boardMenu = false })
                        }
                    }
                }
                if (selectedBoard != null) {
                    Text(if (appUpdate.flashUrl == null)
                        "This board requires the web flasher and its matching release image. In-app flashing supports T-Embed CC1101."
                    else "Make sure the connected device IS a ${selectedBoard.label} before flashing.",
                        style = MaterialTheme.typography.labelSmall, color = cs.error, modifier = Modifier.padding(top = 4.dp))
                }
                if (appUpdate.flashUrl != null && !appUpdate.flashIntegrity.verified) {
                    Text("Unverified: this release lists no SHA-256 digest for the firmware image.",
                        style = MaterialTheme.typography.labelSmall, color = cs.error, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = !flashing && appUpdate.flashUrl != null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.resetFlash(); onFlash() },
                    colors = BruciaButtonDefaults.buttonColors(containerColor = cs.error),
                ) { Text(if (flashing) "Flashing..." else if (selectedBoard != null) "Flash ${selectedBoard.label}" else "Flash latest firmware") }

                if (flashing) {
                    Spacer(Modifier.height(6.dp))
                    val frac = if (flash.total > 0) (flash.written.toFloat() / flash.total).coerceIn(0f, 1f) else 0f
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { frac }, modifier = Modifier.fillMaxWidth(), color = cs.primary,
                    )
                }

                if (flash.message.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    val color = when (flash.phase) {
                        "done" -> Color(0xFF4CAF50)
                        "error" -> cs.error
                        else -> cs.onSurfaceVariant
                    }
                    Text(flash.message, style = MaterialTheme.typography.bodySmall, color = color)
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "Note: the USB flash path is implemented but pending on-hardware verification.",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
            }
        }

        // Persistent flash log: keeps every step and the MD5 verdict even after the device
        // reconnects (which clears the transient message above). Scrollable + copyable.
        if (flashLog.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant)) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Flash log", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.clearFlashLog() }) { Text("Clear") }
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            flashLog.joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BundledWordlistRow(b: BundledWordlistUi, onRun: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(b.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text("${b.description}  -  ${b.lines} words", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            OutlinedButton(onClick = onRun) { Text("Run") }
        }
    }
}

@Composable
private fun WordlistRow(wl: WordlistInfo, onRun: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(wl.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text("${wl.lines} words  -  ${wl.sizeBytes / 1024} KB", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            OutlinedButton(onClick = onRun) { Text("Run") }
            Spacer(Modifier.size(6.dp))
            OutlinedButton(onClick = onDelete) { Text("Del") }
        }
    }
}
