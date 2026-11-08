package app.brucia.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phone side of the firmware's BLE API (Nordic UART, [NusProtocol]): scan for devices that
 * advertise it, connect, pair (Android asks for the PIN the device shows), subscribe to the
 * output and send CLI lines. Independent of the USB companion link: it is a second, plain-text
 * console for when the device is not plugged in. One connection at a time; GATT operations are
 * serialised (Android allows one in flight). The caller holds the Bluetooth runtime permissions.
 */
@SuppressLint("MissingPermission")
class BleConsole(private val context: Context, private val scope: CoroutineScope) {
    sealed class State {
        data object Idle : State()
        data object Scanning : State()
        data class Connecting(val name: String) : State()
        data class Pairing(val name: String) : State()
        data class Ready(val name: String) : State()
        data class Failed(val reason: String) : State()
    }

    data class Found(val address: String, val name: String, val rssi: Int)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _devices = MutableStateFlow<List<Found>>(emptyList())
    val devices: StateFlow<List<Found>> = _devices.asStateFlow()
    private val _log = MutableStateFlow("")
    /** Console text: sent commands prefixed with "> ", device output as it arrives. */
    val log: StateFlow<String> = _log.asStateFlow()

    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var name = ""
    private val assembler = NusLineAssembler()
    private var writes = Channel<ByteArray>(Channel.UNLIMITED)
    private var writer: Job? = null
    private var scanStop: Job? = null
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingDescriptor: CompletableDeferred<Boolean>? = null

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    // --- scan -----------------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val found = Found(dev.address, result.scanRecord?.deviceName ?: dev.name ?: "(unnamed)", result.rssi)
            _devices.value = (_devices.value.filter { it.address != found.address } + found).sortedByDescending { it.rssi }
        }
        override fun onScanFailed(errorCode: Int) { _state.value = State.Failed("Scan failed (error $errorCode)") }
    }

    fun startScan(durationMs: Long = 10_000L) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !bluetoothOn) { _state.value = State.Failed("Bluetooth is off"); return }
        if (gatt != null) return
        _devices.value = emptyList()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NusProtocol.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        _state.value = State.Scanning
        scanStop?.cancel()
        scanStop = scope.launch { delay(durationMs); stopScan() }
    }

    fun stopScan() {
        scanStop?.cancel()
        scanStop = null
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) { }
        if (_state.value == State.Scanning) _state.value = State.Idle
    }

    // --- connect / pair -------------------------------------------------------------------------

    fun connect(found: Found) {
        stopScan()
        val dev = adapter?.getRemoteDevice(found.address) ?: run { _state.value = State.Failed("Bluetooth is off"); return }
        disconnect()
        name = found.name
        mtu = 23
        assembler.clear()
        writes = Channel(Channel.UNLIMITED)
        _state.value = State.Connecting(name)
        gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        writer?.cancel()
        writer = null
        pendingWrite?.complete(false)
        pendingDescriptor?.complete(false)
        unregisterBond()
        gatt?.let { try { it.disconnect(); it.close() } catch (_: Exception) { } }
        gatt = null
        rx = null
        if (_state.value !is State.Failed) _state.value = State.Idle
    }

    private var bondReceiver: BroadcastReceiver? = null

    private fun unregisterBond() {
        bondReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) { } }
        bondReceiver = null
    }

    /** Pair first (the firmware ignores writes from an unpaired phone), then set the link up. */
    private fun ensureBonded(g: BluetoothGatt) {
        val dev = g.device
        if (dev.bondState == BluetoothDevice.BOND_BONDED) { g.requestMtu(247); return }
        _state.value = State.Pairing(name)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                    i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != dev.address) return
                when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> { unregisterBond(); gatt?.requestMtu(247) }
                    BluetoothDevice.BOND_NONE -> {
                        unregisterBond()
                        fail("Pairing failed or cancelled. Enter the PIN shown on the device.")
                    }
                }
            }
        }
        bondReceiver = receiver
        val f = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, f)
        if (!dev.createBond()) { unregisterBond(); fail("Could not start pairing") }
    }

    private fun fail(reason: String) {
        _state.value = State.Failed(reason)
        disconnect()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                if (g != gatt) return@launch
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> ensureBonded(g)
                    newState == BluetoothProfile.STATE_DISCONNECTED -> {
                        val wasReady = _state.value is State.Ready
                        fail(if (wasReady) "Disconnected" else "Connection failed (status $status)")
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            scope.launch {
                if (g != gatt) return@launch
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                if (!g.discoverServices()) fail("Service discovery failed")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch {
                if (g != gatt) return@launch
                val svc = g.getService(NusProtocol.SERVICE)
                val rxc = svc?.getCharacteristic(NusProtocol.RX)
                val txc = svc?.getCharacteristic(NusProtocol.TX)
                if (status != BluetoothGatt.GATT_SUCCESS || rxc == null || txc == null) {
                    fail("No BLE API on this device (turn it on: Config > Advanced > Toggle BLE API)"); return@launch
                }
                rx = rxc
                if (!subscribe(g, txc)) { fail("Could not subscribe to the device output"); return@launch }
                _state.value = State.Ready(name)
                append("Connected to $name. Type a command (for example: info).\n")
                startWriter(g)
            }
        }

        @Deprecated("Android < 13 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") onOutput(c.uuid, c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onOutput(c.uuid, value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingDescriptor?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private suspend fun subscribe(g: BluetoothGatt, tx: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(tx, true)) return false
        val cccd = tx.getDescriptor(NusProtocol.CCCD) ?: return false
        val done = CompletableDeferred<Boolean>().also { pendingDescriptor = it }
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION") g.writeDescriptor(cccd)
        }
        return started && (withTimeoutOrNull(10_000L) { done.await() } == true)
    }

    private fun onOutput(uuid: java.util.UUID, value: ByteArray) {
        if (uuid != NusProtocol.TX) return
        scope.launch { assembler.feed(value).forEach { append("$it\n") } }
    }

    // --- send -----------------------------------------------------------------------------------

    /** Queue [text] as one command line; returns the local rejection, or null when queued. */
    fun send(text: String): String? {
        NusProtocol.rejection(text)?.let { return it }
        if (_state.value !is State.Ready) return "Not connected"
        val line = NusProtocol.commandLine(text) ?: return "Nothing to send."
        append("> ${line.trimEnd()}\n")
        NusProtocol.chunks(line, mtu).forEach { writes.trySend(it) }
        return null
    }

    private fun startWriter(g: BluetoothGatt) {
        writer?.cancel()
        writer = scope.launch {
            for (chunk in writes) {
                val c = rx ?: break
                val done = CompletableDeferred<Boolean>().also { pendingWrite = it }
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION") c.value = chunk
                    @Suppress("DEPRECATION") g.writeCharacteristic(c)
                }
                if (!started || withTimeoutOrNull(10_000L) { done.await() } != true) {
                    append("(write failed: is the phone still paired with this device?)\n")
                }
            }
        }
    }

    fun clearLog() { _log.value = "" }

    private fun append(text: String) {
        _log.value = (_log.value + text).takeLast(20_000)
    }
}
