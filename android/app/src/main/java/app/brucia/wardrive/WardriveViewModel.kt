package app.brucia.wardrive

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import android.util.Base64
import android.location.Location
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.brucia.data.ApEntity
import app.brucia.data.BruciaDb
import app.brucia.ble.BleDevice
import app.brucia.nfc.NfcSecurity
import app.brucia.mitm.MitmLog
import app.brucia.report.ReportBuilder
import app.brucia.report.ReportData
import app.brucia.report.SessionExport
import app.brucia.report.SessionReport
import app.brucia.wifi.CrackStatus
import app.brucia.wifi.Dot11Frame
import app.brucia.wifi.Dot11Info
import app.brucia.wifi.Handshake
import app.brucia.wifi.HandshakeParser
import app.brucia.wifi.PcapWriter
import app.brucia.wifi.Hc22000
import app.brucia.wifi.WordlistRules
import app.brucia.wifi.Pmkid
import app.brucia.wifi.PmkidTarget
import app.brucia.wifi.WpaCracker
import app.brucia.wordlist.BundledWordlists
import app.brucia.wordlist.BundledWordlistUi
import app.brucia.wordlist.DownloadProgress
import app.brucia.wordlist.WordlistCatalog
import app.brucia.wordlist.WordlistInfo
import app.brucia.wordlist.WordlistStore
import app.brucia.data.SavedItem
import app.brucia.device.CliParse
import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.ir.IrButton
import app.brucia.ir.IrParser
import app.brucia.subghz.DecodedSignal
import app.brucia.subghz.OokSamples
import app.brucia.subghz.SubFile
import app.brucia.subghz.SubGhzDecoder
import app.brucia.subghz.SubSignal
import app.brucia.transport.Transport
import app.brucia.update.AppUpdater
import app.brucia.update.EspFlasher
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import app.brucia.audio.PcmSource
import app.brucia.audio.Dtmf
import app.brucia.audio.Sstv
import app.brucia.service.ConnectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Cap on buffered Sub-GHz capture rows, so a long recording cannot exhaust memory. */
private const val MAX_RF_CAPTURE_ROWS = 5000
private const val MAX_MONITOR_FRAMES = 4000

/** Rolling history depth for the Sub-GHz spectrum waterfall (rows = sweeps over time). */
private const val MAX_SPECTRUM_ROWS = 64

/** Default advertisement/fake-AP count ("copies") for the spam loops. */
const val DEFAULT_SPAM_COPIES = 10

/** Length of each abortable spam burst, in seconds, so the link stays responsive between bursts. */
// Each burst re-initializes the device radio (BLE/Wi-Fi/NRF24 init+deinit per command), so very
// short bursts churn the radio ~30x/min, which can destabilize the firmware and reboot the device.
// 8s keeps the op abortable (<=8s to Stop) while cutting that churn ~4x. The proper fix (keep the
// radio up / run the op in a device-side task) is a firmware change tracked separately.
private const val SPAM_BURST_SECS = 8

// Command log: how many of the most recent commands to retain (newest-first), and the
// max length of a stored result string before it is truncated for display.
private const val MAX_CMD_LOG = 60
private const val MAX_CMD_RESULT_LEN = 600

/** Lifecycle of a single device command on the single-threaded companion link. */
enum class CmdStatus { RUNNING, DONE, FAILED }

/** A credential captured by the Evil Portal: [ap] is the portal name, [pass] what the victim typed. */
data class EvilCred(val ap: String, val pass: String, val at: Long = System.currentTimeMillis())

/** A custom Evil Portal captive-page template authored in the app (name + HTML). */
data class SavedPortal(val name: String, val html: String)

/** One WIDS monitoring window: [deauth]/[total] frames seen, [alert] if over threshold. */
data class WidsEvent(val at: Long, val deauth: Int, val total: Int, val alert: Boolean)

/** The RF "signature" of a place: the set of Wi-Fi BSSIDs and BLE MACs seen there, for later
 *  change detection (something new appearing can mean new surveillance). */
data class RfFingerprint(val name: String, val at: Long, val bssids: List<String>, val bleMacs: List<String>)

/** One device in the RF presence timeline: first/last seen + how many times, across sessions. */
data class RfSighting(val key: String, val type: String, val label: String, val firstSeen: Long, val lastSeen: Long, val count: Int)

/** A saved signal (RF or IR) pinned to a GPS location, for geofenced replay. [payload] is the
 *  device replay command (e.g. "rf tx ..." or "ir tx ..."). */
data class GeoSignal(val name: String, val type: String, val payload: String, val lat: Double, val lon: Double, val at: Long)

/**
 * One entry in the command log shown by the Cmds viewer. [id] is a monotonic local id (not the
 * protocol frame id). RUNNING when dispatched; DONE/FAILED once the ACK/result arrives.
 */
data class CommandLogEntry(
    val id: Long,
    val text: String,
    val status: CmdStatus,
    val sentAtMs: Long,
    val doneAtMs: Long? = null,
    val result: String? = null,
    val ok: Boolean? = null,
)

/**
 * Progress of the guided "capture and crack" handshake flow (deauth -> capture -> auto-pull
 * -> load into the cracker). [step] is one of idle / deauth / capture / pull / ready / failed
 * and [message] is a human-readable status line for the Crack screen.
 */
data class HandshakeFlow(
    val active: Boolean = false,
    val step: String = "idle",
    val message: String = "",
    val bssid: String = "",
    val ssid: String = "",
)

/**
 * A Wi-Fi station (client) seen by the device's station sniffer (SUB stream "wifi.sta").
 * Deduped by [mac]; [rssi]/[ch]/[bssid] hold the latest sighting and [count] the total.
 */
data class Station(
    val mac: String,
    val bssid: String,
    val rssi: Int,
    val ch: Int,
    val count: Int,
    val lastSeen: Long,
)

/** One 2.4GHz channel's activity level from the NRF24 scanner (EVENT nrf24.scan). */
data class Nrf24Channel(val ch: Int, val level: Int)

/** An Enhanced ShockBurst device discovered by the NRF24 sniffer (EVENT nrf24.dev). */
data class Nrf24Device(
    val addr: String,
    val ch: Int,
    val rssi: Int,
    val count: Int,
    val lastSeen: Long,
)

/** A mousejackable device discovered during MouseJack scan/inject (EVENT nrf24.mj, data {addr, ch, type}). */
data class Nrf24MjDevice(
    val addr: String,
    val ch: Int,
    val type: String,
    val lastSeen: Long,
)

/** A LoRa packet received during lora_recv (ACK or EVENT lora.rx, data {data, rssi, snr}). */
data class LoraPacket(
    val data: String,
    val rssi: Int,
    val snr: Double,
    val lastSeen: Long,
)

/** Ethernet link status reported by eth_status (ACK data {link, ip, ...}). */
data class EthStatus(
    val link: Boolean,
    val ip: String,
    val detail: String = "",
)

/**
 * One characteristic discovered during GATT exploration (EVENT ble.gatt, data {svc, chr, props}).
 * [props] is the firmware's raw property string (for example "r", "r/w", "read,notify"); [readable]
 * derives from it so the UI can offer a Read button only where it makes sense.
 */
data class GattCharacteristic(val uuid: String, val props: String) {
    /** True when the property string advertises a read property, whatever separators it uses. */
    val readable: Boolean
        get() = props.lowercase().split(Regex("[^a-z]+")).any { it == "r" || it == "read" }
}

/** One GATT service and its characteristics, grouped from EVENT ble.gatt by service UUID. */
data class GattService(val uuid: String, val characteristics: List<GattCharacteristic>)

/**
 * One Sub-GHz spectrum sweep (SUB stream "subghz.spectrum"): parallel arrays of [freqs] (Hz)
 * and their [rssi] levels, plus the wall-clock [time] it was assembled. Built from either the
 * per-sweep EVENT shape ({start, step, rssi:[...]}) or accumulated per-point ones ({freq, rssi}).
 */
data class SpectrumSweep(val freqs: LongArray, val rssi: IntArray, val time: Long)

/**
 * One Wi-Fi probe request seen by the device (SUB stream "wifi.probe"): a client [mac] asking
 * for a network [ssid] (empty = broadcast/wildcard). Deduped by mac+ssid; [rssi] holds the
 * latest sighting and [count] the total.
 */
data class ProbeRequest(val mac: String, val ssid: String, val rssi: Int, val count: Int, val lastSeen: Long)

/**
 * One raw 802.11 frame captured by the "Monitor my network" tool (SUB stream "wifi.monitor").
 * [raw] is the decoded frame bytes (used for pcap export and the hex view); [info] is the
 * decoded header summary that drives the packet list.
 */
data class MonitorFrame(
    val index: Int,
    val tsMicros: Long,
    val ch: Int,
    val rssi: Int,
    val len: Int,
    val raw: ByteArray,
    val info: Dot11Info,
)

/** One recovered MIFARE Classic sector key (type "A"/"B"). */
data class MifareFound(val sector: Int, val type: String, val key: String)

/** Progress + results of the batched MIFARE key-dictionary attack. */
data class MifareAttackState(
    val running: Boolean = false,
    val batch: Int = 0,
    val totalBatches: Int = 0,
    val found: List<MifareFound> = emptyList(),
    val message: String = "",
)

/** App OTA self-update status (installed vs latest GitHub release + download progress). */
data class AppUpdateState(
    val checking: Boolean = false,
    val current: String = "",
    val latest: String = "",
    val apkUrl: String? = null,
    val newer: Boolean = false,
    val notes: String = "", // GitHub release body (Markdown): what changed in [latest]
    val downloading: Boolean = false,
    val downloaded: Long = 0,
    val total: Long = 0,
    val error: String? = null,
)

/** Latest device-firmware release info (tag + resolved merged-image URL). */
data class FirmwareState(
    val latestTag: String = "",
    val firmwareUrl: String? = null,
    val error: String? = null,
)

/**
 * Device firmware flash progress. [phase] is idle / download / flash / done / error;
 * [written]/[total] track bytes for a progress bar.
 */
data class FlashState(
    val phase: String = "idle",
    val written: Int = 0,
    val total: Int = 0,
    val message: String = "",
)

/** SSTV / speaker transmit progress for the Audio tools (SSTV TX, DTMF). */
data class SstvTxState(
    val active: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
)

