package app.brucia.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val core = NusConsoleCore(scope)
    val state: StateFlow<State> = core.state.asStateFlow()
    private val _devices = MutableStateFlow<List<Found>>(emptyList())
    val devices: StateFlow<List<Found>> = _devices.asStateFlow()
    /** Console text: sent commands prefixed with "> ", device output as it arrives. */
    val log: StateFlow<String> = core.log.asStateFlow()

    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var scanStop: Job? = null

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    // --- scan -----------------------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val found = Found(dev.address, result.scanRecord?.deviceName ?: dev.name ?: "(unnamed)", result.rssi)
            _devices.value = (_devices.value.filter { it.address != found.address } + found).sortedByDescending { it.rssi }
        }
        override fun onScanFailed(errorCode: Int) { core.state.value = State.Failed("Scan failed (error $errorCode)") }
    }

    fun startScan(durationMs: Long = 10_000L) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !bluetoothOn) { core.state.value = State.Failed("Bluetooth is off"); return }
        if (core.hasSession) return
        _devices.value = emptyList()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NusProtocol.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        core.state.value = State.Scanning
        scanStop?.cancel()
        scanStop = scope.launch { delay(durationMs); stopScan() }
    }

    fun stopScan() {
        scanStop?.cancel()
        scanStop = null
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) { }
        if (core.state.value == State.Scanning) core.state.value = State.Idle
    }

    // --- connect / pair -------------------------------------------------------------------------

    fun connect(found: Found) {
        stopScan()
        val dev = adapter?.getRemoteDevice(found.address) ?: run { core.state.value = State.Failed("Bluetooth is off"); return }
        core.connect(found.name) { session -> GattLink(dev, session).also { it.open() } }
    }

    fun disconnect() = core.disconnect()

    /** Queue [text] as one command line; returns the local rejection, or null when queued. */
    fun send(text: String): String? = core.send(text)

    fun clearLog() = core.clearLog()

    /**
     * One connectGatt call: its own callback object and bond receiver, both bound to [session], so
     * a late callback of an older connection reaches the core tagged with that older session.
     */
    private inner class GattLink(private val dev: BluetoothDevice, private val session: NusSession) : NusLink {
        @Volatile private var gatt: BluetoothGatt? = null
        private var rx: BluetoothGattCharacteristic? = null
        private var tx: BluetoothGattCharacteristic? = null
        private var bondReceiver: BroadcastReceiver? = null

        fun open() { gatt = dev.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) }

        /**
         * GATT callbacks arrive on a binder thread; hand them to the core on [scope] (the same
         * thread as connect/disconnect), and only while [g] is still this link's open GATT.
         */
        private fun deliver(g: BluetoothGatt, event: () -> Unit) {
            scope.launch { if (g === gatt) event() }
        }

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                val connected = newState == BluetoothProfile.STATE_CONNECTED
                if (!connected && newState != BluetoothProfile.STATE_DISCONNECTED) return
                deliver(g) { core.onConnectionStateChange(session, connected, status == BluetoothGatt.GATT_SUCCESS, status) }
            }

            override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
                deliver(g) { core.onMtuChanged(session, newMtu, status == BluetoothGatt.GATT_SUCCESS) }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                deliver(g) { core.onServicesDiscovered(session, status == BluetoothGatt.GATT_SUCCESS) }
            }

            @Deprecated("Android < 13 path")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION") val value = c.value?.copyOf() ?: return
                output(g, c, value)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                output(g, c, value)
            }

            private fun output(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                deliver(g) { if (c === tx) core.onNotification(session, c.uuid, value) }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                deliver(g) { if (c === rx) core.onCharacteristicWrite(session, c.uuid, status == BluetoothGatt.GATT_SUCCESS) }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                deliver(g) { if (d.characteristic === tx) core.onDescriptorWrite(session, d.uuid, status == BluetoothGatt.GATT_SUCCESS) }
            }
        }

        override fun isBonded() = dev.bondState == BluetoothDevice.BOND_BONDED

        /** Pair first (the firmware ignores writes from an unpaired phone); Android asks for the PIN. */
        override fun startBond(): Boolean {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d?.address != dev.address) return
                    when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                        BluetoothDevice.BOND_BONDED -> { unregisterBond(); core.onBondState(session, bonded = true) }
                        BluetoothDevice.BOND_NONE -> { unregisterBond(); core.onBondState(session, bonded = false) }
                    }
                }
            }
            bondReceiver = receiver
            val f = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(receiver, f)
            if (!dev.createBond()) { unregisterBond(); return false }
            return true
        }

        private fun unregisterBond() {
            bondReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) { } }
            bondReceiver = null
        }

        override fun requestMtu(mtu: Int) = gatt?.requestMtu(mtu) == true

        override fun discoverServices() = gatt?.discoverServices() == true

        override fun resolveNus(): Boolean {
            val svc = gatt?.getService(NusProtocol.SERVICE) ?: return false
            rx = svc.getCharacteristic(NusProtocol.RX)
            tx = svc.getCharacteristic(NusProtocol.TX)
            return rx != null && tx != null
        }

        override fun enableNotifications(): Boolean {
            val g = gatt ?: return false
            val t = tx ?: return false
            if (!g.setCharacteristicNotification(t, true)) return false
            val cccd = t.getDescriptor(NusProtocol.CCCD) ?: return false
            return if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") g.writeDescriptor(cccd)
            }
        }

        override fun write(chunk: ByteArray): Boolean {
            val g = gatt ?: return false
            val c = rx ?: return false
            return if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION") c.value = chunk
                @Suppress("DEPRECATION") g.writeCharacteristic(c)
            }
        }

        override fun close() {
            unregisterBond()
            val g = gatt ?: return
            gatt = null
            rx = null
            tx = null
            try { g.disconnect(); g.close() } catch (_: Exception) { }
        }
    }
}