/** Owns the companion connection, the GPS fix, and persists AP observations. */
class WardriveViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BruciaDb.get(app).apDao()

    private val _status = MutableStateFlow("Disconnected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    private val _transportKind = MutableStateFlow("none")
    val transportKind: StateFlow<String> = _transportKind.asStateFlow()

    private val _wardriving = MutableStateFlow(false)
    val wardriving: StateFlow<Boolean> = _wardriving.asStateFlow()

    private val _subghz = MutableStateFlow<List<DecodedSignal>>(emptyList())
    val subghz: StateFlow<List<DecodedSignal>> = _subghz.asStateFlow()

    private val _rfWave = MutableStateFlow(IntArray(0))
    val rfWave: StateFlow<IntArray> = _rfWave.asStateFlow()

    // Raw Sub-GHz capture-to-CSV: while capturing is on, each subghz.raw event is appended
    // as a row. Capped to avoid OOM on long captures.
    private val rfCaptureRows = java.util.ArrayList<RfCaptureRow>()
    private val _rfCapture = MutableStateFlow(RfCaptureState(false, 0))
    val rfCapture: StateFlow<RfCaptureState> = _rfCapture.asStateFlow()

    private val _track = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val track: StateFlow<List<Pair<Double, Double>>> = _track.asStateFlow()

    private val _ble = MutableStateFlow<Map<String, BleDevice>>(emptyMap())
    val ble: StateFlow<List<BleDevice>> =
        _ble.map { m -> m.values.sortedByDescending { it.rssi } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Station sniffer (SUB "wifi.sta"): deduped by MAC, newest RSSI first.
    private val _stations = MutableStateFlow<Map<String, Station>>(emptyMap())
    val stations: StateFlow<List<Station>> =
        _stations.map { m -> m.values.sortedByDescending { it.rssi } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _sniffing = MutableStateFlow(false)
    val sniffing: StateFlow<Boolean> = _sniffing.asStateFlow()

    // Sub-GHz spectrum waterfall (SUB stream "subghz.spectrum"): latest sweep + rolling history.
    private val _spectrum = MutableStateFlow<SpectrumSweep?>(null)
    val spectrum: StateFlow<SpectrumSweep?> = _spectrum.asStateFlow()
    private val _spectrumHistory = MutableStateFlow<List<SpectrumSweep>>(emptyList())
    val spectrumHistory: StateFlow<List<SpectrumSweep>> = _spectrumHistory.asStateFlow()
    private val _spectrumActive = MutableStateFlow(false)
    val spectrumActive: StateFlow<Boolean> = _spectrumActive.asStateFlow()
    // Per-point accumulator for the {freq, rssi} EVENT shape; flushed into a sweep on wrap.
    private val spectrumPts = LinkedHashMap<Long, Int>()
    private var lastSpectrumFreq = Long.MIN_VALUE

    // Wi-Fi probe-request profiling (SUB stream "wifi.probe"): deduped by client mac + ssid.
    private val _probes = MutableStateFlow<Map<String, ProbeRequest>>(emptyMap())
    val probes: StateFlow<List<ProbeRequest>> =
        _probes.map { m -> m.values.sortedByDescending { it.lastSeen } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    // "Monitor my network" (SUB "wifi.monitor"): rolling buffer of raw 802.11 frames.
    private val _monitorFrames = MutableStateFlow<List<MonitorFrame>>(emptyList())
    val monitorFrames: StateFlow<List<MonitorFrame>> = _monitorFrames.asStateFlow()
    private val _monitoring = MutableStateFlow(false)
    val monitoring: StateFlow<Boolean> = _monitoring.asStateFlow()
    private var monitorIndex = 0

    // Shared status line for BLE advertisement spam (Apple ProximityPair + Google Fast Pair).
    private val _bleSpamStatus = MutableStateFlow("")
    val bleSpamStatus: StateFlow<String> = _bleSpamStatus.asStateFlow()
    // BLE adv spam is a blocking, time-bounded op on the firmware: one long `ble_spam_platform
    // <t> <secs>` freezes the device and the whole companion link for its full duration, with no
    // way to stop. Instead we run a cancellable loop of SHORT (~2s) bursts back to back: between
    // bursts the device + link are free, so the user can Stop (<=2s latency) and the mic.pcm
    // audio tools get serviced again. [bleSpamActive] drives the UI Start/Stop toggle.
    private val _bleSpamActive = MutableStateFlow(false)
    val bleSpamActive: StateFlow<Boolean> = _bleSpamActive.asStateFlow()
    private var bleSpamJob: Job? = null

    // Wi-Fi beacon spam uses the same abortable short-burst loop model as BLE spam above.
    private val _beaconStatus = MutableStateFlow("")
    val beaconStatus: StateFlow<String> = _beaconStatus.asStateFlow()
    private val _beaconActive = MutableStateFlow(false)
    val beaconActive: StateFlow<Boolean> = _beaconActive.asStateFlow()
    private var beaconSpamJob: Job? = null

    // Batched MIFARE key-dictionary attack state + optional user-imported keys.
    private val _mifare = MutableStateFlow(MifareAttackState())
    val mifare: StateFlow<MifareAttackState> = _mifare.asStateFlow()
    private val _mifareUserKeys = MutableStateFlow<List<String>>(emptyList())
    val mifareUserKeys: StateFlow<List<String>> = _mifareUserKeys.asStateFlow()

    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console.asStateFlow()

    private val _irLearned = MutableStateFlow<List<IrButton>>(emptyList())
    val irLearned: StateFlow<List<IrButton>> = _irLearned.asStateFlow()

    // Fixed-contract IR actions (ir_tvbgone / ir_rx / ir_tx_raw). Status lines drive the UI.
    private val _irTvBGoneStatus = MutableStateFlow("")
    val irTvBGoneStatus: StateFlow<String> = _irTvBGoneStatus.asStateFlow()
    private val _irRxStatus = MutableStateFlow("")
    val irRxStatus: StateFlow<String> = _irRxStatus.asStateFlow()
    // The last decoded receive, held until the user saves or re-arms RX.
    private val _irRxResult = MutableStateFlow<IrButton?>(null)
    val irRxResult: StateFlow<IrButton?> = _irRxResult.asStateFlow()

    private val savedDao = BruciaDb.get(app).savedDao()
    val savedNfc: StateFlow<List<SavedItem>> =
        savedDao.observe("nfc").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val savedRf: StateFlow<List<SavedItem>> =
        savedDao.observe("rf").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val savedIr: StateFlow<List<SavedItem>> =
        savedDao.observe("ir").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _lastNfc = MutableStateFlow<String?>(null)
    val lastNfc: StateFlow<String?> = _lastNfc.asStateFlow()
    private val _lastRf = MutableStateFlow<String?>(null)
    val lastRf: StateFlow<String?> = _lastRf.asStateFlow()

    // Advanced NFC status lines. _nfcNdefStatus is shared by both NDEF-push entry points
    // (the NFC screen's push card and the Spam hub tile) so the behaviour never drifts.
    private val _nfcNdefStatus = MutableStateFlow("")
    val nfcNdefStatus: StateFlow<String> = _nfcNdefStatus.asStateFlow()
    private val _nfcEmvStatus = MutableStateFlow("")
    val nfcEmvStatus: StateFlow<String> = _nfcEmvStatus.asStateFlow()
    private val _nfcUidStatus = MutableStateFlow("")
    val nfcUidStatus: StateFlow<String> = _nfcUidStatus.asStateFlow()
    private val _nfcAdvStatus = MutableStateFlow("")
    val nfcAdvStatus: StateFlow<String> = _nfcAdvStatus.asStateFlow()

    private val _wigleStatus = MutableStateFlow<WigleUploadStatus>(WigleUploadStatus.Idle)
    val wigleStatus: StateFlow<WigleUploadStatus> = _wigleStatus.asStateFlow()

    // WiGLE credentials are the user's own secret: kept in a private SharedPreferences,
    // never logged.
    private val wiglePrefs by lazy {
        getApplication<Application>().getSharedPreferences("wigle", android.content.Context.MODE_PRIVATE)
    }

    fun wigleApiName(): String = wiglePrefs.getString("api_name", "").orEmpty()
    fun wigleApiToken(): String = wiglePrefs.getString("api_token", "").orEmpty()

    /**
     * Build the WiGLE CSV from the collected observations and POST it to wigle.net with the
     * given API Name / Token. Persists the credentials and drives [wigleStatus].
     */
    fun uploadToWigle(apiName: String, apiToken: String) {
        if (_wigleStatus.value is WigleUploadStatus.Uploading) return
        val name = apiName.trim()
        val token = apiToken.trim()
        if (name.isEmpty() || token.isEmpty()) {
            _wigleStatus.value = WigleUploadStatus.Error("Enter your WiGLE API Name and Token")
            return
        }
        val observations = aps.value
        if (observations.isEmpty()) {
            _wigleStatus.value = WigleUploadStatus.Error("No wardrive data to upload")
            return
        }
        wiglePrefs.edit().putString("api_name", name).putString("api_token", token).apply()
        _wigleStatus.value = WigleUploadStatus.Uploading
        viewModelScope.launch(Dispatchers.IO) {
            val csv = WigleCsv.build(observations).toByteArray(Charsets.UTF_8)
            val filename = "brucia_${System.currentTimeMillis()}.csv"
            val result = WigleUploader.upload(name, token, csv, filename)
            _wigleStatus.value = when (result) {
                is WigleUploader.Result.Success -> WigleUploadStatus.Success(result.message)
                is WigleUploader.Result.Failure -> WigleUploadStatus.Error(result.message)
            }
        }
    }

    // Pending command callbacks keyed by request id, so concurrent in-flight commands
    // do not clobber each other (see cmd() id correlation).
    private val pendingAcks = java.util.concurrent.ConcurrentHashMap<Int, (Boolean, String) -> Unit>()
    // ACK callbacks that need the full response object (e.g. mifare_keycheck's found[] array).
    private val pendingJsonAcks = java.util.concurrent.ConcurrentHashMap<Int, (Boolean, JSONObject?) -> Unit>()
    // Per-pull byte buffers keyed by the fs_read request id.
    private val pullBufs = java.util.concurrent.ConcurrentHashMap<Int, java.io.ByteArrayOutputStream>()
    // Device streams currently subscribed (wifi.ap / subghz.raw / ble.scan), so we can
    // unsubscribe them on disconnect and stop the device radios.
    private val pendingStreamIds = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val activeStreams = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Access points, newest first, backed by Room (survives app restarts). */
    val aps: StateFlow<List<ApObservation>> =
        dao.observeAll()
            .map { list -> list.map { it.toObservation() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Live analytics over the collected APs (encryption, channels, vendors). */
    val stats: StateFlow<WardriveStats> =
        aps.map { computeStats(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, computeStats(emptyList()))

    private var client: CompanionClient? = null
    private var readJob: Job? = null
    private var pingJob: Job? = null
    // Link watchdog: time of the last PONG/CAPS. If the device stops answering while we still think
    // we are connected, it rebooted out of companion mode (USB-CDC can stay open), so commands all
    // error - we detect that and re-negotiate instead of showing a stuck "Connected".
    @Volatile private var lastPongAt = 0L
    private var transport: Transport? = null

    @Volatile private var lastLocation: Location? = null
    private val _currentLoc = MutableStateFlow<Pair<Double, Double>?>(null)
    val currentLoc: StateFlow<Pair<Double, Double>?> = _currentLoc.asStateFlow()
    private val fused = LocationServices.getFusedLocationProviderClient(app)
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                lastLocation = it
                _currentLoc.value = it.latitude to it.longitude
                val pt = it.latitude to it.longitude
                val cur = _track.value
                if (cur.isEmpty() || cur.last() != pt) _track.value = (cur + pt).takeLast(5000)
            }
        }
    }

    fun connect(t: Transport) {
        disconnect()
        transport = t
        _transportKind.value = t.kind
        client = CompanionClient(t)
        lastPongAt = System.currentTimeMillis()
        // Stay "Connecting..." until the first CAPS/DeviceInfo handshake arrives.
        _status.value = "Connecting..."
        startCompanionLoops()
        // Foreground service keeps the process alive + holds a wake lock for the link while the
        // screen is off. Started here (link opened) and stopped in disconnect() - the single pair.
        try { ConnectionService.start(getApplication(), "Connecting...", "") } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "service start (connecting)", e) }
    }

    /** Comma-joined active device streams for the service notification. */
    private fun streamsText(): String = synchronized(activeStreams) { activeStreams.sorted().joinToString(", ") }

    /** Refresh the foreground-service notification with the current status + streams. */
    private fun refreshConnectionNotification() {
        if (transport?.isOpen != true) return
        try { ConnectionService.update(getApplication(), _status.value, streamsText()) } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "service update", e) }
    }

    /**
     * (Re)start the companion read loop and the battery ping loop. Factored out so both
     * connect() and stopRemote() can bring companion mode back up. Cancels any existing
     * loops first so they are never double-started.
     */
    private fun startCompanionLoops() {
        val c = client ?: return
        readJob?.cancel()
        pingJob?.cancel()
        readJob = viewModelScope.launch(Dispatchers.IO) {
            // Mode auto-negotiation: send the framed HELLO; if no CAPS/DeviceInfo lands
            // within ~1.5s the device is likely sitting at its normal CLI menu, so nudge it
            // into companion mode with the RAW (un-framed) line "companion\n" and retry
            // HELLO. When the device is already in companion mode the first HELLO just works
            // and the nudge never fires (CAPS arrives, _device becomes non-null).
            c.hello()
            var helloDeadline = System.currentTimeMillis() + 1500
            while (isActive) {
                if (transport?.isOpen != true) {
                    // Device unplugged / link lost.
                    _status.value = "Disconnected (device unplugged)"
                    _device.value = null
                    _wardriving.value = false
                    break
                }
                // Never let a read/parse/handle error crash the app: this runs in a background
                // coroutine whose uncaught exceptions would otherwise reach the default handler.
                val msg = try {
                    c.readMessage(1000)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    null
                }
                ensureActive()
                if (msg != null) {
                    lastPongAt = System.currentTimeMillis() // any frame (ACK/EVENT/PONG) proves the link is alive
                    try {
                        handle(msg)
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                    }
                }
                if (_device.value == null && System.currentTimeMillis() > helloDeadline) {
                    // Raw bytes, NOT a framed message: the plain-text CLI reads this line.
                    try { transport?.write("companion\n".toByteArray(Charsets.US_ASCII)) } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "companion-mode nudge write", e) }
                    kotlinx.coroutines.delay(300)
                    c.hello()
                    helloDeadline = System.currentTimeMillis() + 1500
                }
            }
        }
        // Periodic ping so the battery % stays fresh while connected.
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(8000)
                if (transport?.isOpen != true) break
                c.ping(c.nextId())
                // Also re-fetch CAPS so a theme change made ON THE DEVICE propagates to the app: CAPS
                // carries the live device UI color (theme), and handling it rebuilds _device, which
                // re-themes the app accent. The device always wins; app-side theme_set updates _device
                // immediately. Cheap (cached caps) and no device redraw (helloSeen already true). Skip
                // while a command is awaiting its ACK so we don't interleave during a long blocking op.
                if (pendingAcks.isEmpty() && pendingJsonAcks.isEmpty()) client?.hello()
                // Link watchdog: if the device has stopped answering (no PONG/CAPS for ~26s, i.e.
                // ~3 missed pings) while we believe we are connected, it most likely rebooted out of
                // companion mode (the USB-CDC can stay open, so every command just errors and the UI
                // would be stuck on "Connected"). Drop to re-negotiation: clearing _device makes the
                // read loop resume its "companion\n" + HELLO auto-nudge, which re-enters companion
                // once the device is back; stop any running op loops that would just keep erroring.
                // A long bounded command (brucegotchi up to 600s, handshake/jam_detect up to 120s,
                // etc.) runs synchronously on the device's single loop, so it can't PONG mid-run.
                // Don't declare "restarted" while a command is still awaiting its ACK - only after a
                // long absolute silence (covers brucegotchi) or 26s with no command in flight.
                val cmdPending = pendingAcks.isNotEmpty() || pendingJsonAcks.isNotEmpty()
                val silentMs = System.currentTimeMillis() - lastPongAt
                if (_device.value != null && ((!cmdPending && silentMs > 26000L) || silentMs > 650000L)) {
                    _device.value = null
                    _status.value = "Reconnecting (device restarted?)..."
                    stopAllBurstLoops(silent = true)
                    lastPongAt = System.currentTimeMillis() // give the re-negotiation time before retriggering
                }
            }
        }
    }

    // --- Remote control (drive the real Bruce UI: nav + screen mirror over raw serial) ---
    private var remote: app.brucia.remote.RemoteController? = null
    private var remoteJob: Job? = null
    private var remoteWatchdogJob: Job? = null
    private val _remoteFrame = MutableStateFlow(0L)
    val remoteFrame: StateFlow<Long> = _remoteFrame.asStateFlow()
    fun remoteBitmap(): android.graphics.Bitmap? = remote?.renderer?.bitmap
    // Mirror watchdog state: last time we saw any raw bytes vs. a decoded TFT frame.
    @Volatile private var lastRemoteByteAt = 0L
    @Volatile private var lastRemoteFrameAt = 0L

    /** Start remote control. Drives the device's normal UI over raw CLI + screen mirror. */
    fun startRemote() {
        val t = transport ?: return
        // Remote control needs the device OUT of companion mode: it talks raw CLI (nav / display
        // start) handled by the device's serial task, which companion mode suspends while it owns
        // the serial for the framed protocol. Left in companion mode the mirror gets no frames and
        // the screen is black, and the app-lock would still be on. Ask it to leave first, then
        // (after it returns to the normal UI and resumes the serial task) start the mirror. On
        // stopRemote we re-enter companion mode, which re-applies the lock.
        try { client?.cmd("companion_exit") } catch (e: Exception) { app.brucia.BruciaLog.d("Remote", "companion_exit send: ${e.message}") }
        readJob?.cancel(); pingJob?.cancel() // remote is exclusive with the companion loop
        val rc = app.brucia.remote.RemoteController(t)
        remote = rc
        val now = System.currentTimeMillis()
        lastRemoteByteAt = now; lastRemoteFrameAt = now
        remoteJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(900) // let the device return to its normal UI + resume the CLI task
            lastRemoteByteAt = System.currentTimeMillis(); lastRemoteFrameAt = lastRemoteByteAt
            rc.startMirror()
            val buf = ByteArray(4096)
            while (isActive) {
                if (transport?.isOpen != true) break
                val n = try { t.read(buf, 200) } catch (_: Exception) { break }
                if (n > 0) {
                    lastRemoteByteAt = System.currentTimeMillis()
                    val before = rc.frame
                    rc.onBytes(buf, n)
                    if (rc.frame != before) { lastRemoteFrameAt = System.currentTimeMillis(); _remoteFrame.value = rc.frame }
                }
            }
        }
        // Auto-resume the mirror after a device reboot. On reboot the firmware's async-serial
        // state resets to off (startAsyncSerial is idempotent) while the USB-CDC link survives,
        // so nav keeps working but the screen stream dies silently. Detect the tell-tale: raw
        // bytes still arriving (the boot log / CLI chatter) but no decoded TFT frames for a
        // while -> re-send "display start" to re-arm. On a genuinely idle/static screen no bytes
        // arrive either, so this never fires spuriously.
        remoteWatchdogJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(1000)
                if (transport?.isOpen != true) continue
                val t2 = System.currentTimeMillis()
                val gettingBytes = (t2 - lastRemoteByteAt) < 2000
                val noFrames = (t2 - lastRemoteFrameAt) > 2500
                if (gettingBytes && noFrames) {
                    remote?.startMirror()           // idempotent re-arm on the firmware
                    lastRemoteFrameAt = t2          // debounce: don't re-arm again for 2.5s
                }
            }
        }
    }

    // Transport writes go on an IO dispatcher so a stalled USB endpoint never blocks the UI.
    fun remoteNav(dir: String) { val r = remote ?: return; viewModelScope.launch(Dispatchers.IO) { r.nav(dir) } }
    fun remoteCli(line: String) { val r = remote ?: return; viewModelScope.launch(Dispatchers.IO) { r.cli(line) } }
    fun stopRemote() {
        remote?.stopMirror()
        remoteWatchdogJob?.cancel(); remoteWatchdogJob = null
        remoteJob?.cancel(); remoteJob = null
        remote = null
        // Remote was exclusive with the companion loops (startRemote cancelled them);
        // bring companion mode back so wardrive/console/pings/file pulls work again.
        if (transport?.isOpen == true) startCompanionLoops()
    }

    // --- Command log ---------------------------------------------------------------------
    // Visibility into the single-threaded companion link: every actual CMD frame is recorded
    // here (RUNNING on dispatch, DONE/FAILED on ACK) by the central dispatch helpers below, so
    // the user can see what was sent, what is still running, and what finished. High-frequency
    // streaming (fm_audio_data / spkr_pcm_data / EVENT streams) is intentionally NOT logged.
    private val cmdLogIdGen = java.util.concurrent.atomic.AtomicLong(0L)
    private val _commandLog = MutableStateFlow<List<CommandLogEntry>>(emptyList())
    val commandLog: StateFlow<List<CommandLogEntry>> = _commandLog.asStateFlow()

    /** Record a dispatched command as RUNNING and return its local log id. Thread-safe. */
    private fun logCmdStart(cmd: String): Long {
        val id = cmdLogIdGen.incrementAndGet()
        val entry = CommandLogEntry(
            id = id,
            text = cmd,
            status = CmdStatus.RUNNING,
            sentAtMs = System.currentTimeMillis(),
        )
        _commandLog.update { (listOf(entry) + it).take(MAX_CMD_LOG) }
        return id
    }

    /** Resolve the RUNNING entry [logId] to DONE/FAILED with a short result. Thread-safe. */
    private fun logCmdDone(logId: Long, ok: Boolean, result: String) {
        val now = System.currentTimeMillis()
        val short = result.trim().let {
            if (it.length > MAX_CMD_RESULT_LEN) it.take(MAX_CMD_RESULT_LEN) + "..." else it
        }
        _commandLog.update { list ->
            list.map { e ->
                if (e.id == logId) {
                    e.copy(
                        status = if (ok) CmdStatus.DONE else CmdStatus.FAILED,
                        doneAtMs = now,
                        result = short,
                        ok = ok,
                    )
                } else {
                    e
                }
            }
        }
    }

    /** Empty the command log. */
    fun clearCommandLog() { _commandLog.value = emptyList() }

    /** Convert local send failures into the same completion path as firmware ERR. */
    private fun transmitCommand(c: CompanionClient, command: String, id: Int) {
        try {
            check(client === c && transport?.isOpen == true) { "USB connection is closed or changed" }
            if (command.startsWith("rfid125_read"))
                check(_device.value?.caps?.lfRfid == true) { "No confirmed 125 kHz reader" }
            if (command.startsWith("ibutton_"))
                check(_device.value?.caps?.ibutton == true) { "No confirmed iButton interface" }
            c.cmd(command, id)
            check(transport?.isOpen == true) { "USB write failed" }
        } catch (e: Exception) {
            val reply = JSONObject().put("t", Kind.ERR).put("id", id)
                .put("d", JSONObject().put("msg", e.message ?: "Unable to send command"))
            app.brucia.protocol.routeCommandReply(reply, pendingAcks, pendingJsonAcks)
        }
    }

    /** Fire-and-forget command on IO; optionally route its ACK to a one-shot callback. */
    private fun dispatchCmd(cmd: String, cb: ((Boolean, String) -> Unit)? = null) {
        val c = client ?: return
        val logId = logCmdStart(cmd)
        viewModelScope.launch(Dispatchers.IO) {
            val id = c.nextId()
            pendingAcks[id] = { ok, out ->
                logCmdDone(logId, ok, out)
                cb?.invoke(ok, out)
            }
            transmitCommand(c, cmd, id)
        }
    }

    /** Like [dispatchCmd] but routes the full ACK data object to the callback. */
    private fun dispatchCmdJson(cmd: String, cb: (Boolean, JSONObject?) -> Unit) {
        val c = client ?: run { cb(false, null); return }
        val logId = logCmdStart(cmd)
        viewModelScope.launch(Dispatchers.IO) {
            val id = c.nextId()
            pendingJsonAcks[id] = { ok, d ->
                logCmdDone(logId, ok, d?.toString() ?: "")
                cb(ok, d)
            }
            transmitCommand(c, cmd, id)
        }
    }

    fun setDeviceScreen(on: Boolean) = dispatchCmd("scr ${if (on) 1 else 0}")
    fun setDeviceLed(on: Boolean) = dispatchCmd("led ${if (on) 1 else 0}")
    /** Send one command and suspend until its ACK (or a timeout). Used by the spam burst loops. */
    // Default ACK wait must exceed the burst duration: bounded ops (spam/deauth/jammer) only ACK
    // AFTER running for SPAM_BURST_SECS, so a shorter timeout would always report "no response".
    private suspend fun sendAwait(cmd: String, timeoutMs: Long = (SPAM_BURST_SECS + 4) * 1000L): Pair<Boolean, String> {
        val c = client ?: return false to "Not connected."
        val logId = logCmdStart(cmd)
        try {
            val res = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                    val id = c.nextId()
                    pendingAcks[id] = { ok, out -> if (cont.isActive) cont.resumeWith(Result.success(ok to out)) }
                    cont.invokeOnCancellation { pendingAcks.remove(id) }
                    transmitCommand(c, cmd, id)
                }
            } ?: (false to "No response from the device.")
            logCmdDone(logId, res.first, res.second)
            return res
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Loop stopped (Stop / disconnect) mid-burst: resolve the entry so it never hangs on RUNNING.
            logCmdDone(logId, false, "Stopped.")
            throw ce
        }
    }

    /**
     * Shared engine for the abortable short-burst loops (BLE/Wi-Fi spam, Wi-Fi deauth, the NRF24
     * ops). Instead of one long blocking command (which froze the device + whole companion link for
     * its full duration with no way to abort), it launches a cancellable loop that fires SHORT (~2s)
     * bounded commands back to back and awaits each ACK before the next, so bursts are sequential
     * and never queued. Between bursts the device + link are free: the user can Stop within ~2s and
     * contending streams (mic.pcm) get serviced again. [active] both gates the loop and drives the
     * UI Start/Stop toggle; [cmd] builds each burst's command; [onReject] fires once on a hard
     * rejection (a non-blank failure, e.g. missing module) and ends the loop; [onStopped] runs when
     * the loop ends, but only for the current job (a restart supersedes an older one). [getJob]/
     * [setJob] let the caller hold the Job so Stop/disconnect can cancel it.
     */
    private fun launchBurstLoop(
        getJob: () -> Job?,
        setJob: (Job?) -> Unit,
        active: MutableStateFlow<Boolean>,
        cmd: () -> String,
        onReject: (String) -> Unit = {},
        onStopped: () -> Unit = {},
    ) {
        active.value = true
        val job = viewModelScope.launch(Dispatchers.IO) {
            val myJob = coroutineContext[Job]
            try {
                while (isActive && active.value && transport?.isOpen == true) {
                    val (ok, out) = sendAwait(cmd())
                    if (!ok && out.isNotBlank()) { onReject(out); break }
                }
            } finally {
                if (getJob() === myJob) {
                    active.value = false
                    onStopped()
                }
            }
        }
        setJob(job)
    }

    /**
     * Start continuous, abortable BLE advertisement spam for one platform's pairing popup
     * (`ble_spam_platform <ios|android|windows|samsung|all> <secs> <copies>`). Instead of one long
     * blocking op (which froze the device + link until it ended), this launches a cancellable loop
     * that fires SHORT (~2s) bursts back to back and awaits each burst's ACK before the next, so
     * bursts are sequential and never queued. Between bursts the device and link are free: the user
     * can [stopBleSpam] within ~2s and the mic.pcm audio tools get serviced again. [copies] is the
     * number of advertisements per burst (passed as the 3rd token; ignored by current firmware, so
     * backward-compatible). Single entry point for the Spam hub and the native BLE screen.
     */
    fun startBleSpam(target: String, copies: Int = DEFAULT_SPAM_COPIES) {
        if (client == null) { _bleSpamStatus.value = "Connect the device over USB first."; return }
        // If a spam loop is already running, stop it first (restart cleanly, never stack two loops).
        stopBleSpam(silent = true)
        // A live mic.pcm stream and spam contend for the single-threaded companion loop; drop it.
        if (_micPcmActive.value) stopMicPcm()
        val n = copies.coerceIn(1, 100)
        _bleSpamActive.value = true
        _bleSpamStatus.value = "Sending $target... (Stop to end)"
        bleSpamJob = viewModelScope.launch(Dispatchers.IO) {
            val myJob = coroutineContext[Job]
            try {
                while (isActive && _bleSpamActive.value && transport?.isOpen == true) {
                    val (ok, out) = sendAwait("ble_spam_platform $target $SPAM_BURST_SECS $n")
                    // A hard rejection (e.g. unsupported target) ends the loop and shows why.
                    if (!ok && out.isNotBlank()) { _bleSpamStatus.value = out; break }
                }
            } finally {
                // Only the current job updates shared UI state (a restart supersedes the old job).
                if (bleSpamJob === myJob) {
                    _bleSpamActive.value = false
                    if (_bleSpamStatus.value.startsWith("Sending")) _bleSpamStatus.value = "Stopped."
                }
            }
        }
    }

    /** Stop the BLE spam loop. [silent] keeps the status line (used when restarting). */
    fun stopBleSpam(silent: Boolean = false) {
        bleSpamJob?.cancel()
        bleSpamJob = null
        _bleSpamActive.value = false
        if (!silent) _bleSpamStatus.value = "Stopped."
    }

    /** Apple ProximityPair popups; delegates to [startBleSpam] ("ios"). */
    fun bleSpam(copies: Int = DEFAULT_SPAM_COPIES) = startBleSpam("ios", copies)

    // --- File manager (device SD/LittleFS via the storage CLI) ---
    fun listDir(path: String, cb: (List<app.brucia.files.FileEntry>) -> Unit) {
        runWithResult("storage list $path") { _, out -> cb(app.brucia.files.FileList.parse(out)) }
    }
    fun deleteDeviceFile(path: String, cb: () -> Unit) {
        runWithResult("storage remove $path") { _, _ -> cb() }
    }
    fun makeDeviceDir(path: String, cb: () -> Unit) {
        runWithResult("storage mkdir $path") { _, _ -> cb() }
    }

    private suspend fun handle(msg: JSONObject) {
        when (msg.optInt("t")) {
            Kind.DATA -> {
                val id = msg.optInt("id", -1)
                val d = msg.optJSONObject("d") ?: return
                if (d.optString("stream") == "file") {
                    // Route the chunk to the buffer for its request id; fall back to the
                    // only active pull if the firmware did not echo a matching id.
                    val buf = pullBufs[id] ?: pullBufs.values.singleOrNull()
                    try { buf?.write(Base64.decode(d.optString("chunk"), Base64.DEFAULT)) } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "file-pull chunk write", e) }
                }
            }
            Kind.ACK, Kind.ERR -> {
                val out = app.brucia.protocol.routeCommandReply(msg, pendingAcks, pendingJsonAcks)
                if (out.isNotEmpty()) {
                    val lines = out.split("\n").filter { it.isNotBlank() }
                    _console.value = (_console.value + lines).takeLast(500)
                }
            }
            Kind.CAPS -> {
                val d = msg.optJSONObject("d") ?: return
                val modsArr = d.optJSONArray("modules")
                val mods = buildList {
                    if (modsArr != null) for (i in 0 until modsArr.length()) add(modsArr.optString(i))
                }
                _device.value = DeviceInfo(
                    board = d.optString("board", "device"),
                    chip = d.optString("chip", ""),
                    fw = d.optString("fw", ""),
                    modules = mods,
                    battery = d.optInt("battery", -1),
                    caps = DeviceCaps.fromJson(d.optJSONObject("caps")),
                    themeRgb565 = d.optInt("theme", 0),
                    ledRgb = d.optInt("led", 0),
                    ledBright = d.optInt("ledBright", -1),
                    ledEffect = d.optInt("ledEffect", 0),
                    screenBright = d.optInt("bright", -1),
                )
                // Handshake complete: only now is the link truly "Connected".
                lastPongAt = System.currentTimeMillis()
                _status.value = "Connected"
                refreshConnectionNotification()
            }
            Kind.PONG -> {
                // PING replies carry fresh battery; keep the device card up to date.
                lastPongAt = System.currentTimeMillis()
                val d = msg.optJSONObject("d") ?: return
                if (d.has("battery")) {
                    _device.value?.let { _device.value = it.copy(battery = d.optInt("battery", it.battery)) }
                }
            }
            Kind.EVENT -> {
                val d = msg.optJSONObject("d") ?: return
                val ev = d.optString("ev")
                if (ev == "mic.pcm") {
                    // Raw device-mic PCM chunk: base64 of S16LE mono. Decode to shorts and publish on
                    // the single micPcm flow; the Audio tools (spectrum / waterfall / oscilloscope /
                    // tone / SSTV RX) each consume that one stream.
                    val b64 = d.optString("pcm")
                    if (b64.isEmpty()) return
                    val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return }
                    if (_recording.value) synchronized(recBuf) {
                        if (recBuf.size() < REC_MAX_BYTES) recBuf.write(bytes) else _recording.value = false
                    }
                    val shorts = ShortArray(bytes.size / 2)
                    var j = 0
                    var k = 0
                    while (k + 1 < bytes.size) {
                        shorts[j++] = ((bytes[k].toInt() and 0xFF) or (bytes[k + 1].toInt() shl 8)).toShort()
                        k += 2
                    }
                    _micPcm.value = shorts
                    return
                }
                if (ev == "subghz.raw") {
                    val data = d.optJSONObject("data") ?: return
                    val arr: JSONArray = data.optJSONArray("timings") ?: return
                    val timings = IntArray(arr.length()) { arr.optInt(it) }
                    _rfWave.value = timings
                    if (_rfCapture.value.capturing) {
                        val freqHz = data.optLong("freq", 0L)
                        appendRfCaptureRow(RfCaptureRow(System.currentTimeMillis(), freqHz, timings))
                    }
                    val decoded = SubGhzDecoder.decode(timings)
                    if (decoded.isNotEmpty()) _subghz.value = (decoded + _subghz.value).take(50)
                    return
                }
                if (ev == "wifi.sta") {
                    val r = d.optJSONObject("data") ?: return
                    val mac = r.optString("mac")
                    if (mac.isEmpty()) return
                    val prev = _stations.value[mac]
                    _stations.value = _stations.value + (mac to Station(
                        mac = mac,
                        bssid = r.optString("bssid"),
                        rssi = r.optInt("rssi"),
                        ch = r.optInt("ch"),
                        count = (prev?.count ?: 0) + 1,
                        lastSeen = System.currentTimeMillis(),
                    ))
                    return
                }
                if (ev == "ble.dev") {
                    val r = d.optJSONObject("data") ?: return
                    val mac = r.optString("mac")
                    if (mac.isNotEmpty()) {
                        _ble.value = _ble.value + (mac to BleDevice(mac, r.optString("name"), r.optInt("rssi"), System.currentTimeMillis()))
                    }
                    return
                }
                if (ev == "ble.gatt") {
                    // One discovered characteristic: {svc, chr, props}. Fold into the service tree,
                    // preserving discovery order within each service.
                    val r = d.optJSONObject("data") ?: return
                    val svc = r.optString("svc")
                    val chr = r.optString("chr")
                    if (svc.isEmpty() || chr.isEmpty()) return
                    val tree = LinkedHashMap(_gattTree.value)
                    val chars = LinkedHashMap(tree[svc] ?: emptyMap())
                    chars[chr] = r.optString("props")
                    tree[svc] = chars
                    _gattTree.value = tree
                    return
                }
                if (ev == "nrf24.scan") {
                    val r = d.optJSONObject("data") ?: return
                    val ch = r.optInt("ch", -1)
                    if (ch < 0) return
                    _nrf24Channels.value = _nrf24Channels.value + (ch to r.optInt("level"))
                    return
                }
                if (ev == "nrf24.dev") {
                    val r = d.optJSONObject("data") ?: return
                    val addr = r.optString("addr")
                    if (addr.isEmpty()) return
                    val prev = _nrf24Devices.value[addr]
                    _nrf24Devices.value = _nrf24Devices.value + (addr to Nrf24Device(
                        addr = addr,
                        ch = r.optInt("ch"),
                        rssi = r.optInt("rssi"),
                        count = (prev?.count ?: 0) + 1,
                        lastSeen = System.currentTimeMillis(),
                    ))
                    return
                }
                if (ev == "nrf24.mj") {
                    val r = d.optJSONObject("data") ?: return
                    val addr = r.optString("addr")
                    if (addr.isEmpty()) return
                    _nrf24MjDevices.value = _nrf24MjDevices.value + (addr to Nrf24MjDevice(
                        addr = addr,
                        ch = r.optInt("ch"),
                        type = r.optString("type"),
                        lastSeen = System.currentTimeMillis(),
                    ))
                    return
                }
                if (ev == "evil.cred") {
                    val r = d.optJSONObject("data") ?: return
                    val pass = r.optString("pass")
                    if (pass.isEmpty()) return
                    _evilCreds.value = _evilCreds.value + EvilCred(ap = r.optString("ap"), pass = pass)
                    return
                }
                if (ev == "lora.rx") {
                    val r = d.optJSONObject("data") ?: return
                    addLoraPacket(r)
                    return
                }
                if (ev == "subghz.spectrum") {
                    val data = d.optJSONObject("data") ?: return
                    val rssiArr = data.optJSONArray("rssi")
                    if (rssiArr != null) {
                        // Per-sweep shape: {start, step, rssi:[...]} -> one full sweep.
                        val start = data.optLong("start")
                        val step = data.optLong("step")
                        val n = rssiArr.length()
                        val freqs = LongArray(n) { start + it.toLong() * step }
                        val rssi = IntArray(n) { rssiArr.optInt(it) }
                        pushSpectrumSweep(SpectrumSweep(freqs, rssi, System.currentTimeMillis()))
                    } else if (data.has("freq")) {
                        // Per-point shape: {freq, rssi}. A freq that wraps back down / repeats
                        // marks the start of the next sweep, so flush what we have first.
                        val f = data.optLong("freq")
                        val r = data.optInt("rssi")
                        if (spectrumPts.isNotEmpty() && (f <= lastSpectrumFreq || spectrumPts.containsKey(f))) {
                            flushSpectrumPoints()
                        }
                        lastSpectrumFreq = f
                        spectrumPts[f] = r
                    }
                    return
                }
                if (ev == "wifi.probe") {
                    val r = d.optJSONObject("data") ?: return
                    val mac = r.optString("mac")
                    if (mac.isEmpty()) return
                    val ssid = r.optString("ssid")
                    val key = "$mac|$ssid"
                    val prev = _probes.value[key]
                    _probes.value = _probes.value + (key to ProbeRequest(
                        mac = mac,
                        ssid = ssid,
                        rssi = r.optInt("rssi"),
                        count = (prev?.count ?: 0) + 1,
                        lastSeen = System.currentTimeMillis(),
                    ))
                    return
                }
                if (ev == "wifi.monitor") {
                    val r = d.optJSONObject("data") ?: return
                    val b64 = r.optString("data")
                    if (b64.isEmpty()) return
                    val raw = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return }
                    if (raw.isEmpty()) return
                    val frame = MonitorFrame(
                        index = monitorIndex++,
                        tsMicros = r.optLong("ts"),
                        ch = r.optInt("ch"),
                        rssi = r.optInt("rssi"),
                        len = r.optInt("len", raw.size),
                        raw = raw,
                        info = Dot11Frame.parse(raw),
                    )
                    _monitorFrames.value = (_monitorFrames.value + frame).takeLast(MAX_MONITOR_FRAMES)
                    return
                }
                if (ev != "wifi.ap") return
                val r = d.optJSONObject("data") ?: return
                val bssid = r.optString("bssid")
                if (bssid.isEmpty()) return
                val loc = lastLocation
                val rssi = r.optInt("rssi")
                // RSSI-weighted position estimate: stronger signal => closer => higher
                // weight. Accumulate across sightings so the fix improves over time.
                val existing = dao.get(bssid)
                val w = if (loc != null) Math.pow(10.0, rssi / 10.0) else 0.0
                val wLat = (existing?.wLat ?: 0.0) + (loc?.latitude ?: 0.0) * w
                val wLon = (existing?.wLon ?: 0.0) + (loc?.longitude ?: 0.0) * w
                val wSum = (existing?.wSum ?: 0.0) + w
                val estLat = if (wSum > 0) wLat / wSum else existing?.lat ?: loc?.latitude
                val estLon = if (wSum > 0) wLon / wSum else existing?.lon ?: loc?.longitude
                dao.upsert(
                    ApEntity(
                        bssid = bssid,
                        ssid = r.optString("ssid"),
                        channel = r.optInt("ch"),
                        rssi = rssi,
                        enc = r.optString("enc"),
                        lat = estLat,
                        lon = estLon,
                        timeMillis = System.currentTimeMillis(),
                        wLat = wLat,
                        wLon = wLon,
                        wSum = wSum,
                        seen = (existing?.seen ?: 0) + 1,
                    )
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocation() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        try {
            fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {
        }
    }

    /** Send a raw CLI command to the device (used by the IR module: `ir tx ...`). */
    fun sendCommand(cmd: String) {
        dispatchCmd(cmd)
    }

    /**
     * De Bruijn brute force: the phone computes the shortest sequence covering every
     * [bits]-bit fixed OOK code, the device transmits it via `subghz tx`. Returns the
     * command sent (also echoed to the console) so the UI can show what ran.
     */
    fun transmitDeBruijn(bits: Int, freqHz: Long, teUs: Int, repeat: Int): String {
        val (hex, len) = app.brucia.subghz.DeBruijn.hexKey(bits)
        val cmd = "subghz tx $hex $freqHz $teUs $repeat"
        _console.value = (_console.value + "> de Bruijn B(2,$bits): $len bits").takeLast(500)
        dispatchCmd(cmd)
        return cmd
    }

    // Wi-Fi deauth of one AP is a time-bounded op that blocks the whole companion link for its full
    // duration on the firmware. Like the spam loops, we run it as a cancellable loop of SHORT (~2s)
    // `wifi_deauth <bssid> <ch> <secs>` bursts, each awaited before the next, so Stop lands within
    // ~2s and other commands/streams get serviced between bursts. Only one AP is deauthed at a time
    // (single-threaded device); [deauthTarget] holds its BSSID so a per-AP card shows Stop only on
    // the AP currently being deauthed.
    private val _deauthActive = MutableStateFlow(false)
    val deauthActive: StateFlow<Boolean> = _deauthActive.asStateFlow()
    private val _deauthTarget = MutableStateFlow("")
    val deauthTarget: StateFlow<String> = _deauthTarget.asStateFlow()
    private var deauthJob: Job? = null

    /**
     * Start continuous, abortable Wi-Fi deauth of [bssid] on [channel] (authorized testing only).
     * Replaces the old blocking one-shot: a cancellable loop of ~2s `wifi_deauth` bursts, each
     * awaited before the next, so the device + link stay free between bursts and [stopDeauth] lands
     * within ~2s. [channel] is kept as given.
     */
    fun startDeauth(bssid: String, channel: Int) {
        if (client == null) { _console.value = (_console.value + "> deauth: connect the device first").takeLast(500); return }
        stopDeauth(silent = true)
        if (_micPcmActive.value) stopMicPcm()
        _deauthTarget.value = bssid
        _console.value = (_console.value + "> wifi_deauth $bssid ch$channel loop").takeLast(500)
        launchBurstLoop(
            getJob = { deauthJob },
            setJob = { deauthJob = it },
            active = _deauthActive,
            cmd = { "wifi_deauth $bssid $channel $SPAM_BURST_SECS" },
            onStopped = { _deauthTarget.value = "" },
        )
    }

    /** Stop the Wi-Fi deauth loop. [silent] is accepted for a uniform stop-method signature. */
    fun stopDeauth(silent: Boolean = false) {
        deauthJob?.cancel()
        deauthJob = null
        _deauthActive.value = false
        _deauthTarget.value = ""
    }

    /**
     * Phone-driven handshake capture of a target AP: the device deauths + sniffs the
     * 4-way handshake to /BrucePCAP/handshakes/ (then pull it in the Crack tab and crack).
     */
    fun captureHandshake(bssid: String, channel: Int, ssid: String, seconds: Int = 30) {
        _console.value = (_console.value + "> capture handshake $bssid ch$channel ${seconds}s").takeLast(500)
        dispatchCmd("wifi_handshake $bssid $channel $seconds $ssid")
    }

    private val _hsFlow = MutableStateFlow(HandshakeFlow())
    /** Live progress of the guided capture-and-crack flow (observed by the Crack screen). */
    val hsFlow: StateFlow<HandshakeFlow> = _hsFlow.asStateFlow()

    /** Reset the guided handshake flow status. */
    fun resetHandshakeFlow() { _hsFlow.value = HandshakeFlow() }

    /**
     * Fully self-driven handshake flow for one AP: (1) short deauth burst to force a
     * reconnect, (2) capture the 4-way handshake on the device, (3) automatically list
     * /BrucePCAP/handshakes/, pick the newest .pcap for this BSSID, pull it and load it
     * into the cracker. The user never types a path or pulls by hand; progress shows in
     * [hsFlow]. The device runs these ops sequentially, so each step chains off the ACK of
     * the previous one.
     */
    fun captureAndCrack(bssid: String, channel: Int, ssid: String, captureSeconds: Int = 20) {
        if (client == null) {
            _hsFlow.value = HandshakeFlow(step = "failed", message = "Connect the device over USB first.")
            return
        }
        val label = ssid.ifEmpty { bssid }
        _hsFlow.value = HandshakeFlow(active = true, step = "deauth", message = "Deauthing $label...", bssid = bssid, ssid = ssid)
        // 1) Short deauth burst so clients reconnect and emit a fresh handshake.
        dispatchCmd("wifi_deauth $bssid $channel 5") { _, _ ->
            _hsFlow.value = _hsFlow.value.copy(step = "capture", message = "Capturing handshake (${captureSeconds}s)...")
            // 2) Capture the handshake to /BrucePCAP/handshakes/.
            dispatchCmd("wifi_handshake $bssid $channel $captureSeconds $ssid") { _, _ ->
                _hsFlow.value = _hsFlow.value.copy(step = "pull", message = "Looking for capture...")
                // 3) Find + pull the newest capture for this BSSID, then load it.
                pullLatestHandshake(bssid)
            }
        }
    }

    /**
     * Clientless PMKID flow for one AP: ask the device to capture a PMKID
     * (`wifi_pmkid <bssid> <ch> <secs>`), then reuse [pullLatestHandshake] to pull the
     * newest capture from /BrucePCAP/handshakes/ and load it. The pcap path extracts the
     * PMKID (RSN PMKID KDE) and loads it as the crack target. Progress shows in [hsFlow].
     */
    fun capturePmkidAndCrack(bssid: String, channel: Int, ssid: String, seconds: Int = 15) {
        if (client == null) {
            _hsFlow.value = HandshakeFlow(step = "failed", message = "Connect the device over USB first.")
            return
        }
        val label = ssid.ifEmpty { bssid }
        _hsFlow.value = HandshakeFlow(active = true, step = "capture", message = "Capturing PMKID from $label (${seconds}s)...", bssid = bssid, ssid = ssid)
        // Device writes a capture to /BrucePCAP/handshakes/ (same dir as handshakes).
        dispatchCmd("wifi_pmkid $bssid $channel $seconds") { _, _ ->
            _hsFlow.value = _hsFlow.value.copy(step = "pull", message = "Looking for PMKID capture...")
            pullLatestHandshake(bssid, ssid)
        }
    }

    /** List the handshakes dir, pick the newest .pcap/.cap for [bssid], pull and load it. */
    private fun pullLatestHandshake(bssid: String, ssidHint: String = "") {
        // Firmware names captures HS_{MAC}_{SSID}.pcap with an uppercase, separator-free MAC.
        val macKey = bssid.replace(":", "").replace("-", "").uppercase()
        listDir("/BrucePCAP/handshakes") { entries ->
            val caps = entries.filter { !it.isDir && (it.name.endsWith(".pcap", true) || it.name.endsWith(".cap", true)) }
            val matches = caps.filter { it.name.uppercase().contains(macKey) }
            // No timestamps in the listing; the name sorts highest for the most recent capture.
            val pick = (if (matches.isNotEmpty()) matches else caps).maxByOrNull { it.name }
            if (pick == null) {
                _hsFlow.value = _hsFlow.value.copy(active = false, step = "failed", message = "No handshake captured. Move closer and retry.")
            } else {
                _hsFlow.value = _hsFlow.value.copy(step = "pull", message = "Pulling ${pick.name}...")
                pullFile("/BrucePCAP/handshakes/${pick.name}", ssidHint) { loaded ->
                    _hsFlow.value = if (loaded) {
                        _hsFlow.value.copy(active = false, step = "ready", message = "Handshake loaded - ready to crack.")
                    } else {
                        _hsFlow.value.copy(active = false, step = "failed", message = "Capture pulled but no usable handshake. Retry.")
                    }
                }
            }
        }
    }

    /** Console: send a command and echo it; the captured output arrives via ACK. */
    fun sendConsole(cmd: String) {
        _console.value = (_console.value + "> $cmd").takeLast(500)
        dispatchCmd(cmd)
    }

    fun clearConsole() {
        _console.value = emptyList()
    }

    /** IR learn: capture a remote via `ir rx`; parse the .ir output into buttons. */
    fun learnIr() {
        dispatchCmd("ir rx") { _, out ->
            val parsed = IrParser.parse(out)
            if (parsed.isNotEmpty()) {
                _irLearned.value = parsed + _irLearned.value
                viewModelScope.launch(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    parsed.forEach { b ->
                        savedDao.insert(SavedItem(type = "ir", name = b.name, subtitle = b.protocol, payload = b.toCli(), timeMillis = now))
                    }
                }
            }
        }
    }

    /**
     * TV-B-Gone power-off sweep (ir_tvbgone [region]); region is "na" or "eu".
     * Fire + ACK; the ACK `out` is surfaced on [irTvBGoneStatus]. Authorized use only.
     */
    fun irTvBGone(region: String = "na") {
        if (client == null) { _irTvBGoneStatus.value = "Connect the device over USB first."; return }
        _irTvBGoneStatus.value = "TV-B-Gone ($region) running..."
        dispatchCmd("ir_tvbgone $region") { ok, out ->
            _irTvBGoneStatus.value = if (ok) out.ifBlank { "TV-B-Gone ($region) done." }
                else out.ifBlank { "TV-B-Gone ($region) failed." }
        }
    }

    /**
     * Arm the receiver once (ir_rx, ~10s). The decoded signal is parsed and held in
     * [irRxResult] so the user can review it and choose to save it with [saveLearnedIr].
     */
    fun irReceiveOnce() {
        if (client == null) { _irRxStatus.value = "Connect the device over USB first."; return }
        _irRxResult.value = null
        _irRxStatus.value = "Receiving - point a remote at the device and press a button (10s)..."
        dispatchCmd("ir_rx") { ok, out ->
            if (!ok) { _irRxStatus.value = out.ifBlank { "Receive failed." }; return@dispatchCmd }
            val parsed = IrParser.parse(out).firstOrNull()
            _irRxResult.value = parsed
            _irRxStatus.value = when {
                parsed != null -> "Received: ${parsed.name} (${if (parsed.raw) "raw" else parsed.protocol})"
                out.isNotBlank() -> out
                else -> "No signal decoded."
            }
        }
    }

    /** Save a received/decoded button into the learned-IR list and persist it for later replay. */
    fun saveLearnedIr(button: IrButton) {
        _irLearned.value = listOf(button) + _irLearned.value
        _irRxResult.value = null
        _irRxStatus.value = "Saved: ${button.name}"
        viewModelScope.launch(Dispatchers.IO) {
            savedDao.insert(SavedItem(
                type = "ir",
                name = button.name,
                subtitle = if (button.raw) "raw" else button.protocol,
                payload = button.toCli(),
                timeMillis = System.currentTimeMillis(),
            ))
        }
    }

    /** Transmit a raw IR frame via the fixed contract (ir_tx_raw <khz> <csv durations>). */
    fun irTxRaw(khz: Int, durations: String) {
        dispatchCmd("ir_tx_raw $khz $durations")
    }

    // --- Custom IR remote: a user-built grid of captured buttons, persisted on the phone ---
    private val _remoteKeys = MutableStateFlow<List<IrButton>>(emptyList())
    val remoteKeys: StateFlow<List<IrButton>> = _remoteKeys.asStateFlow()
    private val _remoteCapture = MutableStateFlow("")
    val remoteCapture: StateFlow<String> = _remoteCapture.asStateFlow()
    private val remotePrefs by lazy {
        getApplication<Application>().getSharedPreferences("ir_remote", android.content.Context.MODE_PRIVATE)
    }

    private fun loadRemoteKeys() {
        val json = remotePrefs.getString("keys", "[]") ?: "[]"
        val list = ArrayList<IrButton>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    IrButton(
                        name = o.optString("name"), protocol = o.optString("protocol"),
                        address = o.optString("address"), command = o.optString("command"),
                        raw = o.optBoolean("raw"), frequency = o.optInt("frequency", 38000),
                        data = o.optString("data"),
                    )
                )
            }
        } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "load remote keys failed", e) }
        _remoteKeys.value = list
    }

    private fun saveRemoteKeys() {
        val arr = org.json.JSONArray()
        _remoteKeys.value.forEach { b ->
            arr.put(org.json.JSONObject().apply {
                put("name", b.name); put("protocol", b.protocol); put("address", b.address)
                put("command", b.command); put("raw", b.raw); put("frequency", b.frequency); put("data", b.data)
            })
        }
        remotePrefs.edit().putString("keys", arr.toString()).apply()
    }

    /** Capture one IR signal (ir_rx) and add it to the custom remote under [label]. */
    fun captureRemoteKey(label: String) {
        if (client == null) { _remoteCapture.value = "Connect the device over USB first."; return }
        _remoteCapture.value = "Point a remote at the device and press the button (10s)..."
        dispatchCmd("ir_rx") { ok, out ->
            if (!ok) { _remoteCapture.value = out.ifBlank { "Capture failed." }; return@dispatchCmd }
            val parsed = IrParser.parse(out).firstOrNull()
            if (parsed == null) { _remoteCapture.value = "No signal decoded - try again."; return@dispatchCmd }
            val named = parsed.copy(name = label.trim().ifBlank { parsed.name })
            _remoteKeys.value = _remoteKeys.value + named
            saveRemoteKeys()
            _remoteCapture.value = "Saved: ${named.name}"
        }
    }

    /** Transmit a custom-remote button. */
    fun pressRemoteKey(b: IrButton) {
        if (client == null) { _remoteCapture.value = "Connect the device over USB first."; return }
        val (khz, csv) = b.toTxRawArgs()
        if (b.raw && csv.isNotBlank()) irTxRaw(khz, csv) else sendCommand(b.toCli())
        _remoteCapture.value = "Sent: ${b.name}"
    }

    fun deleteRemoteKey(index: Int) {
        _remoteKeys.value = _remoteKeys.value.filterIndexed { i, _ -> i != index }
        saveRemoteKeys()
    }

    fun renameRemoteKey(index: Int, name: String) {
        _remoteKeys.value = _remoteKeys.value.mapIndexed { i, b -> if (i == index) b.copy(name = name.trim().ifBlank { b.name }) else b }
        saveRemoteKeys()
    }

    // --- Melody composer: user-composed RTTTL songs, persisted and played via speaker_song ---
    private val _customSongs = MutableStateFlow<List<app.brucia.audio.Song>>(emptyList())
    val customSongs: StateFlow<List<app.brucia.audio.Song>> = _customSongs.asStateFlow()
    private val songPrefs by lazy {
        getApplication<Application>().getSharedPreferences("songs", android.content.Context.MODE_PRIVATE)
    }

    private fun loadCustomSongs() {
        val json = songPrefs.getString("songs", "[]") ?: "[]"
        val list = ArrayList<app.brucia.audio.Song>()
        try {
            val arr = org.json.JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(app.brucia.audio.Song(o.optString("name"), o.optString("rtttl")))
            }
        } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "load custom songs failed", e) }
        _customSongs.value = list
    }

    private fun saveCustomSongs() {
        val arr = org.json.JSONArray()
        _customSongs.value.forEach { s ->
            arr.put(org.json.JSONObject().apply { put("name", s.name); put("rtttl", s.rtttl) })
        }
        songPrefs.edit().putString("songs", arr.toString()).apply()
    }

    /** Save a composed melody (RTTTL) under [name]; survives app restarts. */
    fun saveCustomSong(name: String, rtttl: String) {
        val n = name.trim().ifBlank { "Song ${_customSongs.value.size + 1}" }
        _customSongs.value = _customSongs.value + app.brucia.audio.Song(n, rtttl)
        saveCustomSongs()
    }

    fun deleteCustomSong(index: Int) {
        _customSongs.value = _customSongs.value.filterIndexed { i, _ -> i != index }
        saveCustomSongs()
    }

    // --- Macros: phone-side automation. A macro is a saved list of device commands the phone runs
    //     in order, with a pause after each - chaining scan/capture/analyze steps the device's tiny
    //     on-board interpreter cannot. Persisted like the IR remote and custom songs.
    private val _macros = MutableStateFlow<List<app.brucia.automation.Macro>>(emptyList())
    val macros: StateFlow<List<app.brucia.automation.Macro>> = _macros.asStateFlow()
    private val _macroStatus = MutableStateFlow("")
    val macroStatus: StateFlow<String> = _macroStatus.asStateFlow()
    private val _macroRunning = MutableStateFlow(false)
    val macroRunning: StateFlow<Boolean> = _macroRunning.asStateFlow()
    private var macroJob: Job? = null
    private val macroPrefs by lazy {
        getApplication<Application>().getSharedPreferences("macros", android.content.Context.MODE_PRIVATE)
    }

    private fun loadMacros() {
        _macros.value = app.brucia.automation.Macro.listFromJson(macroPrefs.getString("macros", "[]") ?: "[]")
    }

    private fun saveMacros() {
        macroPrefs.edit().putString("macros", app.brucia.automation.Macro.listToJson(_macros.value)).apply()
    }

    /** Save (or replace by name) a macro. */
    fun saveMacro(macro: app.brucia.automation.Macro) {
        val others = _macros.value.filter { it.name != macro.name }
        _macros.value = others + macro
        saveMacros()
    }

    fun deleteMacro(index: Int) {
        _macros.value = _macros.value.filterIndexed { i, _ -> i != index }
        saveMacros()
    }

    /** Run a macro: each step's command in order, pausing [delayMs] after each. Cancellable. */
    fun runMacro(macro: app.brucia.automation.Macro) {
        if (client == null) { _macroStatus.value = "Connect the device over USB first."; return }
        if (_macroRunning.value) return
        // Set the guard SYNCHRONOUSLY (before launching) so a fast double-tap can't start two
        // concurrent macros whose command frames would interleave and corrupt the USB link.
        _macroRunning.value = true
        macroJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                macro.steps.forEachIndexed { i, step ->
                    ensureActive()
                    _macroStatus.value = "Step ${i + 1}/${macro.steps.size}: ${step.cmd}"
                    val (ok, out) = sendAwait(step.cmd)
                    _macroStatus.value = "Step ${i + 1}/${macro.steps.size}: ${step.cmd} -> ${if (ok) out.ifBlank { "ok" } else "failed: $out"}"
                    if (step.delayMs > 0) delay(step.delayMs.toLong())
                }
                _macroStatus.value = "Macro \"${macro.name}\" finished."
            } catch (_: Exception) {
                _macroStatus.value = "Macro \"${macro.name}\" stopped."
            } finally {
                _macroRunning.value = false
            }
        }
    }

    fun stopMacro() {
        macroJob?.cancel()
        macroJob = null
        _macroRunning.value = false
        _macroStatus.value = "Macro stopped."
    }

    // --- Saved Evil Portal HTML templates authored in-app (name + html), persisted. The ESP serves
    //     the captive page; the phone is the editor/preview the device could never offer.
    private val _savedPortals = MutableStateFlow<List<SavedPortal>>(emptyList())
    val savedPortals: StateFlow<List<SavedPortal>> = _savedPortals.asStateFlow()
    private val portalPrefs by lazy {
        getApplication<Application>().getSharedPreferences("portals", android.content.Context.MODE_PRIVATE)
    }

    private fun loadSavedPortals() {
        val out = ArrayList<SavedPortal>()
        try {
            val arr = org.json.JSONArray(portalPrefs.getString("portals", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(SavedPortal(o.optString("name"), o.optString("html")))
            }
        } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "load saved portals failed", e) }
        _savedPortals.value = out
    }

    private fun saveSavedPortals() {
        val arr = org.json.JSONArray()
        _savedPortals.value.forEach { p -> arr.put(org.json.JSONObject().apply { put("name", p.name); put("html", p.html) }) }
        portalPrefs.edit().putString("portals", arr.toString()).apply()
    }

    /** Save (replace by name) a custom captive-portal template. */
    fun saveCustomPortal(name: String, html: String) {
        val n = name.trim().ifBlank { "Template ${_savedPortals.value.size + 1}" }
        _savedPortals.value = _savedPortals.value.filter { it.name != n } + SavedPortal(n, html)
        saveSavedPortals()
    }

    fun deleteCustomPortal(index: Int) {
        _savedPortals.value = _savedPortals.value.filterIndexed { i, _ -> i != index }
        saveSavedPortals()
    }

    // --- RF place-fingerprint: snapshot the Wi-Fi/BLE emitters seen HERE (the ESP scans them) and
    //     persist them, so later you can detect what is NEW (possible surveillance) or MISSING.
    private val _rfFingerprints = MutableStateFlow<List<RfFingerprint>>(emptyList())
    val rfFingerprints: StateFlow<List<RfFingerprint>> = _rfFingerprints.asStateFlow()
    private val fpPrefs by lazy {
        getApplication<Application>().getSharedPreferences("rf_fingerprints", android.content.Context.MODE_PRIVATE)
    }

    private fun loadFingerprints() {
        val out = ArrayList<RfFingerprint>()
        try {
            val arr = org.json.JSONArray(fpPrefs.getString("fps", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                fun strList(key: String): List<String> {
                    val a = o.optJSONArray(key) ?: return emptyList()
                    return (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
                }
                out.add(RfFingerprint(o.optString("name"), o.optLong("at"), strList("bssids"), strList("bleMacs")))
            }
        } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "load fingerprints failed", e) }
        _rfFingerprints.value = out
    }

    private fun saveFingerprints() {
        val arr = org.json.JSONArray()
        _rfFingerprints.value.forEach { fp ->
            arr.put(org.json.JSONObject().apply {
                put("name", fp.name); put("at", fp.at)
                put("bssids", org.json.JSONArray(fp.bssids)); put("bleMacs", org.json.JSONArray(fp.bleMacs))
            })
        }
        fpPrefs.edit().putString("fps", arr.toString()).apply()
    }

    /** Snapshot the currently-seen Wi-Fi BSSIDs + BLE MACs as a named place-fingerprint. */
    fun captureFingerprint(name: String) {
        val bssids = aps.value.map { it.bssid }.filter { it.isNotBlank() }.distinct()
        val macs = ble.value.map { it.mac }.filter { it.isNotBlank() }.distinct()
        val n = name.trim().ifBlank { "Place ${_rfFingerprints.value.size + 1}" }
        _rfFingerprints.value = _rfFingerprints.value.filter { it.name != n } +
            RfFingerprint(n, System.currentTimeMillis(), bssids, macs)
        saveFingerprints()
    }

    fun deleteFingerprint(index: Int) {
        _rfFingerprints.value = _rfFingerprints.value.filterIndexed { i, _ -> i != index }
        saveFingerprints()
    }

    // --- Unified RF presence timeline: a persistent history of every Wi-Fi/BLE device the ESP has
    //     seen, with first/last-seen and a hit count, merged across sessions. The screen owns the
    //     scans; this just accumulates + persists while logging is on.
    private val _sightings = MutableStateFlow<List<RfSighting>>(emptyList())
    val sightings: StateFlow<List<RfSighting>> = _sightings.asStateFlow()
    private val _timelineActive = MutableStateFlow(false)
    val timelineActive: StateFlow<Boolean> = _timelineActive.asStateFlow()
    private val sightingMap = LinkedHashMap<String, RfSighting>()
    private var timelineJob: Job? = null
    private val timelinePrefs by lazy {
        getApplication<Application>().getSharedPreferences("rf_timeline", android.content.Context.MODE_PRIVATE)
    }
    private val TIMELINE_CAP = 1000

    private fun loadTimeline() {
        sightingMap.clear()
        try {
            val arr = org.json.JSONArray(timelinePrefs.getString("sightings", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("key")
                if (k.isBlank()) continue
                sightingMap[k] = RfSighting(k, o.optString("type"), o.optString("label"),
                    o.optLong("firstSeen"), o.optLong("lastSeen"), o.optInt("count", 1))
            }
        } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "load timeline failed", e) }
        publishSightings()
    }

    private fun publishSightings() {
        _sightings.value = sightingMap.values.sortedByDescending { it.lastSeen }
    }

    private fun saveTimeline() {
        // Cap to the most-recently-seen entries so prefs don't grow without bound.
        val kept = sightingMap.values.sortedByDescending { it.lastSeen }.take(TIMELINE_CAP)
        val arr = org.json.JSONArray()
        kept.forEach { s ->
            arr.put(org.json.JSONObject().apply {
                put("key", s.key); put("type", s.type); put("label", s.label)
                put("firstSeen", s.firstSeen); put("lastSeen", s.lastSeen); put("count", s.count)
            })
        }
        timelinePrefs.edit().putString("sightings", arr.toString()).apply()
    }

    private fun mergeSighting(key: String, type: String, label: String, now: Long) {
        val ex = sightingMap[key]
        sightingMap[key] = if (ex == null) RfSighting(key, type, label, now, now, 1)
        else ex.copy(lastSeen = now, count = ex.count + 1, label = label.ifBlank { ex.label })
    }

    /** Start accumulating sightings from the live Wi-Fi/BLE scans (the screen starts the scans). */
    fun startTimeline() {
        if (_timelineActive.value) return
        loadTimeline()
        _timelineActive.value = true
        timelineJob = viewModelScope.launch {
            var ticks = 0
            while (true) {
                ensureActive()
                val now = System.currentTimeMillis()
                aps.value.forEach { if (it.bssid.isNotBlank()) mergeSighting("wifi:" + it.bssid, "wifi", it.ssid.ifBlank { it.bssid }, now) }
                ble.value.forEach { if (it.mac.isNotBlank()) mergeSighting("ble:" + it.mac, "ble", it.name.ifBlank { it.mac }, now) }
                publishSightings()
                if (++ticks % 10 == 0) saveTimeline() // persist roughly every 20s
                delay(2000)
            }
        }
    }

    fun stopTimeline() {
        timelineJob?.cancel()
        timelineJob = null
        _timelineActive.value = false
        saveTimeline()
    }

    fun clearTimeline() {
        sightingMap.clear()
        publishSightings()
        saveTimeline()
    }

    // --- Geofenced capture/replay: pin a saved RF/IR signal to the current GPS spot, then replay it
    //     (manually) when you return - the ESP transmits, the phone provides GPS + proximity. The
    //     compelling case is sub-GHz (a gate/garage on arrival); IR works too.
    private val _geoSignals = MutableStateFlow<List<GeoSignal>>(emptyList())
    val geoSignals: StateFlow<List<GeoSignal>> = _geoSignals.asStateFlow()
    private val _geoStatus = MutableStateFlow("")
    val geoStatus: StateFlow<String> = _geoStatus.asStateFlow()
    private val geoPrefs by lazy {
        getApplication<Application>().getSharedPreferences("geo_signals", android.content.Context.MODE_PRIVATE)
    }

    private fun loadGeoSignals() {
        val out = ArrayList<GeoSignal>()
        try {
            val arr = org.json.JSONArray(geoPrefs.getString("geo", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(GeoSignal(o.optString("name"), o.optString("type"), o.optString("payload"),
                    o.optDouble("lat"), o.optDouble("lon"), o.optLong("at")))
            }
        } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "load geo signals failed", e) }
        _geoSignals.value = out
    }

    private fun saveGeoSignals() {
        val arr = org.json.JSONArray()
        _geoSignals.value.forEach { g ->
            arr.put(org.json.JSONObject().apply {
                put("name", g.name); put("type", g.type); put("payload", g.payload)
                put("lat", g.lat); put("lon", g.lon); put("at", g.at)
            })
        }
        geoPrefs.edit().putString("geo", arr.toString()).apply()
    }

    /** Pin a saved signal to the current GPS location. Fails if no GPS fix yet. */
    fun addGeoSignal(name: String, type: String, payload: String) {
        val loc = _currentLoc.value
        if (loc == null) { _geoStatus.value = "No GPS fix yet - wait for location."; return }
        if (payload.isBlank()) { _geoStatus.value = "Nothing to pin."; return }
        val n = name.trim().ifBlank { "Signal ${_geoSignals.value.size + 1}" }
        _geoSignals.value = _geoSignals.value + GeoSignal(n, type, payload, loc.first, loc.second, System.currentTimeMillis())
        saveGeoSignals()
        _geoStatus.value = "Pinned \"$n\" here."
    }

    fun deleteGeoSignal(index: Int) {
        _geoSignals.value = _geoSignals.value.filterIndexed { i, _ -> i != index }
        saveGeoSignals()
    }

    /** Replay a geo-signal's command now (the device transmits). Authorized use only. */
    fun replayGeoSignal(g: GeoSignal) {
        if (client == null) { _geoStatus.value = "Connect the device over USB first."; return }
        if (g.payload.startsWith("rf tx")) sendCommand(g.payload) else sendConsole(g.payload)
        _geoStatus.value = "Replayed \"${g.name}\"."
    }

    /** Send a command and route its captured output to a one-shot callback. */
    fun runWithResult(cmd: String, cb: (Boolean, String) -> Unit) {
        _console.value = (_console.value + "> $cmd").takeLast(500)
        dispatchCmd(cmd, cb)
    }

    // --- NFC / RFID ---
    fun nfcRead() = runWithResult("rfid read") { _, out ->
        _lastNfc.value = out
        val uid = CliParse.field(out, "UID")
        if (!uid.isNullOrEmpty()) {
            val type = CliParse.field(out, "Type") ?: ""
            viewModelScope.launch(Dispatchers.IO) {
                savedDao.insert(
                    SavedItem(type = "nfc", name = uid, subtitle = type, payload = out, timeMillis = System.currentTimeMillis())
                )
            }
        }
    }

    fun nfcEmulate() = sendConsole("rfid emulate")
    fun nfcClone() = sendConsole("rfid clone")
    fun nfcWrite() = sendConsole("rfid write")
    fun nfcErase() = sendConsole("rfid erase")
    fun nfcInfo() = runWithResult("rfid info") { _, out -> _lastNfc.value = out }
    fun nfcSave() = sendConsole("rfid save brucia_${System.currentTimeMillis()}")
    fun nfcNdef() = runWithResult("rfid ndef") { _, out -> _lastNfc.value = out }

    // --- Advanced NFC (fixed contract; firmware built in parallel) ---

    /**
     * Push an NDEF record to a nearby reader/phone on tap: nfc_ndef_push <url|text|contact>
     * <payload>. For a contact, payload is "name,phone" or a raw vCard. Fire + ACK; the result
     * is reported on the shared [nfcNdefStatus] line. This is the single entry point used by
     * both the NFC screen's push card and the Spam hub's NFC tile, so the two can never drift.
     */
    fun nfcNdefPush(type: String, payload: String) {
        if (client == null) { _nfcNdefStatus.value = "Connect the device over USB first."; return }
        val p = payload.trim()
        if (p.isEmpty()) { _nfcNdefStatus.value = "Enter a payload first."; return }
        _nfcNdefStatus.value = "Pushing NDEF ($type) - tap a device..."
        dispatchCmd("nfc_ndef_push $type $p") { ok, out ->
            _nfcNdefStatus.value = if (ok) out.ifBlank { "NDEF pushed." } else out.ifBlank { "NDEF push failed." }
        }
    }

    /** Read an EMV bank card (masked / read-only): nfc_emv_read -> masked PAN/expiry/scheme. */
    fun nfcEmvRead() {
        if (client == null) { _nfcEmvStatus.value = "Connect the device over USB first."; return }
        _nfcEmvStatus.value = "Reading card - hold it on the reader..."
        dispatchCmd("nfc_emv_read") { ok, out ->
            _nfcEmvStatus.value = if (ok) out.ifBlank { "No card data." } else out.ifBlank { "EMV read failed." }
        }
    }

    /** Write a UID onto a magic / UID-writable card: nfc_write_uid <hexuid>. */
    fun nfcWriteUid(hex: String) {
        if (client == null) { _nfcUidStatus.value = "Connect the device over USB first."; return }
        val h = hex.trim().replace(" ", "")
        if (h.isEmpty()) { _nfcUidStatus.value = "Enter a hex UID first."; return }
        _nfcUidStatus.value = "Writing UID $h..."
        dispatchCmd("nfc_write_uid $h") { ok, out ->
            _nfcUidStatus.value = if (ok) out.ifBlank { "UID written." } else out.ifBlank { "Write failed." }
        }
    }

    /** Amiibo helper: nfc_amiibo <action>. Shows whatever the firmware ACKs back. */
    fun nfcAmiibo(action: String) = runNfcAdv("nfc_amiibo $action", "Amiibo ($action)")
    /** MIFARE nested key-recovery attack: mifare_nested. */
    fun mifareNested() = runNfcAdv("mifare_nested", "Nested attack")
    /** MIFARE mfkey32 key-recovery attack: mifare_mfkey32. */
    fun mifareMfkey32() = runNfcAdv("mifare_mfkey32", "mfkey32 attack")
    /** SRIX / ST25TB (ISO14443B) read: srix_read [secs]. Reports the tag JSON or a timeout. */
    fun srixRead(secs: Int = 8) = runNfcAdv("srix_read $secs", "SRIX read")
    /** 125 kHz LF read: rfid125_read [secs]. Only boards with the UART LF reader return a tag. */
    fun rfid125Read(secs: Int = 8) = runNfcAdv("rfid125_read $secs", "125kHz read")

    /** Simple command+status tile helper: send [cmd], report its ACK out on [nfcAdvStatus]. */
    private fun runNfcAdv(cmd: String, label: String) {
        if (client == null) { _nfcAdvStatus.value = "Connect the device over USB first."; return }
        _nfcAdvStatus.value = "$label running..."
        dispatchCmd(cmd) { ok, out ->
            _nfcAdvStatus.value = if (ok) out.ifBlank { "$label done." } else out.ifBlank { "$label failed." }
        }
    }

    // --- MIFARE key-dictionary attack (bundled defaults + optional user keys) ---
    /** Import extra MIFARE keys from a text file (one 12-hex key per line). */
    fun importMifareKeys(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)!!
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                _mifareUserKeys.value = (_mifareUserKeys.value + NfcSecurity.parseKeys(text)).distinct()
            } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "import NFC keys failed", e) }
        }
    }

    fun clearMifareUserKeys() { _mifareUserKeys.value = emptyList() }
    fun resetMifareAttack() { _mifare.value = MifareAttackState() }

    /**
     * Run the MIFARE key dictionary against the presented card: send the bundled default
     * keys plus any user-imported keys to the device in batches of ~48 via
     * `mifare_keycheck <csv>`, accumulating the found[{sector,type,key}] from each ACK.
     */
    fun runMifareKeyCheck() {
        if (client == null) {
            _mifare.value = MifareAttackState(message = "Connect the device over USB first.")
            return
        }
        val dict = (NfcSecurity.loadKeyDictionary(getApplication()) + _mifareUserKeys.value).distinct()
        if (dict.isEmpty()) {
            _mifare.value = MifareAttackState(message = "No keys in dictionary.")
            return
        }
        val batches = dict.chunked(48)
        _mifare.value = MifareAttackState(running = true, batch = 0, totalBatches = batches.size, message = "Starting (${dict.size} keys)...")
        runMifareBatch(batches, 0, LinkedHashMap())
    }

    private fun runMifareBatch(batches: List<List<String>>, index: Int, acc: LinkedHashMap<String, MifareFound>) {
        if (index >= batches.size) {
            _mifare.value = _mifare.value.copy(
                running = false,
                found = acc.values.toList(),
                message = if (acc.isEmpty()) "No keys recovered." else "Done - ${acc.size} key(s) recovered.",
            )
            return
        }
        _mifare.value = _mifare.value.copy(running = true, batch = index + 1, message = "Testing batch ${index + 1}/${batches.size}...")
        dispatchCmdJson("mifare_keycheck ${batches[index].joinToString(",")}") { _, d ->
            val arr = d?.optJSONArray("found")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sector = o.optInt("sector", -1)
                    val type = o.optString("type")
                    val key = o.optString("key")
                    acc["$sector-$type"] = MifareFound(sector, type, key)
                }
            }
            _mifare.value = _mifare.value.copy(found = acc.values.toList())
            runMifareBatch(batches, index + 1, acc)
        }
    }

    // --- Sub-GHz capture / replay via the CLI (device decodes; phone stores) ---
    fun rfCapture(freqHz: Long = 433_920_000L) = runWithResult("rf rx $freqHz") { _, out ->
        _lastRf.value = out
        val replay = CliParse.rfReplayCommand(out)
        val key = CliParse.field(out, "Key") ?: CliParse.field(out, "key") ?: "signal"
        viewModelScope.launch(Dispatchers.IO) {
            savedDao.insert(
                SavedItem(
                    type = "rf",
                    name = key,
                    subtitle = CliParse.field(out, "Protocol") ?: "",
                    payload = replay ?: out,
                    timeMillis = System.currentTimeMillis(),
                )
            )
        }
    }

    fun replaySaved(item: SavedItem) {
        if (item.payload.startsWith("rf tx")) sendCommand(item.payload) else sendConsole(item.payload)
    }

    fun deleteSaved(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { savedDao.delete(id) }
    }

    private fun updateStreamState(stream: String, on: Boolean) {
        when (stream) {
            "wifi.ap" -> _wardriving.value = on
            "wifi.sta" -> _sniffing.value = on
            "wifi.probe" -> _probing.value = on
            "wifi.monitor" -> _monitoring.value = on
            "subghz.spectrum" -> _spectrumActive.value = on
            "subghz.raw" -> if (!on) stopRfCapture()
            "mic.pcm" -> {
                _micPcmActive.value = on
                if (!on) _recording.value = false
            }
        }
    }

    /** Gate every entry point, including composite screens, then correlate the SUB result. */
    private fun setStream(stream: String, on: Boolean, channel: Int? = null, bssid: String? = null): Boolean {
        val c = client
        if (c == null || (on && _device.value?.caps?.allowsStream(stream) != true)) {
            updateStreamState(stream, false)
            return false
        }
        // Publish the request before scheduling IO. A fast failure must not be
        // overwritten later by an optimistic running=true assignment in a caller.
        updateStreamState(stream, on)
        val id = c.nextId()
        pendingStreamIds[stream] = id
        if (on) activeStreams.add(stream) else activeStreams.remove(stream)
        refreshConnectionNotification()
        viewModelScope.launch(Dispatchers.IO) {
            val logId = logCmdStart("SUB $stream ${if (on) "on" else "off"}")
            pendingAcks[id] = { ok, out ->
                logCmdDone(logId, ok, out)
                if (pendingStreamIds.remove(stream, id) && !ok) {
                    activeStreams.remove(stream)
                    updateStreamState(stream, false)
                    if (stream == "mic.pcm") _micPcmStatus.value = out.ifBlank { "Microphone stream failed" }
                    refreshConnectionNotification()
                }
            }
            try {
                c.sub(stream, on, channel, bssid, id)
                if (transport?.isOpen != true) pendingAcks.remove(id)?.invoke(false, "USB write failed")
            } catch (e: Exception) {
                pendingAcks.remove(id)?.invoke(false, e.message ?: "Unable to set stream")
            }
            delay(8000)
            pendingAcks.remove(id)?.invoke(false, "No stream acknowledgement from the device")
        }
        return true
    }

    // --- "Monitor my network" (raw 802.11 frames via the "wifi.monitor" stream) ---
    /**
     * Start monitoring a network the user controls. [channel] (1..13) and/or [bssid] narrow
     * the capture; both null captures on the device's current channel. Clears any prior frames.
     */
    fun startMonitor(channel: Int?, bssid: String?) {
        monitorIndex = 0
        _monitorFrames.value = emptyList()
        setStream("wifi.monitor", true, channel, bssid)
    }

    fun stopMonitor() {
        setStream("wifi.monitor", false, null, null)
        _monitoring.value = false
    }

    fun clearMonitor() {
        monitorIndex = 0
        _monitorFrames.value = emptyList()
    }

    /**
     * Write the captured frames to a real pcap (linktype 105, IEEE 802.11) in the app's
     * external files dir, or null if nothing has been captured. Open it in Wireshark and add
     * the network PSK as a "wpa-pwd" decryption key to view decrypted payloads.
     */
    fun exportMonitorPcap(): java.io.File? {
        val snapshot = _monitorFrames.value
        if (snapshot.isEmpty()) return null
        val dir = getApplication<Application>().getExternalFilesDir(null)
        val file = java.io.File(dir, "brucia_monitor_${System.currentTimeMillis()}.pcap")
        file.outputStream().use { os ->
            PcapWriter.write(os, snapshot.map { it.tsMicros to it.raw })
        }
        return file
    }

    /**
     * PMK = PBKDF2(PSK, SSID) as hex, derived on-device (reusing [WpaCracker]) purely as a
     * convenience the user can drop into Wireshark as a "wpa-psk" key. Null if the PSK length
     * or SSID is invalid. No frame decryption is performed on-device.
     */
    fun derivePmkHex(psk: String, ssid: String): String? {
        if (psk.length !in 8..63 || ssid.isEmpty()) return null
        return try {
            WpaCracker.pmk(psk, ssid).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun setWardriving(on: Boolean) {
        setStream("wifi.ap", on)
    }

    fun setSubghz(on: Boolean) {
        setStream("subghz.raw", on)
    }

    /** Start recording subghz.raw events to an in-memory buffer, ensuring the stream is on. */
    fun startRfCapture() {
        synchronized(rfCaptureRows) { rfCaptureRows.clear() }
        _rfCapture.value = RfCaptureState(true, 0)
        setStream("subghz.raw", true)
    }

    /** Stop recording. Keeps the captured rows so they can still be exported. */
    fun stopRfCapture() {
        _rfCapture.value = _rfCapture.value.copy(capturing = false)
    }

    private fun appendRfCaptureRow(row: RfCaptureRow) {
        val count = synchronized(rfCaptureRows) {
            if (rfCaptureRows.size >= MAX_RF_CAPTURE_ROWS) return
            rfCaptureRows.add(row)
            rfCaptureRows.size
        }
        _rfCapture.value = _rfCapture.value.copy(samples = count)
    }

    /**
     * Write the captured rows to a CSV in the app's external files dir and return the file,
     * or null if nothing has been captured yet.
     */
    fun exportRfCsv(): java.io.File? {
        val snapshot = synchronized(rfCaptureRows) { if (rfCaptureRows.isEmpty()) return null; rfCaptureRows.toList() }
        val dir = getApplication<Application>().getExternalFilesDir(null)
        val file = java.io.File(dir, "brucia_subghz_${System.currentTimeMillis()}.csv")
        file.writeText(RfCaptureCsv.build(snapshot))
        return file
    }

    fun setBleScan(on: Boolean) {
        setStream("ble.scan", on)
    }

    // --- BLE GATT explorer + BadBLE (ble_gatt_connect/read, ble_badble) ---
    // ble_gatt_connect <mac> streams EVENT ble.gatt (one {svc, chr, props} per characteristic)
    // which handle() folds into _gattTree; its ACK reports connect success/failure.
    private val _gattTree = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val gattServices: StateFlow<List<GattService>> =
        _gattTree.map { tree ->
            tree.entries.map { (svc, chars) ->
                GattService(svc, chars.entries.map { GattCharacteristic(it.key, it.value) })
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _gattMac = MutableStateFlow<String?>(null)
    val gattMac: StateFlow<String?> = _gattMac.asStateFlow()
    private val _gattStatus = MutableStateFlow("")
    val gattStatus: StateFlow<String> = _gattStatus.asStateFlow()
    private val _gattConnecting = MutableStateFlow(false)
    val gattConnecting: StateFlow<Boolean> = _gattConnecting.asStateFlow()
    // Per-characteristic read values, keyed by characteristic UUID.
    private val _gattReads = MutableStateFlow<Map<String, String>>(emptyMap())
    val gattReads: StateFlow<Map<String, String>> = _gattReads.asStateFlow()
    private val _badbleStatus = MutableStateFlow("")
    val badbleStatus: StateFlow<String> = _badbleStatus.asStateFlow()

    /**
     * Connect to [mac] over GATT and discover its services. Discovered characteristics arrive as
     * EVENT ble.gatt and build up [gattServices]; the command ACK reports connect-failed on
     * [gattStatus]. Clears any previous exploration state first.
     */
    fun gattConnect(mac: String) {
        if (client == null) { _gattStatus.value = "Connect the device over USB first."; return }
        _gattMac.value = mac
        _gattTree.value = emptyMap()
        _gattReads.value = emptyMap()
        _gattConnecting.value = true
        _gattStatus.value = "Connecting to $mac..."
        dispatchCmd("ble_gatt_connect $mac") { ok, out ->
            _gattConnecting.value = false
            _gattStatus.value =
                if (ok) out.ifBlank { "Connected - services below." }
                else out.ifBlank { "Connect failed." }
        }
    }

    /** Forget the current GATT exploration and clear its state (used on screen leave). */
    fun gattStop() {
        _gattConnecting.value = false
        _gattMac.value = null
        _gattTree.value = emptyMap()
        _gattReads.value = emptyMap()
        _gattStatus.value = ""
    }

    /** Read one characteristic (ble_gatt_read <mac> <uuid> -> ACK {ok,out}); result shown per-char. */
    fun gattRead(mac: String, uuid: String) {
        if (client == null) { _gattStatus.value = "Connect the device over USB first."; return }
        _gattReads.value = _gattReads.value + (uuid to "reading...")
        dispatchCmd("ble_gatt_read $mac $uuid") { ok, out ->
            _gattReads.value = _gattReads.value +
                (uuid to if (ok) out.ifBlank { "(empty)" } else "read failed: ${out.ifBlank { "error" }}")
        }
    }

    /**
     * Run a DuckyScript over BLE HID (ble_badble <script> -> ACK {ok,out}). This is the real
     * device-execution path the USB-disabled BadUSB screen points to. Authorized use only.
     */
    fun bleBadble(script: String) {
        if (client == null) { _badbleStatus.value = "Connect the device over USB first."; return }
        if (script.isBlank()) { _badbleStatus.value = "Script is empty."; return }
        _badbleStatus.value = "Running over BLE..."
        dispatchCmd("ble_badble $script") { ok, out ->
            _badbleStatus.value = if (ok) out.ifBlank { "Done." } else out.ifBlank { "Failed." }
        }
    }

    // --- Station sniffer (nearby Wi-Fi clients via the "wifi.sta" stream) ---
    fun startStationSniff() {
        setStream("wifi.sta", true)
    }

    fun stopStationSniff() {
        setStream("wifi.sta", false)
        _sniffing.value = false
    }

    fun clearStations() {
        _stations.value = emptyMap()
    }

    /** Write the sniffed stations to a CSV (reused by the share plumbing), or null if empty. */
    fun exportStationsCsv(): java.io.File? {
        val snapshot = stations.value
        if (snapshot.isEmpty()) return null
        val sb = StringBuilder("mac,bssid,rssi,ch,count,last_seen_ms\n")
        for (s in snapshot) {
            sb.append(s.mac).append(',').append(s.bssid).append(',').append(s.rssi).append(',')
                .append(s.ch).append(',').append(s.count).append(',').append(s.lastSeen).append('\n')
        }
        val dir = getApplication<Application>().getExternalFilesDir(null)
        val file = java.io.File(dir, "brucia_stations_${System.currentTimeMillis()}.csv")
        file.writeText(sb.toString())
        return file
    }

    fun clearBle() {
        _ble.value = emptyMap()
    }

    // --- Sub-GHz spectrum waterfall (the "subghz.spectrum" stream) ---
    /** Start the spectrum sweep stream; sweeps arrive via EVENT subghz.spectrum. */
    fun startSpectrum() {
        spectrumPts.clear()
        lastSpectrumFreq = Long.MIN_VALUE
        _spectrum.value = null
        _spectrumHistory.value = emptyList()
        setStream("subghz.spectrum", true)
    }

    /** Stop the spectrum stream; flush any half-collected per-point sweep so it is not lost. */
    fun stopSpectrum() {
        if (spectrumPts.isNotEmpty()) flushSpectrumPoints()
        setStream("subghz.spectrum", false)
        _spectrumActive.value = false
    }

    fun clearSpectrum() {
        spectrumPts.clear()
        lastSpectrumFreq = Long.MIN_VALUE
        _spectrum.value = null
        _spectrumHistory.value = emptyList()
    }

    /** Assemble the accumulated {freq, rssi} points (sorted by frequency) into one sweep. */
    private fun flushSpectrumPoints() {
        if (spectrumPts.isEmpty()) return
        val sorted = spectrumPts.entries.sortedBy { it.key }
        val freqs = LongArray(sorted.size) { sorted[it].key }
        val rssi = IntArray(sorted.size) { sorted[it].value }
        spectrumPts.clear()
        lastSpectrumFreq = Long.MIN_VALUE
        pushSpectrumSweep(SpectrumSweep(freqs, rssi, System.currentTimeMillis()))
    }

    private fun pushSpectrumSweep(sweep: SpectrumSweep) {
        if (sweep.rssi.isEmpty()) return
        _spectrum.value = sweep
        _spectrumHistory.value = (_spectrumHistory.value + sweep).takeLast(MAX_SPECTRUM_ROWS)
    }

    // --- Wi-Fi probe-request profiling (the "wifi.probe" stream) ---
    fun startProbeSniff() {
        setStream("wifi.probe", true)
    }

    fun stopProbeSniff() {
        setStream("wifi.probe", false)
        _probing.value = false
    }

    fun clearProbes() {
        _probes.value = emptyMap()
    }

    /** Write the deduped probe requests to a CSV (reused by the share plumbing), or null if empty. */
    fun exportProbesCsv(): java.io.File? {
        val snapshot = probes.value
        if (snapshot.isEmpty()) return null
        val sb = StringBuilder("client_mac,ssid,rssi,count,last_seen_ms\n")
        for (p in snapshot) {
            val ssid = p.ssid.replace(",", " ")
            sb.append(p.mac).append(',').append(ssid).append(',').append(p.rssi).append(',')
                .append(p.count).append(',').append(p.lastSeen).append('\n')
        }
        val dir = getApplication<Application>().getExternalFilesDir(null)
        val file = java.io.File(dir, "brucia_probes_${System.currentTimeMillis()}.csv")
        file.writeText(sb.toString())
        return file
    }

    /** Google Fast Pair (Android) popups; delegates to [startBleSpam] ("android"). */
    fun bleFastPairSpam(copies: Int = DEFAULT_SPAM_COPIES) = startBleSpam("android", copies)

    // --- NRF24 / 2.4GHz (scanner, ESB sniffer, MouseJack) ---
    // The device runs each nrf24_* command for <secs> and streams EVENTs while it runs; the
    // ACK marks completion (ok:false "no NRF24 module" when the radio is absent). Stop is a
    // local flag only (the device finishes its window on its own).
    private val _nrf24Channels = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val nrf24Channels: StateFlow<List<Nrf24Channel>> =
        _nrf24Channels.map { m -> m.entries.sortedBy { it.key }.map { Nrf24Channel(it.key, it.value) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _nrf24Devices = MutableStateFlow<Map<String, Nrf24Device>>(emptyMap())
    val nrf24Devices: StateFlow<List<Nrf24Device>> =
        _nrf24Devices.map { m -> m.values.sortedByDescending { it.lastSeen } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Mousejackable devices discovered during a MouseJack run (EVENT nrf24.mj).
    private val _nrf24MjDevices = MutableStateFlow<Map<String, Nrf24MjDevice>>(emptyMap())
    val nrf24MjDevices: StateFlow<List<Nrf24MjDevice>> =
        _nrf24MjDevices.map { m -> m.values.sortedByDescending { it.lastSeen } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _nrf24Scanning = MutableStateFlow(false)
    val nrf24Scanning: StateFlow<Boolean> = _nrf24Scanning.asStateFlow()
    private val _nrf24Sniffing = MutableStateFlow(false)
    val nrf24Sniffing: StateFlow<Boolean> = _nrf24Sniffing.asStateFlow()
    private val _nrf24Status = MutableStateFlow("")
    val nrf24Status: StateFlow<String> = _nrf24Status.asStateFlow()

    // Jammer and MouseJack each run for a fixed window and ACK on completion; their status
    // lines are separate so the two cards never clobber each other.
    private val _nrf24Jamming = MutableStateFlow(false)
    val nrf24Jamming: StateFlow<Boolean> = _nrf24Jamming.asStateFlow()
    private val _nrf24JammerStatus = MutableStateFlow("")
    val nrf24JammerStatus: StateFlow<String> = _nrf24JammerStatus.asStateFlow()
    private val _nrf24MjRunning = MutableStateFlow(false)
    val nrf24MjRunning: StateFlow<Boolean> = _nrf24MjRunning.asStateFlow()
    private val _nrf24MjStatus = MutableStateFlow("")
    val nrf24MjStatus: StateFlow<String> = _nrf24MjStatus.asStateFlow()

    // Each NRF24 op runs a bounded device command that blocks the single-threaded link for its full
    // window, so (like the spam loops) they run as cancellable loops of ~2s bursts; these hold the
    // Jobs that Stop/disconnect cancel.
    private var nrf24ScanJob: Job? = null
    private var nrf24SniffJob: Job? = null
    private var nrf24JammerJob: Job? = null
    private var nrf24MjJob: Job? = null

    private fun nrf24ErrMessage(out: String) = out.ifBlank { "No NRF24 module on this device." }

    /**
     * Start the 2.4GHz channel scanner as a cancellable loop of ~2s `nrf24_scan` bursts; per-channel
     * activity arrives via EVENT nrf24.scan between bursts. Replaces the blocking one-shot so Stop
     * aborts within ~2s instead of only flipping a flag while the device kept running. [seconds] is
     * kept for call-site compatibility; each burst is a fixed ~2s window.
     */
    fun startNrf24Scan(seconds: Int = 20) {
        if (client == null) { _nrf24Status.value = "Connect the device over USB first."; return }
        stopNrf24Scan(silent = true)
        if (_micPcmActive.value) stopMicPcm()
        _nrf24Channels.value = emptyMap()
        _nrf24Status.value = "Scanning 2.4GHz... (Stop to end)"
        launchBurstLoop(
            getJob = { nrf24ScanJob },
            setJob = { nrf24ScanJob = it },
            active = _nrf24Scanning,
            cmd = { "nrf24_scan $SPAM_BURST_SECS" },
            onReject = { _nrf24Status.value = nrf24ErrMessage(it) },
            onStopped = { if (_nrf24Status.value.startsWith("Scanning")) _nrf24Status.value = "Scan stopped." },
        )
    }

    fun stopNrf24Scan(silent: Boolean = false) {
        nrf24ScanJob?.cancel()
        nrf24ScanJob = null
        _nrf24Scanning.value = false
        if (!silent && _nrf24Status.value.startsWith("Scanning")) _nrf24Status.value = "Scan stopped."
    }

    /**
     * Start the ESB sniffer as a cancellable loop of ~2s `nrf24_sniff` bursts; devices arrive via
     * EVENT nrf24.dev between bursts. Replaces the blocking one-shot so Stop aborts within ~2s
     * instead of only flipping a flag while the device kept running. [seconds] is kept for call-site
     * compatibility; each burst is a fixed ~2s window.
     */
    fun startNrf24Sniff(seconds: Int = 20) {
        if (client == null) { _nrf24Status.value = "Connect the device over USB first."; return }
        stopNrf24Sniff(silent = true)
        if (_micPcmActive.value) stopMicPcm()
        _nrf24Status.value = "Sniffing ESB devices... (Stop to end)"
        launchBurstLoop(
            getJob = { nrf24SniffJob },
            setJob = { nrf24SniffJob = it },
            active = _nrf24Sniffing,
            cmd = { "nrf24_sniff $SPAM_BURST_SECS" },
            onReject = { _nrf24Status.value = nrf24ErrMessage(it) },
            onStopped = { if (_nrf24Status.value.startsWith("Sniffing")) _nrf24Status.value = "Sniff stopped." },
        )
    }

    fun stopNrf24Sniff(silent: Boolean = false) {
        nrf24SniffJob?.cancel()
        nrf24SniffJob = null
        _nrf24Sniffing.value = false
        if (!silent && _nrf24Status.value.startsWith("Sniffing")) _nrf24Status.value = "Sniff stopped."
    }

    fun clearNrf24Devices() { _nrf24Devices.value = emptyMap() }

    fun clearNrf24MjDevices() { _nrf24MjDevices.value = emptyMap() }

    /**
     * Start the 2.4GHz jammer as a cancellable loop of ~2s `nrf24_jammer` bursts (authorized use
     * only). Replaces the blocking one-shot so the device + link stay free between bursts and
     * [stopNrf24Jammer] aborts within ~2s.
     */
    fun startNrf24Jammer() {
        if (client == null) { _nrf24JammerStatus.value = "Connect the device over USB first."; return }
        stopNrf24Jammer(silent = true)
        if (_micPcmActive.value) stopMicPcm()
        _nrf24JammerStatus.value = "Jamming 2.4GHz... (Stop to end)"
        launchBurstLoop(
            getJob = { nrf24JammerJob },
            setJob = { nrf24JammerJob = it },
            active = _nrf24Jamming,
            cmd = { "nrf24_jammer $SPAM_BURST_SECS" },
            onReject = { _nrf24JammerStatus.value = nrf24ErrMessage(it) },
            onStopped = { if (_nrf24JammerStatus.value.startsWith("Jamming")) _nrf24JammerStatus.value = "Jammer stopped." },
        )
    }

    fun stopNrf24Jammer(silent: Boolean = false) {
        nrf24JammerJob?.cancel()
        nrf24JammerJob = null
        _nrf24Jamming.value = false
        if (!silent && _nrf24JammerStatus.value.startsWith("Jamming")) _nrf24JammerStatus.value = "Jammer stopped."
    }

    // --- Sub-GHz jammer (CC1101) --- cancellable loop of ~Ns `subghz_jammer <freqKHz> <secs>` bursts.
    private var subghzJammerJob: Job? = null
    private val _subghzJamming = MutableStateFlow(false)
    val subghzJamming: StateFlow<Boolean> = _subghzJamming.asStateFlow()
    private val _subghzJammerStatus = MutableStateFlow("")
    val subghzJammerStatus: StateFlow<String> = _subghzJammerStatus.asStateFlow()

    /** Start the CC1101 jammer at [freqKHz] (authorized use only) as abortable ~Ns bursts. */
    fun startSubghzJammer(freqKHz: Int) {
        if (client == null) { _subghzJammerStatus.value = "Connect the device over USB first."; return }
        stopSubghzJammer(silent = true)
        if (_micPcmActive.value) stopMicPcm()
        val f = freqKHz.coerceIn(300000, 928000)
        _subghzJammerStatus.value = "Jamming ${f / 1000.0} MHz... (Stop to end)"
        launchBurstLoop(
            getJob = { subghzJammerJob },
            setJob = { subghzJammerJob = it },
            active = _subghzJamming,
            cmd = { "subghz_jammer $f $SPAM_BURST_SECS" },
            onReject = { _subghzJammerStatus.value = if (it.isBlank()) "Jammer failed." else it },
            onStopped = { if (_subghzJammerStatus.value.startsWith("Jamming")) _subghzJammerStatus.value = "Jammer stopped." },
        )
    }

    fun stopSubghzJammer(silent: Boolean = false) {
        subghzJammerJob?.cancel()
        subghzJammerJob = null
        _subghzJamming.value = false
        if (!silent && _subghzJammerStatus.value.startsWith("Jamming")) _subghzJammerStatus.value = "Jammer stopped."
    }

    // --- ARP spoof / NetCut (device joins the target Wi-Fi, then poisons target<->gateway) ---
    private var arpSpoofJob: Job? = null
    private val _arpSpoofActive = MutableStateFlow(false)
    val arpSpoofActive: StateFlow<Boolean> = _arpSpoofActive.asStateFlow()
    private val _arpSpoofStatus = MutableStateFlow("")
    val arpSpoofStatus: StateFlow<String> = _arpSpoofStatus.asStateFlow()

    /**
     * Start ARP spoofing [targetIp] against gateway [gwIp] on Wi-Fi [ssid]/[pass], as abortable
     * bursts of `arp_spoof <ssid> <pass> <target_ip> <gw_ip> <secs>`. Authorized use only.
     */
    fun startArpSpoof(ssid: String, pass: String, targetIp: String, gwIp: String) {
        if (client == null) { _arpSpoofStatus.value = "Connect the device over USB first."; return }
        if (ssid.isBlank() || targetIp.isBlank() || gwIp.isBlank()) {
            _arpSpoofStatus.value = "Fill SSID, target IP and gateway IP."; return
        }
        stopArpSpoof(silent = true)
        if (_micPcmActive.value) stopMicPcm()
        _arpSpoofStatus.value = "Spoofing $targetIp via $gwIp... (Stop to end)"
        launchBurstLoop(
            getJob = { arpSpoofJob },
            setJob = { arpSpoofJob = it },
            active = _arpSpoofActive,
            cmd = { "arp_spoof $ssid $pass $targetIp $gwIp $SPAM_BURST_SECS" },
            onReject = { _arpSpoofStatus.value = if (it.isBlank()) "ARP spoof failed." else it },
            onStopped = { if (_arpSpoofStatus.value.startsWith("Spoofing")) _arpSpoofStatus.value = "Stopped." },
        )
    }

    fun stopArpSpoof(silent: Boolean = false) {
        arpSpoofJob?.cancel()
        arpSpoofJob = null
        _arpSpoofActive.value = false
        if (!silent && _arpSpoofStatus.value.startsWith("Spoofing")) _arpSpoofStatus.value = "Stopped."
    }

    // --- IR jammer --- abortable loop of ~Ns `ir_jammer <secs>` bursts.
    private var irJammerJob: Job? = null
    private val _irJamming = MutableStateFlow(false)
    val irJamming: StateFlow<Boolean> = _irJamming.asStateFlow()
    private val _irJammerStatus = MutableStateFlow("")
    val irJammerStatus: StateFlow<String> = _irJammerStatus.asStateFlow()

    fun startIrJammer() {
        if (client == null) { _irJammerStatus.value = "Connect the device over USB first."; return }
        stopIrJammer(silent = true)
        if (_micPcmActive.value) stopMicPcm()
        _irJammerStatus.value = "Jamming IR... (Stop to end)"
        launchBurstLoop(
            getJob = { irJammerJob },
            setJob = { irJammerJob = it },
            active = _irJamming,
            cmd = { "ir_jammer $SPAM_BURST_SECS" },
            onReject = { _irJammerStatus.value = if (it.isBlank()) "Jammer failed." else it },
            onStopped = { if (_irJammerStatus.value.startsWith("Jamming")) _irJammerStatus.value = "Jammer stopped." },
        )
    }

    fun stopIrJammer(silent: Boolean = false) {
        irJammerJob?.cancel()
        irJammerJob = null
        _irJamming.value = false
        if (!silent && _irJammerStatus.value.startsWith("Jamming")) _irJammerStatus.value = "Jammer stopped."
    }

    // --- BLE iBeacon advertiser --- abortable loop of ~Ns `ble_ibeacon <secs> [uuid]` bursts.
    private var ibeaconJob: Job? = null
    private val _ibeaconActive = MutableStateFlow(false)
    val ibeaconActive: StateFlow<Boolean> = _ibeaconActive.asStateFlow()
    private val _ibeaconStatus = MutableStateFlow("")
    val ibeaconStatus: StateFlow<String> = _ibeaconStatus.asStateFlow()

    fun startIbeacon(uuid: String = "") {
        if (client == null) { _ibeaconStatus.value = "Connect the device over USB first."; return }
        stopIbeacon(silent = true)
        if (_micPcmActive.value) stopMicPcm()
        val u = uuid.trim()
        _ibeaconStatus.value = "Broadcasting iBeacon... (Stop to end)"
        launchBurstLoop(
            getJob = { ibeaconJob },
            setJob = { ibeaconJob = it },
            active = _ibeaconActive,
            cmd = { if (u.isEmpty()) "ble_ibeacon $SPAM_BURST_SECS" else "ble_ibeacon $SPAM_BURST_SECS $u" },
            onReject = { _ibeaconStatus.value = if (it.isBlank()) "iBeacon failed." else it },
            onStopped = { if (_ibeaconStatus.value.startsWith("Broadcasting")) _ibeaconStatus.value = "Stopped." },
        )
    }

    fun stopIbeacon(silent: Boolean = false) {
        ibeaconJob?.cancel()
        ibeaconJob = null
        _ibeaconActive.value = false
        if (!silent && _ibeaconStatus.value.startsWith("Broadcasting")) _ibeaconStatus.value = "Stopped."
    }

    // --- BLE media-control hijack (one-shot): ble_media <mac> ---
    private val _bleMediaStatus = MutableStateFlow("")
    val bleMediaStatus: StateFlow<String> = _bleMediaStatus.asStateFlow()
    fun bleMedia(mac: String) {
        val m = mac.trim()
        if (client == null) { _bleMediaStatus.value = "Connect the device over USB first."; return }
        if (!Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$").matches(m)) { _bleMediaStatus.value = "Enter a MAC like AA:BB:CC:DD:EE:FF."; return }
        _bleMediaStatus.value = "Connecting to $m..."
        dispatchCmd("ble_media $m") { ok, out -> _bleMediaStatus.value = out.ifBlank { if (ok) "Sent." else "Failed." } }
    }

    // --- Sub-GHz brute force (bounded one-shot): subghz_bruteforce <proto> <freqHz> <bits> <secs> ---
    private val _subBruteActive = MutableStateFlow(false)
    val subBruteActive: StateFlow<Boolean> = _subBruteActive.asStateFlow()
    private val _subBruteStatus = MutableStateFlow("")
    val subBruteStatus: StateFlow<String> = _subBruteStatus.asStateFlow()
    fun subghzBruteforce(protocolIdx: Int, freqHz: Long, bits: Int, secs: Int) {
        if (client == null) { _subBruteStatus.value = "Connect the device over USB first."; return }
        if (_subBruteActive.value) return
        _subBruteActive.value = true
        _subBruteStatus.value = "Brute-forcing for ${secs}s..."
        viewModelScope.launch(Dispatchers.IO) {
            val (ok, out) = sendAwait("subghz_bruteforce $protocolIdx $freqHz $bits $secs", timeoutMs = (secs + 8) * 1000L)
            _subBruteStatus.value = out.ifBlank { if (ok) "Done." else "Failed." }
            _subBruteActive.value = false
        }
    }

    // --- Wi-Fi jam detect (bounded): jam_detect <secs> -> "deauth=.. total=.." ---
    private val _jamDetectActive = MutableStateFlow(false)
    val jamDetectActive: StateFlow<Boolean> = _jamDetectActive.asStateFlow()
    private val _jamDetectStatus = MutableStateFlow("")
    val jamDetectStatus: StateFlow<String> = _jamDetectStatus.asStateFlow()
    fun jamDetect(secs: Int) {
        if (client == null) { _jamDetectStatus.value = "Connect the device over USB first."; return }
        if (_jamDetectActive.value) return
        _jamDetectActive.value = true
        _jamDetectStatus.value = "Listening for ${secs}s..."
        viewModelScope.launch(Dispatchers.IO) {
            val (ok, out) = sendAwait("jam_detect $secs", timeoutMs = (secs + 8) * 1000L)
            _jamDetectStatus.value = out.ifBlank { if (ok) "Done." else "Failed." }
            _jamDetectActive.value = false
        }
    }

    // --- WIDS: continuous wireless-intrusion monitor. Loops jam_detect windows (802.11 monitor mode -
    //     only the ESP can do this, a phone cannot) and logs deauth/disassoc floods with a phone
    //     timestamp; raises an ALERT when a window exceeds the threshold. Defensive / authorized use.
    private val _widsActive = MutableStateFlow(false)
    val widsActive: StateFlow<Boolean> = _widsActive.asStateFlow()
    private val _widsStatus = MutableStateFlow("")
    val widsStatus: StateFlow<String> = _widsStatus.asStateFlow()
    private val _widsEvents = MutableStateFlow<List<WidsEvent>>(emptyList())
    val widsEvents: StateFlow<List<WidsEvent>> = _widsEvents.asStateFlow()
    private var widsJob: Job? = null

    /** Start the WIDS monitor: back-to-back jam_detect windows, alert when deauths >= [threshold]. */
    fun startWids(windowSecs: Int = 15, threshold: Int = 20) {
        if (client == null) { _widsStatus.value = "Connect the device over USB first."; return }
        if (_widsActive.value) return
        _widsActive.value = true
        _widsEvents.value = emptyList()
        widsJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var windows = 0
                while (true) {
                    ensureActive()
                    _widsStatus.value = "Monitoring (window ${windows + 1}, ${windowSecs}s)..."
                    val (ok, out) = sendAwait("jam_detect $windowSecs", timeoutMs = (windowSecs + 8) * 1000L)
                    windows++
                    if (ok) {
                        // Parse "deauth=<n> total=<m>".
                        val deauth = Regex("deauth=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val total = Regex("total=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val alert = deauth >= threshold
                        if (alert || deauth > 0) {
                            _widsEvents.value = (_widsEvents.value + WidsEvent(System.currentTimeMillis(), deauth, total, alert)).takeLast(100)
                        }
                        _widsStatus.value = if (alert) "ALERT: deauth flood ($deauth in ${windowSecs}s)" else "Clear (window $windows, deauth=$deauth)"
                    } else {
                        _widsStatus.value = out.ifBlank { "jam_detect failed" }
                    }
                }
            } catch (_: Exception) {
                _widsStatus.value = "Monitor stopped."
            } finally {
                _widsActive.value = false
            }
        }
    }

    fun stopWids() {
        widsJob?.cancel()
        widsJob = null
        _widsActive.value = false
        _widsStatus.value = "Monitor stopped."
    }

    fun clearWidsEvents() { _widsEvents.value = emptyList() }

    // --- Evil Portal (captive-portal / evil twin) --- persistent device-side session, creds streamed.
    private val _evilActive = MutableStateFlow(false)
    val evilActive: StateFlow<Boolean> = _evilActive.asStateFlow()
    private val _evilStatus = MutableStateFlow("")
    val evilStatus: StateFlow<String> = _evilStatus.asStateFlow()
    private val _evilCreds = MutableStateFlow<List<EvilCred>>(emptyList())
    val evilCreds: StateFlow<List<EvilCred>> = _evilCreds.asStateFlow()

    /**
     * Start the Evil Portal on the device with AP name [ssid]. If [templateBytes]/[templateName] are
     * given, the HTML is first pushed to the device and used as the captive page; otherwise the
     * device's built-in page is served. Captured credentials arrive as EVENT evil.cred.
     */
    fun startEvilPortal(
        ssid: String,
        templateBytes: ByteArray? = null,
        templateName: String = "",
        channel: Int = 1,
        deauth: Boolean = false,
    ) {
        if (client == null) { _evilStatus.value = "Connect the device over USB first."; return }
        _evilStatus.value = "Starting..."
        _evilCreds.value = emptyList()
        val name = ssid.ifBlank { "Free Wifi" }
        val ch = channel.coerceIn(1, 14)
        fun launch(tpath: String) {
            // Always send 4 tab-separated fields: ssid, templatePath, channel, deauth(0/1).
            val arg = "$name\t$tpath\t$ch\t${if (deauth) 1 else 0}"
            dispatchCmd("evil_portal_start $arg") { ok, out ->
                _evilActive.value = ok
                _evilStatus.value = out
            }
        }
        if (templateBytes != null && templateName.isNotBlank()) {
            val path = "/$templateName"
            pushFile(path, templateBytes) { ok, msg -> if (ok) launch(path) else _evilStatus.value = "Template push failed: $msg" }
        } else launch("")
    }

    fun stopEvilPortal() {
        dispatchCmd("evil_portal_stop") { _, out -> _evilActive.value = false; _evilStatus.value = out.ifBlank { "Stopped." } }
    }

    fun clearEvilCreds() { _evilCreds.value = emptyList() }

    /**
     * Start MouseJack scan/inject as a cancellable loop of ~2s `nrf24_mousejack <secs> [text]`
     * bursts; discovered devices stream via EVENT nrf24.mj between bursts. [text] (optional
     * keystrokes to inject) is kept as given and re-sent each burst. Replaces the blocking one-shot
     * so [stopNrf24MouseJack] aborts within ~2s.
     */
    fun startNrf24MouseJack(text: String = "") {
        if (client == null) { _nrf24MjStatus.value = "Connect the device over USB first."; return }
        stopNrf24MouseJack(silent = true)
        if (_micPcmActive.value) stopMicPcm()
        _nrf24MjDevices.value = emptyMap()
        _nrf24MjStatus.value = "MouseJack running... (Stop to end)"
        val trimmed = text.trim()
        val cmd = if (trimmed.isEmpty()) "nrf24_mousejack $SPAM_BURST_SECS" else "nrf24_mousejack $SPAM_BURST_SECS $trimmed"
        launchBurstLoop(
            getJob = { nrf24MjJob },
            setJob = { nrf24MjJob = it },
            active = _nrf24MjRunning,
            cmd = { cmd },
            onReject = { _nrf24MjStatus.value = nrf24ErrMessage(it) },
            onStopped = { if (_nrf24MjStatus.value.startsWith("MouseJack")) _nrf24MjStatus.value = "MouseJack stopped." },
        )
    }

    fun stopNrf24MouseJack(silent: Boolean = false) {
        nrf24MjJob?.cancel()
        nrf24MjJob = null
        _nrf24MjRunning.value = false
        if (!silent && _nrf24MjStatus.value.startsWith("MouseJack")) _nrf24MjStatus.value = "MouseJack stopped."
    }

    // --- LoRa (config / send / receive) ----------------------------------------
    // lora_config applies the radio parameters; lora_send transmits one message; lora_recv runs
    // for <secs> and streams received packets via EVENT lora.rx {data, rssi, snr} (a short recv
    // may also return the packet directly in its ACK). Disabled when disconnected.
    private val _loraPackets = MutableStateFlow<List<LoraPacket>>(emptyList())
    val loraPackets: StateFlow<List<LoraPacket>> = _loraPackets.asStateFlow()
    private val _loraStatus = MutableStateFlow("")
    val loraStatus: StateFlow<String> = _loraStatus.asStateFlow()
    private val _loraReceiving = MutableStateFlow(false)
    val loraReceiving: StateFlow<Boolean> = _loraReceiving.asStateFlow()

    private fun loraErrMessage(out: String) = out.ifBlank { "No LoRa module on this device." }

    /** Append a LoRa packet from an EVENT lora.rx data object or an lora_recv ACK data object. */
    private fun addLoraPacket(r: JSONObject) {
        val text = r.optString("data")
        if (text.isEmpty() && !r.has("rssi")) return
        _loraPackets.value = (_loraPackets.value + LoraPacket(
            data = text,
            rssi = r.optInt("rssi"),
            snr = r.optDouble("snr", 0.0),
            lastSeen = System.currentTimeMillis(),
        )).takeLast(200)
    }

    /**
     * Apply the LoRa radio configuration (lora_config <freqMHz> [bw] [sf] [cr]). Blank optional
     * fields are omitted. The final ACK is reflected on the status line.
     */
    fun loraConfig(freqMHz: String, bw: String = "", sf: String = "", cr: String = "") {
        if (client == null) { _loraStatus.value = "Connect the device over USB first."; return }
        val freq = freqMHz.trim()
        if (freq.isEmpty()) { _loraStatus.value = "Enter a frequency in MHz."; return }
        val args = listOf(freq, bw.trim(), sf.trim(), cr.trim())
        // Keep positional ordering: drop only trailing blanks so bw/sf/cr stay in place.
        val tail = args.drop(1).dropLastWhile { it.isEmpty() }
        val cmd = ("lora_config " + (listOf(freq) + tail).joinToString(" ")).trim()
        _loraStatus.value = "Applying LoRa config..."
        dispatchCmd(cmd) { ok, out ->
            _loraStatus.value = if (ok) out.ifBlank { "LoRa configured." } else loraErrMessage(out)
        }
    }

    /** Transmit one LoRa message (lora_send <text>). The ACK result is shown on the status line. */
    fun loraSend(text: String) {
        if (client == null) { _loraStatus.value = "Connect the device over USB first."; return }
        val msg = text.trim()
        if (msg.isEmpty()) { _loraStatus.value = "Enter a message to send."; return }
        _loraStatus.value = "Sending..."
        dispatchCmd("lora_send $msg") { ok, out ->
            _loraStatus.value = if (ok) out.ifBlank { "Sent." } else loraErrMessage(out)
        }
    }

    /**
     * Listen for LoRa packets for [seconds] (lora_recv <secs>). Packets arrive via EVENT lora.rx;
     * a short recv may also return one directly in its ACK data. Fire-and-ack; stop is local.
     */
    fun loraRecv(seconds: Int = 20) {
        if (client == null) { _loraStatus.value = "Connect the device over USB first."; return }
        _loraReceiving.value = true
        _loraStatus.value = "Listening for LoRa packets (${seconds}s)..."
        dispatchCmdJson("lora_recv $seconds") { ok, d ->
            _loraReceiving.value = false
            if (ok) {
                // A packet may be returned directly in the ACK data object.
                d?.optJSONObject("data")?.let { addLoraPacket(it) }
                _loraStatus.value = d?.optString("out").orEmpty().ifBlank { "Receive complete." }
            } else {
                _loraStatus.value = loraErrMessage(d?.optString("out").orEmpty())
            }
        }
    }

    fun stopLoraRecv() { _loraReceiving.value = false }

    fun clearLoraPackets() { _loraPackets.value = emptyList() }

    // --- Ethernet (status / config) --------------------------------------------
    // eth_status reports link state + IP; eth_config applies network settings. Disabled when
    // disconnected.
    private val _ethStatus = MutableStateFlow<EthStatus?>(null)
    val ethStatus: StateFlow<EthStatus?> = _ethStatus.asStateFlow()
    private val _ethMessage = MutableStateFlow("")
    val ethMessage: StateFlow<String> = _ethMessage.asStateFlow()

    private fun ethErrMessage(out: String) = out.ifBlank { "No Ethernet on this device." }

    /** Query Ethernet link + IP (eth_status). The ACK data carries {link, ip}. */
    fun ethStatus() {
        if (client == null) { _ethMessage.value = "Connect the device over USB first."; return }
        _ethMessage.value = "Querying Ethernet..."
        dispatchCmdJson("eth_status") { ok, d ->
            if (ok) {
                _ethStatus.value = EthStatus(
                    link = d?.optBoolean("link", false) ?: false,
                    ip = d?.optString("ip").orEmpty(),
                    detail = d?.optString("out").orEmpty(),
                )
                _ethMessage.value = ""
            } else {
                _ethMessage.value = ethErrMessage(d?.optString("out").orEmpty())
            }
        }
    }

    /** Apply Ethernet configuration (eth_config <...>); [args] is the raw argument string. */
    fun ethConfig(args: String) {
        if (client == null) { _ethMessage.value = "Connect the device over USB first."; return }
        val a = args.trim()
        val cmd = if (a.isEmpty()) "eth_config" else "eth_config $a"
        _ethMessage.value = "Applying Ethernet config..."
        dispatchCmd(cmd) { ok, out ->
            _ethMessage.value = if (ok) out.ifBlank { "Ethernet configured." } else ethErrMessage(out)
            if (ok) ethStatus()
        }
    }

    /** DHCP-starvation flood over Ethernet: eth_dhcp_starve [secs]. Authorized use only. */
    fun ethDhcpStarve(secs: Int = 10) {
        if (client == null) { _ethMessage.value = "Connect the device over USB first."; return }
        _ethMessage.value = "DHCP starvation running (${secs}s)..."
        dispatchCmd("eth_dhcp_starve $secs") { ok, out ->
            _ethMessage.value = if (ok) out.ifBlank { "DHCP starvation done." } else ethErrMessage(out)
        }
    }

    /** MAC-flooding over Ethernet: eth_mac_flood [secs]. Authorized use only. */
    fun ethMacFlood(secs: Int = 10) {
        if (client == null) { _ethMessage.value = "Connect the device over USB first."; return }
        _ethMessage.value = "MAC flooding running (${secs}s)..."
        dispatchCmd("eth_mac_flood $secs") { ok, out ->
            _ethMessage.value = if (ok) out.ifBlank { "MAC flooding done." } else ethErrMessage(out)
        }
    }

    // --- FM audio transmitter --------------------------------------------------
    // Streams 16 kHz mono 16-bit PCM to the device as base64 fm_audio_data chunks, paced to
    // real time. The device's OWN mic is a special case: fm_audio_begin <freq> mic, no PCM.
    private val _fmStatus = MutableStateFlow("")
    val fmStatus: StateFlow<String> = _fmStatus.asStateFlow()
    private val _fmStreaming = MutableStateFlow(false)
    val fmStreaming: StateFlow<Boolean> = _fmStreaming.asStateFlow()
    private val _fmLevel = MutableStateFlow(0f)
    val fmLevel: StateFlow<Float> = _fmLevel.asStateFlow()
    private val _fmElapsedMs = MutableStateFlow(0L)
    val fmElapsedMs: StateFlow<Long> = _fmElapsedMs.asStateFlow()
    private var fmJob: Job? = null

    /** Stream a generated test tone to the FM transmitter. */
    fun fmStartTone(freqKHz: Int, toneHz: Double = 440.0) =
        fmStart(freqKHz, "phone") { PcmSource.toneReader(toneHz) }

    /** Decode an audio file to 16 kHz mono PCM and stream it to the FM transmitter. */
    fun fmStartFile(uri: Uri, freqKHz: Int) = fmStart(freqKHz, "phone") {
        val pcm = PcmSource.decodeFile(getApplication(), uri)
        if (pcm.isEmpty()) throw IllegalStateException("Could not decode this audio file.")
        PcmSource.arrayReader(pcm)
    }

    /** Capture the PHONE mic and stream it to the FM transmitter (needs RECORD_AUDIO). */
    fun fmStartPhoneMic(freqKHz: Int) = fmStart(freqKHz, "phone") {
        val mic = PcmSource.MicReader()
        if (!mic.start()) throw IllegalStateException("Microphone unavailable (permission denied?).")
        mic
    }

    /** Transmit from the DEVICE's own mic - no phone PCM, the firmware captures and modulates. */
    fun fmStartDeviceMic(freqKHz: Int) = fmStart(freqKHz, "mic", null)

    /** Stop any active FM transmission (fm_audio_stop). */
    fun fmStop() {
        if (fmJob == null && !_fmStreaming.value) return
        _fmStreaming.value = false
        fmJob?.cancel()
        fmJob = null
        // Ensure the device hears stop even for the device-mic mode (which has no streaming job).
        client?.let { c -> viewModelScope.launch(Dispatchers.IO) { c.cmd("fm_audio_stop") } }
        _fmLevel.value = 0f
        _fmStatus.value = "Stopped."
    }

    /** Suspend until the begin ACK arrives (or a short timeout), returning ok + message. */
    private suspend fun fmBegin(cmd: String): Pair<Boolean, String> {
        val c = client ?: return false to "Not connected."
        return withTimeoutOrNull(4000) {
            suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                val id = c.nextId()
                pendingAcks[id] = { ok, out -> if (cont.isActive) cont.resumeWith(Result.success(ok to out)) }
                cont.invokeOnCancellation { pendingAcks.remove(id) }
                transmitCommand(c, cmd, id)
            }
        } ?: (false to "No response from the device.")
    }

    /**
     * Core FM flow: send fm_audio_begin, then (for phone sources) stream PCM chunks paced to
     * real time, and fm_audio_stop at the end. [makeReader] is null for the device-mic mode.
     */
    private fun fmStart(freqKHz: Int, mode: String, makeReader: (() -> PcmSource.PcmReader)?) {
        val c = client ?: run { _fmStatus.value = "Connect the device over USB first."; return }
        if (_fmStreaming.value) return
        _fmStreaming.value = true
        _fmLevel.value = 0f
        _fmElapsedMs.value = 0L
        _fmStatus.value = "Starting FM at $freqKHz kHz..."
        fmJob = viewModelScope.launch(Dispatchers.IO) {
            val (ok, msg) = fmBegin("fm_audio_begin $freqKHz $mode")
            if (!ok) {
                _fmStatus.value = msg.ifBlank { "FM transmitter not available." }
                _fmStreaming.value = false
                return@launch
            }
            if (makeReader == null) {
                _fmStatus.value = "Transmitting from the device mic at $freqKHz kHz."
                return@launch
            }
            val reader = try {
                makeReader()
            } catch (e: Exception) {
                c.cmd("fm_audio_stop")
                _fmStatus.value = e.message ?: "Audio source failed."
                _fmStreaming.value = false
                return@launch
            }
            _fmStatus.value = "Transmitting at $freqKHz kHz..."
            val buf = ShortArray(1024) // 1024 samples -> 2048 bytes (~2 KB) per fm_audio_data chunk
            val startMs = System.currentTimeMillis()
            var sentSamples = 0L
            try {
                while (isActive && _fmStreaming.value) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    if (n == 0) { delay(5); continue }
                    val bytes = PcmSource.shortsToLittleEndian(buf, n)
                    c.cmd("fm_audio_data " + Base64.encodeToString(bytes, Base64.NO_WRAP))
                    _fmLevel.value = PcmSource.rmsLevel(buf, n)
                    sentSamples += n
                    _fmElapsedMs.value = sentSamples * 1000 / PcmSource.SAMPLE_RATE
                    // Pace to the real 16 kHz playback rate so we do not outrun the device buffer.
                    val sleep = (sentSamples * 1000 / PcmSource.SAMPLE_RATE) - (System.currentTimeMillis() - startMs)
                    if (sleep > 0) delay(sleep)
                }
            } finally {
                reader.close()
                c.cmd("fm_audio_stop")
                _fmLevel.value = 0f
                val finished = _fmStreaming.value // reader hit the end rather than user stop
                _fmStreaming.value = false
                _fmStatus.value = if (finished) "Finished." else "Stopped."
            }
        }
    }

    /**
     * Start continuous, abortable Wi-Fi beacon spam (authorized testing only): the device
     * broadcasts fake [ssids]. Same abortable short-burst loop as [startBleSpam]: a cancellable
     * loop of ~2s `wifi_beacon_spam <secs> <ssids>` bursts, each awaited before the next, so the
     * device + link stay free between bursts and the user can [stopBeaconSpam] within ~2s. The
     * SSID list is newline-joined into the command payload.
     */
    fun startBeaconSpam(ssids: List<String>) {
        if (client == null) { _beaconStatus.value = "Connect the device over USB first."; return }
        val list = ssids.map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) { _beaconStatus.value = "Add at least one SSID or a number of fake APs."; return }
        stopBeaconSpam(silent = true)
        if (_micPcmActive.value) stopMicPcm()
        _beaconActive.value = true
        _beaconStatus.value = "Broadcasting ${list.size} SSID(s)... (Stop to end)"
        val payload = list.joinToString("\n")
        _console.value = (_console.value + "> wifi_beacon_spam loop (${list.size} SSIDs)").takeLast(500)
        beaconSpamJob = viewModelScope.launch(Dispatchers.IO) {
            val myJob = coroutineContext[Job]
            try {
                while (isActive && _beaconActive.value && transport?.isOpen == true) {
                    val (ok, out) = sendAwait("wifi_beacon_spam $SPAM_BURST_SECS $payload")
                    if (!ok && out.isNotBlank()) { _beaconStatus.value = out; break }
                }
            } finally {
                if (beaconSpamJob === myJob) {
                    _beaconActive.value = false
                    if (_beaconStatus.value.startsWith("Broadcasting")) _beaconStatus.value = "Stopped."
                }
            }
        }
    }

    /** Stop the Wi-Fi beacon spam loop. [silent] keeps the status line (used when restarting). */
    fun stopBeaconSpam(silent: Boolean = false) {
        beaconSpamJob?.cancel()
        beaconSpamJob = null
        _beaconActive.value = false
        if (!silent) _beaconStatus.value = "Stopped."
    }

    /**
     * Stop every abortable short-burst loop at once (BLE/Wi-Fi spam, Wi-Fi deauth, all NRF24 ops).
     * Used on [disconnect] and whenever an audio stream (mic.pcm) needs the single-threaded link.
     */
    private fun stopAllBurstLoops(silent: Boolean) {
        stopBleSpam(silent = silent)
        stopBeaconSpam(silent = silent)
        stopDeauth(silent = silent)
        stopNrf24Scan(silent = silent)
        stopNrf24Sniff(silent = silent)
        stopNrf24Jammer(silent = silent)
        stopNrf24MouseJack(silent = silent)
    }

    /**
     * Stop everything currently running (burst loops, evil portal, speaker/RF streams) and mark any
     * still-running command-log entries as stopped. Invoked from the Cmds viewer's Stop button.
     */
    fun stopAllRunning() {
        stopAllBurstLoops(silent = false)
        try { stopEvilPortal() } catch (e: Exception) { app.brucia.BruciaLog.d("VM", "stopEvilPortal cleanup: ${e.message}") }
        sstvTransmitStop()
        try { stopRfCapture() } catch (e: Exception) { app.brucia.BruciaLog.d("VM", "stopRfCapture cleanup: ${e.message}") }
        _commandLog.update { list ->
            list.map {
                if (it.status == CmdStatus.RUNNING) {
                    it.copy(status = CmdStatus.FAILED, result = "stopped", doneAtMs = System.currentTimeMillis())
                } else it
            }
        }
    }

    // --- Device panel (info / diagnostics / power) ---
    private val _deviceOut = MutableStateFlow("")
    val deviceOut: StateFlow<String> = _deviceOut.asStateFlow()

    fun deviceCmd(cmd: String) = runWithResult(cmd) { _, out -> _deviceOut.value = out }
    fun powerReboot() = sendConsole("reboot")
    fun powerSleep() = sendConsole("sleep")

    /** Decode the bundled synthetic sample (works without hardware, for demo/test). */
    fun decodeSample() {
        _rfWave.value = OokSamples.princeton24
        val decoded = SubGhzDecoder.decode(OokSamples.princeton24)
        _subghz.value = (decoded + _subghz.value).take(50)
    }

    fun clearSubghz() {
        _subghz.value = emptyList()
    }

    // --- Flipper .sub import / export ---------------------------------------

    /** The last imported `.sub` file (null until one is loaded). */
    private val _importedSub = MutableStateFlow<SubSignal?>(null)
    val importedSub: StateFlow<SubSignal?> = _importedSub.asStateFlow()

    /** Load a `.sub` file from a content Uri; posts it to [importedSub]. */
    fun importSub(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val sig = SubFile.parse(text)
                _importedSub.value = sig
                if (sig != null) {
                    _console.value = (_console.value + "> loaded .sub: ${SubFile.summary(sig)}").takeLast(500)
                    // Also decode RAW captures on the phone so the summary is richer.
                    if (sig.isRaw) {
                        _rfWave.value = sig.rawAbs()
                        val decoded = SubGhzDecoder.decode(sig.rawAbs())
                        if (decoded.isNotEmpty()) _subghz.value = (decoded + _subghz.value).take(50)
                    }
                } else {
                    _console.value = (_console.value + "> .sub parse failed").takeLast(500)
                }
            } catch (e: Exception) {
                _importedSub.value = null
                _console.value = (_console.value + "> .sub read failed: ${e.message}").takeLast(500)
            }
        }
    }

    fun clearImportedSub() { _importedSub.value = null }

    /**
     * Transmit the imported `.sub` via the real firmware path (`subghz txp ...`).
     * Returns the command sent, or null when the signal cannot be transmitted over
     * USB (RAW that does not decode to a known protocol - no file push is wired).
     */
    fun transmitSub(repeat: Int = 10): String? {
        val sig = _importedSub.value ?: return null
        val cmd = SubFile.transmitCommand(sig, repeat) ?: return null
        _console.value = (_console.value + "> $cmd").takeLast(500)
        dispatchCmd(cmd)
        return cmd
    }

    /**
     * Export a decoded signal (or, for unknown/RAW, the live waveform) to a `.sub`
     * file in the app external dir. Returns the file, or null if nothing to export.
     */
    fun exportSub(sig: DecodedSignal?, freqHz: Long): java.io.File? {
        val wave = _rfWave.value
        val sub: SubSignal = when {
            sig != null -> SubFile.fromDecoded(sig, freqHz, wave)
            wave.isNotEmpty() -> SubFile.fromRawAbs(wave, freqHz)
            else -> return null
        }
        val dir = getApplication<Application>().getExternalFilesDir(null)
        val file = java.io.File(dir, "brucia_subghz_${System.currentTimeMillis()}.sub")
        file.writeText(SubFile.generate(sub))
        return file
    }

    fun clearSession() {
        viewModelScope.launch(Dispatchers.IO) { dao.clear() }
    }

    // --- Updates (app OTA + device firmware flash) ---------------------------
    private val _appUpdate = MutableStateFlow(AppUpdateState())
    val appUpdate: StateFlow<AppUpdateState> = _appUpdate.asStateFlow()

    private val _firmware = MutableStateFlow(FirmwareState())
    val firmware: StateFlow<FirmwareState> = _firmware.asStateFlow()

    // --- Multi-board flash: list per-board firmware images + let the user pick which to flash. ---
    private val _boards = MutableStateFlow<List<AppUpdater.BoardFw>>(emptyList())
    val boards: StateFlow<List<AppUpdater.BoardFw>> = _boards.asStateFlow()
    private val _selectedBoard = MutableStateFlow<AppUpdater.BoardFw?>(null)
    val selectedBoard: StateFlow<AppUpdater.BoardFw?> = _selectedBoard.asStateFlow()
    fun loadBoards() {
        if (_boards.value.isNotEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val list = try { AppUpdater.fetchBoards() } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) { _boards.value = list }
        }
    }
    /** null = auto (connected board / default); else flash this specific per-board image. */
    fun selectBoard(b: AppUpdater.BoardFw?) { _selectedBoard.value = b }

    private val _flash = MutableStateFlow(FlashState())
    val flash: StateFlow<FlashState> = _flash.asStateFlow()

    /**
     * Query the GitHub latest release once and populate both the app-update and the
     * firmware state. [currentApp] is BuildConfig.VERSION_NAME.
     */
    fun checkUpdates(currentApp: String) {
        if (_appUpdate.value.checking) return
        _appUpdate.value = _appUpdate.value.copy(checking = true, current = currentApp, error = null)
        _firmware.value = _firmware.value.copy(error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val r = AppUpdater.fetchLatest()
                _appUpdate.value = AppUpdateState(
                    current = currentApp,
                    latest = r.tag,
                    apkUrl = r.apkUrl,
                    newer = AppUpdater.isNewer(currentApp, r.tag),
                    notes = r.notes,
                )
                _firmware.value = FirmwareState(
                    latestTag = r.tag,
                    firmwareUrl = r.firmwareUrl?.takeIf { it.isNotEmpty() } ?: AppUpdater.FIRMWARE_FALLBACK_URL,
                )
            } catch (e: Exception) {
                val msg = e.message ?: "Check failed"
                _appUpdate.value = _appUpdate.value.copy(checking = false, error = msg)
                _firmware.value = _firmware.value.copy(error = msg)
            }
        }
    }

    /** Download the latest APK and hand it to the system installer. */
    // "Install unknown apps" is a one-time per-app grant. The first tap used to only open the grant
    // screen and require a second tap; now we remember the intent and auto-resume on return.
    @Volatile private var pendingAppInstall = false

    /** Entry point for the "Download & install" button: install now, or request the grant then auto-resume. */
    fun requestAppInstall(ctx: android.content.Context) {
        if (AppUpdater.canInstall(ctx)) { pendingAppInstall = false; downloadAndInstallApp(ctx) }
        else { pendingAppInstall = true; AppUpdater.openInstallSettings(ctx) }
    }

    /** Called from the Activity onResume: if the user just granted install permission, proceed. */
    fun resumeAppInstallIfPending(ctx: android.content.Context) {
        if (pendingAppInstall && AppUpdater.canInstall(ctx)) { pendingAppInstall = false; downloadAndInstallApp(ctx) }
    }

    fun downloadAndInstallApp(ctx: android.content.Context) {
        val url = _appUpdate.value.apkUrl ?: return
        if (_appUpdate.value.downloading) return
        _appUpdate.value = _appUpdate.value.copy(downloading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apk = AppUpdater.download(
                    ctx, url,
                    onProgress = { d, t -> _appUpdate.value = _appUpdate.value.copy(downloaded = d, total = t) },
                )
                _appUpdate.value = _appUpdate.value.copy(downloading = false)
                withContext(Dispatchers.Main) { AppUpdater.install(ctx, apk) }
            } catch (e: Exception) {
                _appUpdate.value = _appUpdate.value.copy(downloading = false, error = e.message ?: "Download failed")
            }
        }
    }

    /**
     * Download the merged firmware and flash it over USB via [EspFlasher]. [serial]
     * is an already-opened port (provided by the Activity, which also took over the
     * port from companion mode); [closePort] releases it when done.
     */
    /** Set an explicit flash error from the Activity (e.g. bootloader re-enumeration failed). */
    fun flashError(msg: String) { _flash.value = FlashState(phase = "error", message = msg); flashLog("ERROR: $msg") }
    /** Report that the flasher is rebooting the device into the ROM download mode. */
    fun flashEnteringBootloader() { _flash.value = FlashState(phase = "download", message = "Rebooting into bootloader..."); flashLog("--- flash start: rebooting into bootloader ---") }

    /**
     * True while a flash is in progress. The flasher calls [disconnect] first (so status reads
     * "Disconnected"), and a native-USB ESP32-S3 re-enumerates mid-flash; the USB-attach
     * auto-connect must NOT grab the port during this window or it steals it from the flasher.
     */
    fun isFlashing(): Boolean = _flash.value.phase.let { it == "download" || it == "flash" }

    // Persistent flash log: every flash step (incl. the MD5 verdict) is appended here with a
    // timestamp and KEPT, so the result stays readable even after companion reconnects and clears
    // the transient flash.message. Shown at the bottom of the Updates screen.
    private val _flashLog = MutableStateFlow<List<String>>(emptyList())
    val flashLog: StateFlow<List<String>> = _flashLog.asStateFlow()
    private fun flashLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _flashLog.value = (_flashLog.value + "[$ts] $msg").takeLast(200)
    }
    fun clearFlashLog() { _flashLog.value = emptyList() }

    fun flashFirmware(serial: EspFlasher.SerialIo, closePort: () -> Unit, skipBoardCheck: Boolean = false, doReset: Boolean = true) {
        if (_flash.value.phase == "flash") { closePort(); return }
        // Safety: the in-app flasher and the bundled firmware image are specific to the LilyGo
        // T-Embed CC1101 (ESP32-S3, native USB). Flashing it onto a different board would write the
        // wrong image (recoverable via the web flasher / BOOT+RESET, but still a bad outcome), so
        // refuse unless the connected board is the supported one. Other boards have their own
        // per-board firmware in Releases and should be flashed with the web flasher. [skipBoardCheck]
        // is set by the Activity when it already verified the board before rebooting to the bootloader
        // (where the device no longer reports its board over companion).
        val board = _device.value?.board?.lowercase().orEmpty()
        val supported = skipBoardCheck || (board.contains("t-embed") && board.contains("cc1101"))
        if (!supported) {
            val shown = _device.value?.board?.takeIf { it.isNotBlank() } ?: "this board"
            _flash.value = FlashState(
                phase = "error",
                message = "In-app firmware update supports only the LilyGo T-Embed CC1101. For \"$shown\", flash the matching per-board firmware from Releases using the web flasher.",
            )
            closePort(); return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = _selectedBoard.value?.url // user-picked per-board image
                    ?: _firmware.value.firmwareUrl?.takeIf { it.isNotEmpty() }
                    ?: AppUpdater.FIRMWARE_FALLBACK_URL
                _flash.value = FlashState(phase = "download", message = "Downloading firmware...")
                flashLog("Downloading firmware from ${url.substringAfterLast('/')}")
                val bytes = AppUpdater.downloadBytes(url, onProgress = { d, t ->
                    _flash.value = _flash.value.copy(written = d.toInt(), total = t.toInt(), message = "Downloading...")
                })
                _flash.value = FlashState(phase = "flash", total = bytes.size, message = "Flashing (do not unplug)...")
                flashLog("Downloaded ${bytes.size} bytes; entering flash write")
                // The T-Embed CC1101 has 16 MB of flash (factory-only partition table). Declaring the
                // real size matters: the merged image is ~4.14 MB, so a wrong 4 MB declaration makes
                // FLASH_BEGIN's erase/write region exceed the declared flash - the ROM then clamps/
                // rejects it while FLASH_DATA still ACKs, so nothing actually lands (device stays "dev").
                val flashSize = maxOf(16 * 1024 * 1024, bytes.size)
                val verify = EspFlasher(serial).flash(bytes, flashSize = flashSize, doReset = doReset, onProgress = { w, t ->
                    _flash.value = _flash.value.copy(phase = "flash", written = w, total = t, message = "Writing to flash...")
                })
                // The MD5 check tells us whether the write actually landed on the chip.
                val verifyMsg = when (verify) {
                    EspFlasher.Verify.MATCH -> "verified OK (MD5 matches)"
                    EspFlasher.Verify.MISMATCH -> "WARNING: write verification MISMATCH - the flash did NOT take, retry"
                    EspFlasher.Verify.UNVERIFIED -> "write not verified (device did not report MD5)"
                }
                _flash.value = FlashState(phase = if (verify == EspFlasher.Verify.MISMATCH) "error" else "done",
                    message = "Firmware flashed - $verifyMsg. The device is rebooting into its menu - open \"Brucia App\" on the device to reconnect, then check Device info shows the new firmware.")
                flashLog("RESULT: $verifyMsg (wrote ${bytes.size} bytes @0x0, flash=${flashSize / (1024 * 1024)}MB)")
            } catch (e: Exception) {
                _flash.value = FlashState(phase = "error", message = e.message ?: "Flash failed")
                flashLog("EXCEPTION: ${e.message ?: "Flash failed"}")
            } finally {
                closePort()
            }
        }
    }

    /** Report that no USB device was available to flash. */
    fun flashNoDevice() { _flash.value = FlashState(phase = "error", message = "No USB device found") }

    fun resetFlash() { _flash.value = FlashState() }

    fun disconnect() {
        // Stop any running short-burst loops first so nothing keeps looping / sending after the link drops.
        stopAllBurstLoops(silent = true)
        // Turn off any active device streams before dropping the link, so the device stops
        // its BLE radio / RMT RX instead of scanning forever after we go away.
        val c = client
        if (c != null && transport?.isOpen == true) {
            synchronized(activeStreams) {
                activeStreams.forEach { try { c.sub(it, false) } catch (e: Exception) { app.brucia.BruciaLog.d("VM", "stream unsubscribe: ${e.message}") } }
            }
        }
        activeStreams.clear()
        pendingStreamIds.clear()
        pendingAcks.clear()
        pendingJsonAcks.clear()
        pullBufs.clear()
        readJob?.cancel()
        readJob = null
        pingJob?.cancel()
        pingJob = null
        client = null
        transport?.close()
        transport = null
        _status.value = "Disconnected"
        _transportKind.value = "none"
        _wardriving.value = false
        _device.value = null
        _sniffing.value = false
        _stations.value = emptyMap()
        stopRfCapture()
        // Audio suite: stop any capture/decode/playback and release the wake lock (no leak).
        sstvTxJob?.cancel(); sstvTxJob = null
        stopPhoneMic()
        _micPcmActive.value = false
        _sstvTx.value = _sstvTx.value.copy(active = false)
        // Stop the foreground service and release its wake lock (covers every disconnect path).
        try { ConnectionService.stop(getApplication()) } catch (e: Exception) { app.brucia.BruciaLog.d("VM", "service stop: ${e.message}") }
    }

    /** Machine-readable JSON of the session. */
    fun buildSessionJson(): String = SessionExport.build(aps.value, ble.value.size, crack.value.result)

    /** Aggregate the session into a Markdown security report. */
    fun buildReport(): String {
        val nfc = _lastNfc.value
        val findings = if (nfc != null) NfcSecurity.analyze(nfc) else emptyList()
        return ReportBuilder.markdown(aps.value, nfc, findings, ble.value.size, crack.value.result)
    }

    /** Snapshot every captured data stream into an immutable report model. */
    fun reportSnapshot(): ReportData {
        val dev = _device.value
        return ReportData(
            fw = dev?.fw,
            board = dev?.board,
            modules = dev?.modules ?: emptyList(),
            aps = aps.value,
            stations = stations.value,
            probes = probes.value,
            ble = ble.value,
            subghz = subghz.value,
            nrf24 = nrf24Devices.value,
            nrf24Mj = nrf24MjDevices.value,
            handshakeInfo = handshakeInfo.value,
            crackResult = crack.value.result,
            monitorFrames = monitorFrames.value.size,
            mitmTxns = MitmLog.txns.value.size,
        )
    }

    /** Full "phone is the brain" session report (Markdown) over everything captured. */
    fun buildFullReport(): String = SessionReport.markdown(reportSnapshot())

    /** Heuristic insights summary over everything captured (honestly labelled, not ML). */
    fun buildInsights(): List<String> = SessionReport.insights(reportSnapshot())

    // --- Wordlists + WPA handshake cracking (heavy crypto offloaded to the phone) ---
    private val _wordlists = MutableStateFlow<List<WordlistInfo>>(emptyList())
    val wordlists: StateFlow<List<WordlistInfo>> = _wordlists.asStateFlow()

    private val _bundledWordlists = MutableStateFlow<List<BundledWordlistUi>>(emptyList())
    val bundledWordlists: StateFlow<List<BundledWordlistUi>> = _bundledWordlists.asStateFlow()

    // Downloadable real wordlists (rockyou, SecLists) and live download progress.
    val wordlistCatalog = WordlistCatalog.all
    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()
    private val downloadJobs = mutableMapOf<String, Job>()

    fun downloadWordlist(name: String, url: String) {
        if (downloadJobs[name]?.isActive == true) return
        val job = viewModelScope.launch(Dispatchers.IO) {
            _downloads.value = _downloads.value + (name to DownloadProgress(name, 0, 0, true))
            try {
                WordlistStore.download(
                    getApplication(), url, name,
                    onProgress = { done, total ->
                        _downloads.value = _downloads.value + (name to DownloadProgress(name, done, total, true))
                    },
                    isCancelled = { downloadJobs[name]?.isActive != true },
                )
                _downloads.value = _downloads.value - name
                _wordlists.value = WordlistStore.list(getApplication())
            } catch (e: Exception) {
                _downloads.value = _downloads.value + (name to DownloadProgress(name, 0, 0, false, e.message ?: "failed"))
            }
        }
        downloadJobs[name] = job
    }

    fun cancelDownload(name: String) {
        downloadJobs[name]?.cancel()
        downloadJobs.remove(name)
        _downloads.value = _downloads.value - name
    }

    fun wordlistDownloaded(name: String): Boolean = WordlistStore.has(getApplication(), name)

    private val _crack = MutableStateFlow(CrackStatus())
    val crack: StateFlow<CrackStatus> = _crack.asStateFlow()
    private var crackJob: Job? = null

    // Cooperative-cancel flag for the CPU-bound dictionary loop. Job.cancel() cannot interrupt
    // the non-suspending PBKDF2 loop inside WpaCracker, so the loop polls this flag each
    // candidate. Set true by cancelCrack(), reset false at the start of every crack.
    private val crackCancelled = AtomicBoolean(false)

    private val _rules = MutableStateFlow(false)
    val rules: StateFlow<Boolean> = _rules.asStateFlow()
    fun setRules(on: Boolean) { _rules.value = on }

    @Volatile private var loadedHandshake: Handshake? = null
    @Volatile private var loadedPmkid: PmkidTarget? = null

    /**
     * Pull a file from the device SD over USB (fs_read); auto-imports .pcap/.cap.
     * [onDone] (if given) reports whether a usable WPA handshake was loaded.
     */
    fun pullFile(path: String, ssidHint: String = "", onDone: ((Boolean) -> Unit)? = null) {
        val c = client
        if (c == null) { onDone?.invoke(false); return }
        viewModelScope.launch(Dispatchers.IO) {
            val id = c.nextId()
            val buf = java.io.ByteArrayOutputStream()
            pullBufs[id] = buf
            pendingAcks[id] = { _, _ ->
                pullBufs.remove(id)
                val bytes = buf.toByteArray()
                val name = path.substringAfterLast('/').ifEmpty { "pulled.bin" }
                val dir = java.io.File(getApplication<Application>().filesDir, "pulled").apply { mkdirs() }
                val f = java.io.File(dir, name)
                try { f.writeBytes(bytes) } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "write pulled file failed", e) }
                var loaded = false
                if (name.endsWith(".pcap", true) || name.endsWith(".cap", true)) {
                    val hs = HandshakeParser.parse(bytes)
                    val hint = ssidHint.ifEmpty { null }
                    val pm = if (hs == null) HandshakeParser.parsePmkid(bytes, hint) else null
                    if (hs != null) {
                        loadedHandshake = hs
                        loadedPmkid = null
                        _handshakeInfo.value = "${hs.ssid} (WPA${hs.keyVersion}) [pulled]"
                        loaded = true
                    } else if (pm != null) {
                        loadedPmkid = pm
                        loadedHandshake = null
                        _handshakeInfo.value = "${pm.ssid} (PMKID) [pulled]"
                        loaded = true
                    } else {
                        _handshakeInfo.value = "pulled ${bytes.size} B, no handshake"
                    }
                } else {
                    _handshakeInfo.value = "pulled ${bytes.size} B: $name"
                }
                onDone?.invoke(loaded)
            }
            transmitCommand(c, "fs_read $path", id)
        }
    }

    /**
     * Pull a device file's raw bytes over fs_read and write them to a user-chosen content [uri]
     * (Storage Access Framework ACTION_CREATE_DOCUMENT). Reuses the same pull path as [pullFile].
     * [onDone] reports (ok, message) on the main thread.
     */
    fun pullFileToUri(path: String, uri: Uri, onDone: (Boolean, String) -> Unit) {
        val c = client
        if (c == null) { onDone(false, "Not connected."); return }
        viewModelScope.launch(Dispatchers.IO) {
            val id = c.nextId()
            val buf = java.io.ByteArrayOutputStream()
            pullBufs[id] = buf
            pendingAcks[id] = { ok, out ->
                pullBufs.remove(id)
                viewModelScope.launch(Dispatchers.IO) {
                    var okWritten = false
                    var msg = out
                    if (ok) {
                        try {
                            getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                                it.write(buf.toByteArray())
                            }
                            okWritten = true
                            msg = "Saved ${buf.size()} B"
                        } catch (e: Exception) {
                            msg = "Save failed: ${e.message}"
                        }
                    } else if (msg.isBlank()) {
                        msg = "Pull failed"
                    }
                    withContext(Dispatchers.Main) { onDone(okWritten, msg) }
                }
            }
            transmitCommand(c, "fs_read $path", id)
        }
    }

    /** Pull a device file's raw bytes over fs_read, delivering them (or null on failure) on the main thread. */
    fun pullFileBytes(path: String, onBytes: (ByteArray?) -> Unit) {
        val c = client
        if (c == null) { onBytes(null); return }
        viewModelScope.launch(Dispatchers.IO) {
            val id = c.nextId()
            val buf = java.io.ByteArrayOutputStream()
            pullBufs[id] = buf
            pendingAcks[id] = { ok, _ ->
                pullBufs.remove(id)
                val bytes = if (ok) buf.toByteArray() else null
                viewModelScope.launch(Dispatchers.Main) { onBytes(bytes) }
            }
            transmitCommand(c, "fs_read $path", id)
        }
    }

    /** Await one command's ACK without logging the full command text (used for base64 file chunks). */
    private suspend fun sendNoLog(cmd: String, timeoutMs: Long = 8000): Pair<Boolean, String> {
        val c = client ?: return false to "Not connected."
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Pair<Boolean, String>> { cont ->
                val id = c.nextId()
                pendingAcks[id] = { ok, out -> if (cont.isActive) cont.resumeWith(Result.success(ok to out)) }
                cont.invokeOnCancellation { pendingAcks.remove(id) }
                transmitCommand(c, cmd, id)
            }
        } ?: (false to "No response from the device.")
    }

    /**
     * Write [bytes] to the device at [path] using the firmware write grammar: the first chunk
     * truncates (fs_write), later chunks append (fs_write_append). Large files are streamed in
     * [DeviceFilePush.MAX_RAW_CHUNK]-byte chunks so each base64 frame stays under the companion max
     * frame. One summary line is logged (not each base64 chunk). [onDone] reports on the main thread.
     */
    fun pushFile(path: String, bytes: ByteArray, onDone: (Boolean, String) -> Unit) {
        if (client == null) { onDone(false, "Not connected."); return }
        val chunks = app.brucia.files.DeviceFilePush.chunk(bytes)
        val logId = logCmdStart("fs_write $path (${bytes.size} B, ${chunks.size} chunk${if (chunks.size == 1) "" else "s"})")
        viewModelScope.launch(Dispatchers.IO) {
            var ok = true
            var last = ""
            for ((i, ch) in chunks.withIndex()) {
                val b64 = Base64.encodeToString(ch, Base64.NO_WRAP)
                val (cok, out) = sendNoLog(app.brucia.files.DeviceFilePush.command(path, i, b64))
                last = out
                if (!cok) { ok = false; break }
            }
            logCmdDone(logId, ok, if (ok) "wrote ${bytes.size} B" else last)
            val msg = if (ok) "Saved ${bytes.size} B to $path" else last.ifBlank { "Write failed (needs firmware fs_write)" }
            withContext(Dispatchers.Main) { onDone(ok, msg) }
        }
    }

    private val _handshakeInfo = MutableStateFlow<String?>(null)
    val handshakeInfo: StateFlow<String?> = _handshakeInfo.asStateFlow()

    /** Export the loaded target to hashcat 22000, or null if nothing is loaded. */
    fun exportHashcat(): String? {
        loadedPmkid?.let { return Hc22000.export(it) }
        loadedHandshake?.let { return Hc22000.export(it) }
        return null
    }

    /** Load a PMKID (hashcat 16800) or a full hashcat 22000 line (WPA*01/WPA*02). */
    fun importPmkid(line: String) {
        if (line.trim().startsWith("WPA*")) {
            val r = Hc22000.parse(line)
            if (r != null) {
                val (hs, pm) = r
                if (hs != null) { loadedHandshake = hs; loadedPmkid = null; _handshakeInfo.value = "${hs.ssid} (WPA${hs.keyVersion}) [22000]" }
                else if (pm != null) { loadedPmkid = pm; loadedHandshake = null; _handshakeInfo.value = "${pm.ssid} (PMKID) [22000]" }
                return
            }
            _handshakeInfo.value = "invalid 22000 line"
            return
        }
        val t = Pmkid.parse(line)
        if (t != null) {
            loadedPmkid = t
            loadedHandshake = null
            _handshakeInfo.value = "${t.ssid} (PMKID)"
        } else {
            _handshakeInfo.value = "invalid PMKID line"
        }
    }

    /** Import a .pcap captured by the device and extract the WPA handshake. */
    fun importPcap(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val hs = HandshakeParser.parse(bytes)
                loadedHandshake = hs
                if (hs != null) loadedPmkid = null
                _handshakeInfo.value = hs?.let { "${it.ssid} (WPA${it.keyVersion})" } ?: "no handshake found in file"
            } catch (e: Exception) {
                _handshakeInfo.value = "read error"
            }
        }
    }

    fun refreshWordlists() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = WordlistStore.list(getApplication())
            _wordlists.value = list
            if (_bundledWordlists.value.isEmpty()) {
                val ctx = getApplication<Application>()
                _bundledWordlists.value = BundledWordlists.all.map {
                    BundledWordlistUi(it.asset, it.title, it.description, BundledWordlists.lines(ctx, it.asset))
                }
            }
        }
    }

    fun importWordlist(uri: Uri, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try { WordlistStore.importFrom(getApplication(), uri, name) } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "wordlist import failed", e) }
            _wordlists.value = WordlistStore.list(getApplication())
        }
    }

    fun deleteWordlist(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            WordlistStore.delete(getApplication(), name)
            _wordlists.value = WordlistStore.list(getApplication())
        }
    }

    /** A synthetic WPA2 handshake with a known passphrase, for demo/benchmark. */
    private fun sampleHandshake(pass: String): Handshake {
        val ap = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        val cl = byteArrayOf(0x66, 0x77, 0x2b.toByte(), 0x11, 0x22, 0x33)
        val aNonce = ByteArray(32) { it.toByte() }
        val sNonce = ByteArray(32) { (31 - it).toByte() }
        val eapol = ByteArray(99) { (it * 7 % 256).toByte() }
        val base = Handshake("BruciaDemoNet", ap, cl, aNonce, sNonce, eapol, ByteArray(16), 2)
        return base.copy(mic = WpaCracker.computeMic(pass, base))
    }

    /**
     * Stop a running crack. Sets the cooperative-cancel flag (so the CPU-bound loop stops within
     * one candidate), cancels the coroutine, and resets the UI to a stopped state ("Cancelled").
     * Leaves the cracker ready to start a fresh run.
     */
    fun cancelCrack() {
        crackCancelled.set(true)
        crackJob?.cancel()
        crackJob = null
        _crack.value = CrackStatus(running = false, finished = false, note = "Cancelled")
    }

    /** Demo: recover a known passphrase from an in-memory list - proves the full
     *  PMK/PTK/MIC pipeline + throughput on this phone, no capture needed. */
    fun demoCrack() {
        crackJob?.cancel()
        crackCancelled.set(false)
        _crack.value = CrackStatus(running = true, note = "demo (synthetic handshake)")
        crackJob = viewModelScope.launch(Dispatchers.Default) {
            val known = "brucia-demo-pass"
            val h = sampleHandshake(known)
            val list = buildList {
                repeat(400) { add("fillerpass%05d".format(it)) }
                add(known)
            }
            runCrack(h, list.asSequence(), list.size, "demo")
        }
    }

    /** Run an imported wordlist against the sample handshake (real throughput). */
    fun crackWithWordlist(name: String) {
        crackJob?.cancel()
        crackCancelled.set(false)
        _crack.value = CrackStatus(running = true, note = name)
        crackJob = viewModelScope.launch(Dispatchers.Default) {
            val total = _wordlists.value.firstOrNull { it.name == name }?.lines ?: 0
            val pm = loadedPmkid
            val h = loadedHandshake ?: sampleHandshake("not-in-any-wordlist-xyz")
            try {
                WordlistStore.file(getApplication(), name).bufferedReader().useLines { raw ->
                    val seq = if (_rules.value) raw.flatMap { WordlistRules.expand(it) } else raw
                    val t0 = System.currentTimeMillis()
                    val onP: (Int) -> Unit = { tried ->
                        val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                        _crack.value = CrackStatus(running = true, tried = tried, total = total, keysPerSec = (tried.toLong() * 1000 / dt).toInt(), note = name)
                    }
                    val res = if (pm != null) {
                        WpaCracker.crackPmkid(pm.ssid, pm.apMac, pm.staMac, pm.pmkid, seq, 100, onP, isCancelled = { crackCancelled.get() })
                    } else {
                        WpaCracker.crack(h, seq, 100, onP, isCancelled = { crackCancelled.get() })
                    }
                    if (crackCancelled.get()) return@useLines
                    val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                    _crack.value = CrackStatus(running = false, finished = true, tried = total, total = total, keysPerSec = (total.toLong() * 1000 / dt).toInt().coerceAtLeast(0), result = res, note = name)
                }
            } catch (_: Exception) {
                _crack.value = CrackStatus(finished = true, note = "read error")
            }
        }
    }

    /** Crack the loaded target using the bundled common-WPA wordlist (no import needed). */
    fun crackBuiltin() = crackBundled("common_wpa.txt", "built-in")

    /** Crack the loaded target using any bundled dictionary (no import needed). */
    fun crackBundled(asset: String, note: String = asset) {
        crackJob?.cancel()
        crackCancelled.set(false)
        val total = BundledWordlists.lines(getApplication(), asset)
        _crack.value = CrackStatus(running = true, total = total, note = note)
        crackJob = viewModelScope.launch(Dispatchers.Default) {
            val pm = loadedPmkid
            val h = loadedHandshake ?: sampleHandshake("not-in-any-wordlist-xyz")
            try {
                getApplication<Application>().assets.open(asset).bufferedReader().useLines { raw ->
                    val seq = if (_rules.value) raw.flatMap { WordlistRules.expand(it) } else raw
                    val t0 = System.currentTimeMillis()
                    val onP: (Int) -> Unit = { tried ->
                        val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                        _crack.value = CrackStatus(running = true, tried = tried, total = total, keysPerSec = (tried.toLong() * 1000 / dt).toInt(), note = note)
                    }
                    val res = if (pm != null) WpaCracker.crackPmkid(pm.ssid, pm.apMac, pm.staMac, pm.pmkid, seq, 100, onP, isCancelled = { crackCancelled.get() })
                              else WpaCracker.crack(h, seq, 100, onP, isCancelled = { crackCancelled.get() })
                    if (crackCancelled.get()) return@useLines
                    _crack.value = CrackStatus(running = false, finished = true, tried = total, total = total, result = res, note = note)
                }
            } catch (_: Exception) {
                _crack.value = CrackStatus(finished = true, note = "read error")
            }
        }
    }

    private fun runCrack(h: Handshake, candidates: Sequence<String>, total: Int, note: String) {
        val t0 = System.currentTimeMillis()
        val res = WpaCracker.crack(
            h, candidates, progressEvery = 100,
            onProgress = { tried ->
                val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                _crack.value = CrackStatus(
                    running = true, tried = tried, total = total,
                    keysPerSec = (tried.toLong() * 1000 / dt).toInt(), note = note,
                )
            },
            isCancelled = { crackCancelled.get() },
        )
        if (crackCancelled.get()) return
        val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1)
        _crack.value = CrackStatus(
            running = false, finished = true, tried = total, total = total,
            keysPerSec = (total.toLong() * 1000 / dt).toInt().coerceAtLeast(0),
            result = res, note = note,
        )
    }

    // --- Scripts (JS runner) and small device tools (iButton, clock) ---
    // Fixed contract (firmware built in parallel):
    //   script_run <js source>   -> ACK {ok, out:"<output>"}
    //   ibutton_read             -> ACK {ok, out:"id=.."}
    //   ibutton_write <idhex>    -> ACK {ok, out}
    //   clock_get                -> ACK {ok, out:"<iso>"}
    //   clock_set <epoch>        -> ACK {ok, out}

    private val _scriptOutput = MutableStateFlow("")
    val scriptOutput: StateFlow<String> = _scriptOutput.asStateFlow()

    private val _ibuttonStatus = MutableStateFlow("")
    val ibuttonStatus: StateFlow<String> = _ibuttonStatus.asStateFlow()

    private val _clockStatus = MutableStateFlow("")
    val clockStatus: StateFlow<String> = _clockStatus.asStateFlow()

    // --- Audio (speaker / mic) + ESP-NOW (fixed contract; firmware built in parallel) ---
    private val _speakerStatus = MutableStateFlow("")
    val speakerStatus: StateFlow<String> = _speakerStatus.asStateFlow()

    private val _micStatus = MutableStateFlow("")
    val micStatus: StateFlow<String> = _micStatus.asStateFlow()

    private val _espnowStatus = MutableStateFlow("")
    val espnowStatus: StateFlow<String> = _espnowStatus.asStateFlow()

    /** Run a JavaScript source on the device; the captured output lands on [scriptOutput]. */
    fun scriptRun(src: String) {
        if (client == null) { _scriptOutput.value = "Connect the device over USB first."; return }
        if (src.isBlank()) { _scriptOutput.value = "Nothing to run."; return }
        _scriptOutput.value = "Running..."
        dispatchCmd("script_run $src") { ok, out ->
            _scriptOutput.value = if (ok) out.ifBlank { "(no output)" } else out.ifBlank { "Script failed." }
        }
    }

    fun clearScriptOutput() { _scriptOutput.value = "" }

    /** Read an iButton / 1-Wire id; result surfaced on [ibuttonStatus]. */
    fun ibuttonRead() {
        if (client == null) { _ibuttonStatus.value = "Connect the device over USB first."; return }
        _ibuttonStatus.value = "Reading - touch an iButton to the probe..."
        dispatchCmd("ibutton_read") { ok, out ->
            _ibuttonStatus.value = if (ok) out.ifBlank { "No iButton read." } else out.ifBlank { "Read failed." }
        }
    }

    /** Write a hex id onto a writable iButton; result surfaced on [ibuttonStatus]. */
    fun ibuttonWrite(hex: String) {
        if (client == null) { _ibuttonStatus.value = "Connect the device over USB first."; return }
        if (hex.isBlank()) { _ibuttonStatus.value = "Enter an id to write."; return }
        _ibuttonStatus.value = "Writing $hex..."
        dispatchCmd("ibutton_write $hex") { ok, out ->
            _ibuttonStatus.value = if (ok) out.ifBlank { "Wrote $hex." } else out.ifBlank { "Write failed." }
        }
    }

    /** Read the device clock; result surfaced on [clockStatus]. */
    fun clockGet() {
        if (client == null) { _clockStatus.value = "Connect the device over USB first."; return }
        _clockStatus.value = "Reading clock..."
        dispatchCmd("clock_get") { ok, out ->
            _clockStatus.value = if (ok) out.ifBlank { "(no time)" } else out.ifBlank { "Clock read failed." }
        }
    }

    /** Set the device clock from a Unix epoch (seconds); result surfaced on [clockStatus]. */
    fun clockSet(epoch: Long) {
        if (client == null) { _clockStatus.value = "Connect the device over USB first."; return }
        _clockStatus.value = "Syncing clock..."
        dispatchCmd("clock_set $epoch") { ok, out ->
            _clockStatus.value = if (ok) out.ifBlank { "Clock synced." } else out.ifBlank { "Clock sync failed." }
        }
    }

    // --- Device theme / LED control (device UI color wins; app matches via DeviceInfo.themeRgb565) ---
    private val _themeStatus = MutableStateFlow("")
    val themeStatus: StateFlow<String> = _themeStatus.asStateFlow()

    /** Set the device UI theme color from an 0xRRGGBB value (converted to RGB565 for the device). */
    fun setDeviceTheme(rgb: Int) {
        if (client == null) { _themeStatus.value = "Connect the device over USB first."; return }
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        val rgb565 = ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or (b shr 3)
        dispatchCmd("theme_set %04x".format(rgb565)) { ok, out ->
            _themeStatus.value = out.ifBlank { if (ok) "Theme set." else "Failed." }
            // Reflect immediately in the app without waiting for the next CAPS.
            if (ok) _device.value = _device.value?.copy(themeRgb565 = rgb565)
        }
    }

    /** Set the device LED color (0xRRGGBB). */
    fun setDeviceLedColor(rgb: Int) {
        if (client == null) { _themeStatus.value = "Connect the device over USB first."; return }
        dispatchCmd("led_set %06x".format(rgb and 0xFFFFFF)) { ok, out ->
            _themeStatus.value = out.ifBlank { if (ok) "LED set." else "Failed." }
            if (ok) _device.value = _device.value?.copy(ledRgb = rgb and 0xFFFFFF)
        }
    }

    /** Persist the current device theme/LED config to flash (the Theme "Save" button). */
    fun saveDeviceConfig() {
        if (client == null) { _themeStatus.value = "Connect the device over USB first."; return }
        dispatchCmd("config_save") { ok, out ->
            _themeStatus.value = if (ok) "Saved to device." else "Save failed: ${out.ifBlank { "error" }}"
        }
    }

    /** Onboard LED brightness 0..100 %: led_bright <pct>. */
    fun setLedBright(pct: Int) {
        if (client == null) { _themeStatus.value = "Connect the device over USB first."; return }
        dispatchCmd("led_bright ${pct.coerceIn(0, 100)}") { ok, out ->
            _themeStatus.value = out.ifBlank { if (ok) "LED brightness set." else "Failed." }
        }
    }

    /** Onboard LED animation 0..9 (0=solid): led_effect <n>. */
    fun setLedEffect(effect: Int) {
        if (client == null) { _themeStatus.value = "Connect the device over USB first."; return }
        dispatchCmd("led_effect ${effect.coerceIn(0, 9)}") { ok, out ->
            _themeStatus.value = out.ifBlank { if (ok) "LED effect set." else "Failed." }
        }
    }

    /** Device screen brightness 0..100 %: scr_bright <pct>. */
    fun setScreenBright(pct: Int) {
        if (client == null) return
        dispatchCmd("scr_bright ${pct.coerceIn(0, 100)}")
    }

    // --- Speaker volume (bruceConfig.soundVolume 0-100 via companion `speaker_volume <pct>`) ---
    private val _speakerVolume = MutableStateFlow(100)
    val speakerVolume: StateFlow<Int> = _speakerVolume.asStateFlow()

    /** Set the device speaker volume (0-100). Persists in the device config. */
    fun setSpeakerVolume(pct: Int) {
        val v = pct.coerceIn(0, 100)
        _speakerVolume.value = v
        if (client == null) { _speakerStatus.value = "Connect the device over USB first."; return }
        dispatchCmd("speaker_volume $v") { ok, out ->
            _speakerStatus.value = if (ok) out.ifBlank { "Volume ${v}%" } else out.ifBlank { "Volume failed." }
        }
    }

    /** Play a tone on the device speaker: speaker_tone <freqHz> <ms>. Result on [speakerStatus]. */
    fun speakerTone(freqHz: Int, ms: Int) {
        if (client == null) { _speakerStatus.value = "Connect the device over USB first."; return }
        if (freqHz <= 0 || ms <= 0) { _speakerStatus.value = "Enter a frequency and duration."; return }
        _speakerStatus.value = "Playing ${freqHz}Hz for ${ms}ms..."
        dispatchCmd("speaker_tone $freqHz $ms") { ok, out ->
            _speakerStatus.value = if (ok) out.ifBlank { "Tone played." } else out.ifBlank { "Tone failed." }
        }
    }

    // --- Song player: sequence speaker_tone commands to play a melody (RTTTL / meme presets) ---
    private var songJob: Job? = null
    private val _songPlaying = MutableStateFlow("")
    val songPlaying: StateFlow<String> = _songPlaying.asStateFlow()

    /** Play a melody on the device by sending the whole RTTTL string to the native on-device player
     *  (one command -> correct timing, no per-note USB round-trips). [name] for status. */
    fun playSong(name: String, rtttl: String) {
        if (client == null) { _speakerStatus.value = "Connect the device over USB first."; return }
        _songPlaying.value = name
        // Play in the BACKGROUND on the device (speaker_song = async) so Stop (speaker_stop) can
        // actually interrupt it. The old blocking speaker_rtttl kept the device busy, so cancelling
        // the app coroutine did nothing and the melody played to the end.
        dispatchCmd("speaker_song $rtttl") { ok, out ->
            if (!ok) { _songPlaying.value = ""; _speakerStatus.value = out.ifBlank { "Song failed." } }
        }
    }

    fun stopSong() {
        songJob?.cancel()
        songJob = null
        _songPlaying.value = ""
        if (client != null) dispatchCmd("speaker_stop")
    }

    /** Play an audio file on the device speaker: speaker_play <path>. Result on [speakerStatus]. */
    fun speakerPlay(path: String) {
        if (client == null) { _speakerStatus.value = "Connect the device over USB first."; return }
        val p = path.trim()
        if (p.isEmpty()) { _speakerStatus.value = "Enter a file path first."; return }
        _speakerStatus.value = "Playing $p..."
        dispatchCmd("speaker_play $p") { ok, out ->
            _speakerStatus.value = if (ok) out.ifBlank { "Playback started." } else out.ifBlank { "Playback failed." }
        }
    }

    /** Record from the device mic for [secs] seconds: mic_record <secs>. Saved path on [micStatus]. */
    fun micRecord(secs: Int) {
        if (client == null) { _micStatus.value = "Connect the device over USB first."; return }
        if (secs <= 0) { _micStatus.value = "Enter a duration in seconds."; return }
        _micStatus.value = "Recording ${secs}s..."
        dispatchCmd("mic_record $secs") { ok, out ->
            _micStatus.value = if (ok) out.ifBlank { "Recording saved." } else out.ifBlank { "Recording failed." }
        }
    }

    // --- Wi-Fi MAC (Bruce "Change MAC" parity) --------------------------------
    private val _wifiMac = MutableStateFlow("")
    val wifiMac: StateFlow<String> = _wifiMac.asStateFlow()

    /** Ask the device for its current STA MAC: wifi_get_mac. */
    fun getWifiMac() {
        if (client == null) { _wifiMac.value = "Connect the device over USB first."; return }
        dispatchCmd("wifi_get_mac") { ok, out ->
            _wifiMac.value = if (ok) out.trim() else "error: ${out.ifBlank { "failed" }}"
        }
    }

    /** Set the device STA MAC: wifi_set_mac <mac|random>. Result on [wifiMac]. */
    fun setWifiMac(mac: String) {
        if (client == null) { _wifiMac.value = "Connect the device over USB first."; return }
        val m = mac.trim().ifBlank { "random" }
        _wifiMac.value = "Setting..."
        dispatchCmd("wifi_set_mac $m") { ok, out ->
            _wifiMac.value = if (ok) out.trim().ifBlank { "MAC set." } else "error: ${out.ifBlank { "failed" }}"
        }
    }

    // --- Brucegotchi (Pwnagotchi parity): headless WPA-handshake collector -----
    private val _brucegotchiStatus = MutableStateFlow("")
    val brucegotchiStatus: StateFlow<String> = _brucegotchiStatus.asStateFlow()

    // --- BLE HID presenter / keyboard (parity with Bruce BLE Keyboard / Presenter) ---
    private val _bleHidStatus = MutableStateFlow("off")
    val bleHidStatus: StateFlow<String> = _bleHidStatus.asStateFlow()
    fun bleHidBegin() {
        if (client == null) { _bleHidStatus.value = "Connect the device over USB first."; return }
        _bleHidStatus.value = "Starting..."
        dispatchCmd("ble_hid_begin") { ok, out -> _bleHidStatus.value = if (ok) out.ifBlank { "advertising" } else out.ifBlank { "failed" } }
    }
    fun bleHidEnd() { if (client != null) dispatchCmd("ble_hid_end") { _, _ -> _bleHidStatus.value = "off" } }
    fun bleHidRefresh() { if (client != null) dispatchCmd("ble_hid_status") { ok, out -> if (ok) _bleHidStatus.value = out } }
    fun bleHidKey(name: String) { if (client != null) dispatchCmd("ble_hid_key $name") { ok, out -> if (!ok) _bleHidStatus.value = out } }
    fun bleHidType(text: String) { if (client != null && text.isNotEmpty()) dispatchCmd("ble_hid_type $text") { ok, out -> if (!ok) _bleHidStatus.value = out } }

    // --- Responder (LLMNR/NBT-NS poisoning), headless. Authorized use only; needs Wi-Fi joined. ---
    /**
     * Early-stop for the long one-shot blocking ops (karma / responder / jam_detect / brucegotchi):
     * sends a generic "stop" the firmware polls mid-run. Sent directly (not via the serialized queue,
     * which is blocked behind the still-running command).
     */
    fun stopLongOp() {
        val c = client ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try { c.cmd("stop") } catch (e: Exception) { app.brucia.BruciaLog.d("Op", "stop: ${e.message}") }
        }
    }

    private val _responderStatus = MutableStateFlow("")
    val responderStatus: StateFlow<String> = _responderStatus.asStateFlow()
    private val _responderRunning = MutableStateFlow(false)
    val responderRunning: StateFlow<Boolean> = _responderRunning.asStateFlow()
    fun startResponder(secs: Int) {
        if (client == null) { _responderStatus.value = "Connect the device over USB first."; return }
        _responderRunning.value = true
        _responderStatus.value = "Running ${secs}s - join the device to the target Wi-Fi first (Connect tool). Answering LLMNR/NBT-NS name queries with the device IP (authorized use)... (Stop to end early)"
        dispatchCmd("responder $secs") { ok, out ->
            _responderRunning.value = false
            _responderStatus.value = if (ok) "Done - ${out.ifBlank { "finished" }}" else "Failed: ${out.ifBlank { "error" }}"
        }
    }

    // --- Karma (evil-twin probe-response lure), headless. Authorized use only. ---
    private val _karmaStatus = MutableStateFlow("")
    val karmaStatus: StateFlow<String> = _karmaStatus.asStateFlow()
    private val _karmaRunning = MutableStateFlow(false)
    val karmaRunning: StateFlow<Boolean> = _karmaRunning.asStateFlow()

    /** Run the headless Karma attack for [secs] seconds: karma <secs>. Authorized use only. */
    fun startKarma(secs: Int) {
        if (client == null) { _karmaStatus.value = "Connect the device over USB first."; return }
        _karmaRunning.value = true
        _karmaStatus.value = "Running ${secs}s - the device answers clients' probe requests to lure them (authorized use). The device screen shows Karma... (Stop to end early)"
        dispatchCmd("karma $secs") { ok, out ->
            _karmaRunning.value = false
            _karmaStatus.value = if (ok) "Done - ${out.ifBlank { "finished" }}" else "error: ${out.ifBlank { "failed" }}"
        }
    }

    private val _brucegotchiRunning = MutableStateFlow(false)
    val brucegotchiRunning: StateFlow<Boolean> = _brucegotchiRunning.asStateFlow()

    /** Run the headless Brucegotchi for [secs] seconds: brucegotchi <secs>. Authorized use only. */
    fun startBrucegotchi(secs: Int) {
        if (client == null) { _brucegotchiStatus.value = "Connect the device over USB first."; return }
        _brucegotchiRunning.value = true
        _brucegotchiStatus.value = "Running ${secs}s - the device screen shows the gotchi, handshakes save to /BrucePCAP/handshakes... (Stop to end early)"
        dispatchCmd("brucegotchi $secs") { ok, out ->
            _brucegotchiRunning.value = false
            _brucegotchiStatus.value = if (ok) "Done - $out" else "error: ${out.ifBlank { "failed" }}"
        }
    }

    /**
     * Stop a running Brucegotchi early. Sent as a direct framed command (NOT via the serialized
     * command queue, which is blocked behind the still-running brucegotchi): the firmware polls the
     * serial for this during its run and ends the loop.
     */
    fun stopBrucegotchi() {
        val c = client ?: return
        _brucegotchiStatus.value = "Stopping..."
        viewModelScope.launch(Dispatchers.IO) {
            try { c.cmd("brucegotchi_stop") } catch (e: Exception) { app.brucia.BruciaLog.d("Brucegotchi", "stop: ${e.message}") }
        }
    }

    // --- Wi-Fi station join (Bruce "Connect to Wifi" parity) ------------------
    private val _wifiJoinStatus = MutableStateFlow("")
    val wifiJoinStatus: StateFlow<String> = _wifiJoinStatus.asStateFlow()

    /** Ask the device whether its station is connected: wifi_status. */
    fun wifiStatus() {
        if (client == null) { _wifiJoinStatus.value = "Connect the device over USB first."; return }
        dispatchCmd("wifi_status") { ok, out ->
            _wifiJoinStatus.value = if (ok) out.trim() else "error: ${out.ifBlank { "failed" }}"
        }
    }

    /** Join an AP: wifi_connect <ssid>\x1f<pass> (0x1F lets ssid/pass contain spaces). */
    fun wifiJoin(ssid: String, pass: String) {
        if (client == null) { _wifiJoinStatus.value = "Connect the device over USB first."; return }
        val s = ssid.trim()
        if (s.isEmpty()) { _wifiJoinStatus.value = "Enter an SSID first."; return }
        _wifiJoinStatus.value = "Connecting (up to ~10s)..."
        dispatchCmd("wifi_connect $s\u001f$pass") { ok, out ->
            _wifiJoinStatus.value = if (ok) "Connected - IP ${out.trim()}" else "Failed: ${out.ifBlank { "timeout" }}"
        }
    }

    /** Drop the device's station link: wifi_disconnect. */
    fun wifiLeave() {
        if (client == null) { _wifiJoinStatus.value = "Connect the device over USB first."; return }
        dispatchCmd("wifi_disconnect") { ok, out ->
            _wifiJoinStatus.value = if (ok) "Disconnected." else "error: ${out.ifBlank { "failed" }}"
        }
    }

    /** Broadcast a message over ESP-NOW: espnow_send <message>. Result on [espnowStatus]. */
    fun espnowSend(message: String) {
        if (client == null) { _espnowStatus.value = "Connect the device over USB first."; return }
        val m = message.trim()
        if (m.isEmpty()) { _espnowStatus.value = "Enter a message first."; return }
        _espnowStatus.value = "Broadcasting..."
        dispatchCmd("espnow_send $m") { ok, out ->
            _espnowStatus.value = if (ok) out.ifBlank { "Broadcast sent." } else out.ifBlank { "Broadcast failed." }
        }
    }

    // --- Audio suite: device-mic PCM stream + speaker PCM playback -------------
    // The audio source is the ESP device mic (stream "mic.pcm") and the ESP speaker
    // (spkr_pcm_* commands). All DSP (FFT, SSTV, tone/DTMF) runs on the phone. Format
    // everywhere: PCM S16LE mono @ 16000 Hz.

    /** Latest decoded device-mic PCM chunk (S16LE mono @16k), fanned out to the Audio tools. */
    private val _micPcm = MutableStateFlow(ShortArray(0))
    val micPcm: StateFlow<ShortArray> = _micPcm.asStateFlow()
    private val _micPcmActive = MutableStateFlow(false)
    val micPcmActive: StateFlow<Boolean> = _micPcmActive.asStateFlow()

    // --- Recording: capture the raw mic.pcm to a buffer, then export WAV + per-type PNGs on stop ---
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    private val recBuf = java.io.ByteArrayOutputStream()
    // Cap to ~10 min (16kHz * 2B * 600s ~= 19 MB) so a forgotten recording can't exhaust memory.
    private val REC_MAX_BYTES = 16000 * 2 * 600

    /** Start capturing the device mic to memory. Ensures the mic stream is live first. */
    fun startRecording() {
        if (client == null) { _micPcmStatus.value = "Connect the device over USB first."; return }
        if (!_micPcmActive.value) startMicPcm()
        synchronized(recBuf) { recBuf.reset() }
        _recording.value = true
    }

    /**
     * Stop capturing and export the recording off the main thread: a standard WAV plus one PNG per
     * visualization type (waveform, spectrum, spectrogram). [onDone] gets the written files (empty on
     * failure / no audio) back on the main thread for the share sheet.
     */
    fun stopRecordingAndExport(context: android.content.Context, onDone: (List<java.io.File>) -> Unit) {
        _recording.value = false
        val pcm = synchronized(recBuf) { recBuf.toByteArray().also { recBuf.reset() } }
        if (pcm.size < 4096) { onDone(emptyList()); return }
        viewModelScope.launch(Dispatchers.Default) {
            val files = try {
                app.brucia.audio.AudioExport.saveAll(context, pcm, 16000).files
            } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) { onDone(files) }
        }
    }

    /** Drop an in-progress recording without exporting (e.g. leaving the Audio screen). */
    fun cancelRecording() {
        _recording.value = false
        synchronized(recBuf) { recBuf.reset() }
    }

    /** Status line shared by the Audio tools (mic/record). */
    private val _micPcmStatus = MutableStateFlow("")
    val micPcmStatus: StateFlow<String> = _micPcmStatus.asStateFlow()

    /** Subscribe the device-mic PCM stream (caps.mic). Safe to call repeatedly. */
    fun startMicPcm() {
        if (client == null) return
        if (_device.value?.caps?.mic != true) return
        // Spam loops and the live mic.pcm stream contend for the single-threaded companion loop on
        // current firmware (spam running => mic.pcm never serviced => audio tools show nothing), so
        // opening an audio tool auto-stops any running spam.
        stopAllBurstLoops(silent = false)
        setStream("mic.pcm", true)
    }

    /** Unsubscribe the device-mic PCM stream. */
    fun stopMicPcm() {
        setStream("mic.pcm", false)
        _micPcmActive.value = false
    }

    // --- Phone-microphone capture (only for the XY dual-mic vectorscope) ---
    //
    // A second, independent audio source: the PHONE's own mic via AudioRecord (16 kHz mono), used
    // solely as the Y channel of the true 2-channel vectorscope (ESP mic = X). Every other Audio tool
    // stays on the ESP mic.pcm stream. The two streams are free-running (not sample-locked).

    private val _phoneMicPcm = MutableStateFlow(ShortArray(0))
    val phoneMicPcm: StateFlow<ShortArray> = _phoneMicPcm.asStateFlow()
    private var phoneMicJob: Job? = null

    /** Start capturing the phone mic (needs RECORD_AUDIO). No-op if already running or unavailable. */
    fun startPhoneMic() {
        if (phoneMicJob != null) return
        phoneMicJob = viewModelScope.launch(Dispatchers.IO) {
            val reader = PcmSource.MicReader()
            if (!reader.start()) { phoneMicJob = null; return@launch }
            try {
                val buf = ShortArray(1024)
                while (isActive) {
                    val n = reader.read(buf)
                    if (n > 0) _phoneMicPcm.value = buf.copyOf(n) else if (n < 0) break
                }
            } finally {
                reader.close()
            }
        }
    }

    /** Stop the phone-mic capture started by [startPhoneMic]. */
    fun stopPhoneMic() {
        phoneMicJob?.cancel()
        phoneMicJob = null
    }

    // --- Speaker PCM playback (modelled on fm_audio_*; routed to the I2S speaker) ---

    /** Open the device speaker for streamed PCM at [sr] Hz. */
    fun spkrPcmBegin(sr: Int = PcmSource.SAMPLE_RATE, cb: ((Boolean, String) -> Unit)? = null) =
        dispatchCmd("spkr_pcm_begin $sr", cb)

    /** Enqueue one PCM chunk (S16LE mono) to the device speaker (base64, <=2048 samples/frame). */
    fun spkrPcmData(bytes: ByteArray) =
        dispatchCmd("spkr_pcm_data " + Base64.encodeToString(bytes, Base64.NO_WRAP))

    /** Enqueue one PCM chunk given as shorts. */
    fun spkrPcmData(samples: ShortArray, count: Int = samples.size) =
        spkrPcmData(PcmSource.shortsToLittleEndian(samples, count))

    /** Flush and close the device speaker. */
    fun spkrPcmStop(cb: ((Boolean, String) -> Unit)? = null) = dispatchCmd("spkr_pcm_stop", cb)

    /**
     * Stream a full PCM clip to the device speaker in <=2048-sample frames, paced to real time
     * so the device I2S buffer never overruns. Drives [sstvTx] progress. Shared by SSTV TX and
     * DTMF/tone synthesis.
     */
    private fun streamPcmToSpeaker(pcm: ShortArray, label: String) {
        val c = client ?: run { _sstvTx.value = SstvTxState(message = "Connect the device over USB first."); return }
        if (_sstvTx.value.active) return
        if (_device.value?.caps?.speaker == false) { _sstvTx.value = SstvTxState(message = "This device has no speaker."); return }
        _sstvTx.value = SstvTxState(active = true, progress = 0f, message = "$label...")
        sstvTxJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                spkrPcmBegin()
                val frame = 2048 // <=2048 samples => raw <=4096 bytes => base64 <COMPANION_MAX_FRAME
                val total = pcm.size
                var pos = 0
                val startMs = System.currentTimeMillis()
                while (pos < total && isActive && _sstvTx.value.active) {
                    val n = minOf(frame, total - pos)
                    val chunk = pcm.copyOfRange(pos, pos + n)
                    c.cmd("spkr_pcm_data " + Base64.encodeToString(PcmSource.shortsToLittleEndian(chunk, n), Base64.NO_WRAP))
                    pos += n
                    _sstvTx.value = _sstvTx.value.copy(progress = pos.toFloat() / total)
                    // Pace to 16 kHz playback.
                    val sleep = (pos.toLong() * 1000 / PcmSource.SAMPLE_RATE) - (System.currentTimeMillis() - startMs)
                    if (sleep > 0) delay(sleep)
                }
            } catch (_: Exception) {
            } finally {
                try { client?.cmd("spkr_pcm_stop") } catch (e: Exception) { app.brucia.BruciaLog.d("VM", "spkr_pcm_stop: ${e.message}") }
                val finished = _sstvTx.value.active
                _sstvTx.value = SstvTxState(active = false, progress = if (finished) 1f else _sstvTx.value.progress,
                    message = if (finished) "Finished." else "Stopped.")
            }
        }
    }

    private var sstvTxJob: Job? = null
    private val _sstvTx = MutableStateFlow(SstvTxState())
    val sstvTx: StateFlow<SstvTxState> = _sstvTx.asStateFlow()

    /** Encode [img] to SSTV audio ([mode]) on the phone and stream it to the device speaker. */
    fun sstvTransmit(img: Sstv.Image, mode: Sstv.Mode) {
        viewModelScope.launch(Dispatchers.IO) {
            _sstvTx.value = SstvTxState(active = false, message = "Encoding ${mode.name}...")
            val pcm = try { Sstv.encode(img, mode) } catch (e: Exception) {
                _sstvTx.value = SstvTxState(message = "Encode failed: ${e.message}"); return@launch
            }
            streamPcmToSpeaker(pcm, "Transmitting ${mode.name}")
        }
    }

    /** Stop an in-progress speaker stream (SSTV TX / DTMF). */
    fun sstvTransmitStop() {
        _sstvTx.value = _sstvTx.value.copy(active = false)
        sstvTxJob?.cancel()
        sstvTxJob = null
        client?.let { c -> viewModelScope.launch(Dispatchers.IO) { try { c.cmd("spkr_pcm_stop") } catch (e: Exception) { app.brucia.BruciaLog.d("VM", "spkr_pcm_stop: ${e.message}") } } }
    }

    /**
     * Play a DTMF digit natively on the device speaker (speaker_dtmf). The firmware synthesizes the
     * dual tone straight to I2S at full scale (gain carries the volume, like the RTTTL songs) so it
     * is actually audible - the earlier generator path came out ~10x too quiet.
     */
    fun sendDtmf(key: Char, ms: Int = 200) {
        if (client == null) { _themeStatus.value = "Connect the device over USB first."; return }
        dispatchCmd("speaker_dtmf $key $ms")
    }

    // --- DTMF decoder: ESP mic in listening mode -> Goertzel decode on the phone -> digits -------
    // The device mic chunks arrive on [micPcm]; we run each through the Goertzel detector and build
    // up the heard string. A digit is only appended when it differs from the one currently held, and
    // a short silence gap resets that, so two separate presses of the same key both register.
    private val _dtmfHeard = MutableStateFlow("")
    val dtmfHeard: StateFlow<String> = _dtmfHeard.asStateFlow()
    private var dtmfJob: Job? = null
    private var dtmfHeldDigit: Char? = null
    private var dtmfSilenceChunks = 0
    private val _dtmfListening = MutableStateFlow(false)
    val dtmfListening: StateFlow<Boolean> = _dtmfListening.asStateFlow()

    /**
     * Start decoding DTMF from the ESP mic. Owns the mic lifecycle: it turns the mic.pcm stream on
     * (mic and speaker are half-duplex, so the speaker tools pause while listening) and off on stop.
     * Decodes an EXTERNAL DTMF source held near the mic - not the device's own keypad.
     */
    fun startDtmfDecode() {
        startMicPcm()
        _dtmfListening.value = true
        if (dtmfJob != null) return
        dtmfHeldDigit = null
        dtmfSilenceChunks = 0
        dtmfJob = viewModelScope.launch {
            micPcm.collect { pcm ->
                if (pcm.size < 160) return@collect
                val d = app.brucia.audio.Dtmf.detect(pcm, 16000, ratio = 2.5, minPower = 2e-6)
                if (d == null) {
                    if (++dtmfSilenceChunks >= 2) dtmfHeldDigit = null // gap: next repeat is a new press
                } else {
                    dtmfSilenceChunks = 0
                    if (d != dtmfHeldDigit) {
                        dtmfHeldDigit = d
                        _dtmfHeard.value = (_dtmfHeard.value + d).takeLast(64)
                    }
                }
            }
        }
    }

    fun stopDtmfDecode() {
        dtmfJob?.cancel()
        dtmfJob = null
        _dtmfListening.value = false
        stopMicPcm()
    }

    fun clearDtmfHeard() { _dtmfHeard.value = "" }

    // SSTV receive now runs as an always-listening streaming decoder (Sstv.StreamDecoder) driven from
    // the shared micPcm flow inside the Audio UI (all modes in parallel), so no per-mode RX state lives
    // in the ViewModel. Transmit (sstvTransmit) stays here because it is phone->device output.

    override fun onCleared() {
        fused.removeLocationUpdates(locationCallback)
        sstvTxJob?.cancel()
        stopPhoneMic()
        disconnect()
    }

    // IMPORTANT: this init block is deliberately the LAST thing in the class. It calls the load*()
    // helpers, which read `by lazy` SharedPreferences delegates (remotePrefs/songPrefs/macroPrefs/
    // portalPrefs/fpPrefs) declared further up. Kotlin runs init blocks and property initializers in
    // declaration order, so init MUST come after those delegates - otherwise their backing Lazy field
    // is still null when init runs and the app crashes on startup with a NullPointerException in
    // Lazy.getValue() (the 0.8.14/0.8.15 "crashes on open" bug).
    init {
        // Load the OUI vendor database off the main thread.
        viewModelScope.launch(Dispatchers.IO) { OuiResolver.ensureLoaded(getApplication()) }
        loadRemoteKeys() // restore the user's custom IR remote layout
        loadCustomSongs() // restore the user's composed melodies
        loadMacros() // restore the user's saved automation macros
        loadSavedPortals() // restore the user's saved captive-portal templates
        loadFingerprints() // restore saved RF place-fingerprints
        loadGeoSignals() // restore geofenced signals
    }
}
