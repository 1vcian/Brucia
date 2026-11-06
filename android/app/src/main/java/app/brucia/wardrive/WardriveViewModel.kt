package app.brucia.wardrive

import android.annotation.SuppressLint
import android.app.Application
import android.net.Uri
import android.util.Base64
import android.location.Location
import android.os.Looper
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.brucia.data.ApEntity
import app.brucia.data.BruciaDb
import app.brucia.files.DeviceFileReader
import app.brucia.files.FileReadResult
import app.brucia.files.FileTransferLink
import app.brucia.files.FileTransferService
import app.brucia.files.DeviceFileBrowser
import app.brucia.files.SessionFileLink
import app.brucia.remote.RemoteHandover
import app.brucia.utils.FileRetention
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.brucia.ble.BleDevice
import app.brucia.automation.MacroRunner
import app.brucia.nfc.MifareKeyCheckService
import app.brucia.session.CommandTasks
import app.brucia.protocol.CommandCapabilities
import app.brucia.protocol.LinkCounters
import app.brucia.protocol.LinkCountersSnapshot
import app.brucia.session.StreamSubscriptions
import app.brucia.session.ServiceLifecycle
import app.brucia.session.ServiceLifecycle.Entry
import app.brucia.session.CommandSession
import app.brucia.nfc.NfcSecurity
import app.brucia.mitm.MitmLog
import app.brucia.report.ReportBuilder
import app.brucia.report.ReportData
import app.brucia.report.SessionExport
import app.brucia.report.SessionReport
import app.brucia.files.FileList
import app.brucia.wifi.BeaconSpam
import app.brucia.wifi.CaptureWorkflow
import app.brucia.wifi.CaptureLink
import app.brucia.wifi.HandshakeFlow
import app.brucia.wifi.DictionaryTestService
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
import app.brucia.BruciaLog
import app.brucia.audio.MicRecordService
import app.brucia.ble.BadBleService
import app.brucia.ble.GattService as GattExplorer
import app.brucia.ble.BleHidService
import app.brucia.ble.BleMediaService
import app.brucia.audio.SpeakerService
import app.brucia.audio.PcmStreamService
import app.brucia.audio.PcmStreamSpec
import app.brucia.audio.PcmStreamSource
import app.brucia.audio.SessionPcmLink
import app.brucia.device.ClockService
import app.brucia.device.DiagnosticsService
import app.brucia.device.DeviceAppearanceService
import app.brucia.device.DeviceOutputs
import app.brucia.device.EspNowService
import app.brucia.device.ScriptService
import app.brucia.ibutton.IButtonService
import app.brucia.ir.IrReceiveService
import app.brucia.ir.TvBGoneService
import app.brucia.lora.LoraService
import app.brucia.monitor.JamDetectService
import app.brucia.monitor.WidsService
import app.brucia.net.EthernetService
import app.brucia.nfc.NfcToolsService
import app.brucia.nfc.NfcTagService
import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.session.BurstLoop
import app.brucia.session.BurstLoopRegistry
import app.brucia.session.CommandRunner
import app.brucia.subghz.SubGhzBruteforceService
import app.brucia.subghz.DeBruijnTx
import app.brucia.subghz.DeBruijnTxService
import app.brucia.subghz.RfReceiveService
import app.brucia.session.JsonCommandRunner
import app.brucia.wifi.BrucegotchiService
import app.brucia.wifi.EvilPortalService
import app.brucia.wifi.KarmaService
import app.brucia.wifi.ResponderService
import app.brucia.wifi.WifiJoinService
import app.brucia.wifi.WifiMacService
import app.brucia.ir.IrButton
import app.brucia.subghz.DecodedSignal
import app.brucia.subghz.OokSamples
import app.brucia.subghz.SubFile
import app.brucia.subghz.SubGhzDecoder
import app.brucia.subghz.RawCompleteness
import app.brucia.subghz.SubGhzRawEvent
import app.brucia.subghz.SubGhzReceiveState
import app.brucia.subghz.SubSignal
import app.brucia.transport.Transport
import app.brucia.transport.FlashPortOwner
import app.brucia.transport.PortLease
import app.brucia.transport.launchFlashOwned
import app.brucia.update.AppDownloadService
import app.brucia.update.AppUpdater
import app.brucia.update.DownloadIO
import app.brucia.update.ReleaseCatalog
import app.brucia.update.ReleaseUpdateService
import app.brucia.update.EspFlasher
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import app.brucia.audio.RecordingBuffer
import app.brucia.audio.PcmSource
import app.brucia.audio.Dtmf
import app.brucia.audio.Sstv
import app.brucia.service.ConnectionService
import app.brucia.service.ConnectionServiceCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import org.json.JSONArray
import org.json.JSONObject

/** Cap on buffered Sub-GHz capture rows, so a long recording cannot exhaust memory. */
private fun companionNowMs(): Long = System.nanoTime() / 1_000_000L

private const val MAX_RF_CAPTURE_ROWS = 5000
// Bootloader reset + re-enumeration (~12 s) plus the USB permission dialog (60 s) plus margin.
private const val FLASH_HANDOFF_TIMEOUT_MS = 90_000L
// The companion open (openDevice + port open) is synchronous; the claim only bounds a lost abort.
private const val COMPANION_OPEN_TIMEOUT_MS = 10_000L
// `diag` only reads RAM counters; a busy device answers ERR 409 at once.
private const val DIAG_TIMEOUT_MS = 5_000L
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
data class SpectrumSweep(val freqs: LongArray, val rssi: IntArray, val time: Long) {
    companion object {
        /**
         * One per-sweep EVENT `data` ({start, step, rssi:[...]}) as a sweep, or null when it has no
         * rssi array. The firmware reports `start`/`step` in kHz (proto/PROTOCOL.md); [freqs] are Hz,
         * so point i is (start + i * step) * 1000 Hz. (Treating kHz as Hz labelled a 300-348 MHz
         * sweep as 0.30-0.35 MHz.)
         */
        fun fromSweepEvent(data: org.json.JSONObject, time: Long): SpectrumSweep? {
            val rssiArr = data.optJSONArray("rssi") ?: return null
            val startKHz = data.optLong("start")
            val stepKHz = data.optLong("step")
            val n = rssiArr.length()
            return SpectrumSweep(
                LongArray(n) { (startKHz + it.toLong() * stepKHz) * 1000L },
                IntArray(n) { rssiArr.optInt(it) },
                time,
            )
        }
    }
}

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

/** Owns the companion connection, the GPS fix, and persists AP observations. */
class WardriveViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BruciaDb.get(app).apDao()

    private val _status = MutableStateFlow("Disconnected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    // Recovery diagnostics: app link counters across every link since the app started, and the
    // last device `diag` result (fetched only on Refresh, kept after disconnect for exports).
    private val linkCounters = LinkCounters()
    private val _appDiag = MutableStateFlow(linkCounters.snapshot())
    val appDiag: StateFlow<LinkCountersSnapshot> = _appDiag.asStateFlow()
    private val _deviceDiag = MutableStateFlow<DeviceDiagState>(DeviceDiagState.NoDevice)
    val deviceDiag: StateFlow<DeviceDiagState> = _deviceDiag.asStateFlow()

    private val _transportKind = MutableStateFlow("none")
    val transportKind: StateFlow<String> = _transportKind.asStateFlow()

    private val _wardriving = MutableStateFlow(false)
    val wardriving: StateFlow<Boolean> = _wardriving.asStateFlow()

    /** ble.scan is on (or its ON awaits the ACK); follows the real SUB outcome like [wardriving]. */
    private val _bleScanning = MutableStateFlow(false)
    val bleScanning: StateFlow<Boolean> = _bleScanning.asStateFlow()

    private val _subghz = MutableStateFlow<List<DecodedSignal>>(emptyList())
    val subghz: StateFlow<List<DecodedSignal>> = _subghz.asStateFlow()

    private val _rfWave = MutableStateFlow(IntArray(0))
    val rfWave: StateFlow<IntArray> = _rfWave.asStateFlow()
    // Completeness metadata of the burst shown in [rfWave]; NO_INFO for samples, imports and old firmware.
    private val _rfWaveCompleteness = MutableStateFlow(RawCompleteness.NO_INFO)
    val rfWaveCompleteness: StateFlow<RawCompleteness> = _rfWaveCompleteness.asStateFlow()

    // Sub-GHz receive streams ("Listen" = subghz.raw, spectrum): state follows the SUB outcome.
    private val subghzRx = SubGhzReceiveState()
    val subghzListening: StateFlow<Boolean> = subghzRx.listening
    val subghzRawError: StateFlow<String> = subghzRx.rawError
    val spectrumError: StateFlow<String> = subghzRx.spectrumError

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
    val spectrumActive: StateFlow<Boolean> get() = subghzRx.spectrumActive
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
    // way to stop. Instead we run a cancellable loop of SHORT (~8s) bursts back to back: between
    // bursts the device + link are free, so the user can Stop (<=2s latency) and the mic.pcm
    // audio tools get serviced again. [bleSpamActive] drives the UI Start/Stop toggle.
    private val bleSpamLoop by lazy { burstLoop(_bleSpamStatus) }
    val bleSpamActive: StateFlow<Boolean> get() = bleSpamLoop.active

    // Wi-Fi beacon spam uses the same abortable short-burst loop model as BLE spam above.
    private val _beaconStatus = MutableStateFlow("")
    val beaconStatus: StateFlow<String> = _beaconStatus.asStateFlow()
    private val beaconLoop by lazy { burstLoop(_beaconStatus) }
    val beaconActive: StateFlow<Boolean> get() = beaconLoop.active

    // Batched MIFARE key-dictionary attack state + optional user-imported keys.
    private val mifareKeyCheck by lazy {
        MifareKeyCheckService(viewModelScope, connect = {
            val captured = session
            if (captured == null) null else JsonCommandRunner { cmd, timeout ->
                withContext(Dispatchers.IO) { sendAwaitJson(cmd, timeout, captured) }
            }
        },
        // Only send the in-flight stop frame when the firmware advertises it polls one mid-batch.
        stopSupported = { _device.value?.features?.mifareKeycheckStop == true },
        // Direct, unlogged stop channel (same sender the responder/karma long ops use).
        stopSender = directStop)
    }
    val mifare get() = mifareKeyCheck.status
    private val _mifareUserKeys = MutableStateFlow<List<String>>(emptyList())
    val mifareUserKeys: StateFlow<List<String>> = _mifareUserKeys.asStateFlow()

    private val _console = MutableStateFlow<List<String>>(emptyList())
    val console: StateFlow<List<String>> = _console.asStateFlow()

    private val _irLearned = MutableStateFlow<List<IrButton>>(emptyList())
    val irLearned: StateFlow<List<IrButton>> = _irLearned.asStateFlow()

    // IR receive/capture and TV-B-Gone status lines live in IrReceiveService / TvBGoneService.

    private val savedDao = BruciaDb.get(app).savedDao()
    val savedNfc: StateFlow<List<SavedItem>> =
        savedDao.observe("nfc").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val savedRf: StateFlow<List<SavedItem>> =
        savedDao.observe("rf").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val savedIr: StateFlow<List<SavedItem>> =
        savedDao.observe("ir").stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        wiglePrefs.edit { putString("api_name", name); putString("api_token", token) }
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

    // Command/reply correlation for the current link; replaced (and closed) on every connect/disconnect.
    @Volatile private var session: app.brucia.session.CommandSession? = null
    // Per-pull byte buffers keyed by the fs_read request id.
    private val fileReader = DeviceFileReader()
    private val fileTransferMutex = Mutex()
    private val subscriptions by lazy {
        StreamSubscriptions(viewModelScope, { session }, { _device.value?.caps },
            onState = ::updateStreamState,
            onChanged = ::refreshConnectionNotification,
            onError = { stream, message ->
                if (stream == "mic.pcm") _micPcmStatus.value = message
                subghzRx.onError(stream, message)
            },
            onStart = { stream, on -> logCmdStart("SUB $stream ${if (on) "on" else "off"}") },
            onFinish = ::logCmdDone)
    }

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

    // The companion link's hold on the USB device: its usbOwner token and the lease that closes [transport].
    private var companionToken: Long? = null
    private var companionLease: PortLease? = null

    /**
     * Claim the USB device for a companion open (manual Connect or attach auto-connect); pass the
     * token to [connect], or to [abortCompanionLink] if the open fails. An existing link is dropped
     * first (manual reconnect). Null while a flash or another companion open holds the device.
     */
    fun beginCompanionLink(): Long? {
        if (companionToken != null) disconnect()
        return usbOwner.beginCompanion(COMPANION_OPEN_TIMEOUT_MS)
    }

    /** The open for [token] failed before [connect]: give the device back. */
    fun abortCompanionLink(token: Long) { usbOwner.finish(token) }

    /**
     * Run companion mode on [t], opened under [token] from [beginCompanionLink]. False (and [t]
     * already closed) when the token no longer owns the USB device, e.g. a flash took it.
     */
    fun connect(token: Long, t: Transport): Boolean {
        if (transport != null) disconnect()
        val lease = PortLease { t.close() }
        if (usbOwner.adopt(token, lease) != FlashPortOwner.Adopt.ADOPTED) {
            app.brucia.BruciaLog.w("VM", "companion transport refused: the USB device has another owner")
            return false
        }
        companionToken = token
        companionLease = lease
        transport = t
        _transportKind.value = t.kind
        val c = CompanionClient(t, linkCounters)
        linkCounters.noteLink()
        client = c
        session = app.brucia.session.CommandSession(c, t, preflight = { command ->
            CommandCapabilities.check(command, _device.value?.caps, _device.value?.features)
        }, onCallbackError = { e -> app.brucia.BruciaLog.w("VM", "disconnect callback", e) })
        lastPongAt = companionNowMs()
        // Stay "Connecting..." until the first CAPS/DeviceInfo handshake arrives.
        _status.value = "Connecting..."
        startCompanionLoops()
        // Foreground service keeps the process alive + holds a wake lock for the link while the
        // screen is off. Started here (link opened) and stopped in disconnect() - the single pair.
        try { ConnectionService.start(getApplication(), ConnectionServiceCommand.OWNER_COMPANION, "Connecting...", "") } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "service start (connecting)", e) }
        return true
    }

    /** Comma-joined active device streams for the service notification. */
    private fun streamsText(): String = subscriptions.activeStreams().sorted().joinToString(", ")

    /** Refresh the foreground-service notification with the current status + streams. */
    private fun refreshConnectionNotification() {
        if (transport?.isOpen != true) return
        try { ConnectionService.update(getApplication(), ConnectionServiceCommand.OWNER_COMPANION, _status.value, streamsText()) } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "service update", e) }
    }

    /**
     * (Re)start the companion read loop and the battery ping loop. Factored out so both
     * connect() and stopRemote() can bring companion mode back up. Cancels any existing
     * loops first so they are never double-started.
     */
    private fun startCompanionLoops() {
        val c = client ?: return
        val s = session ?: return
        readJob?.cancel()
        pingJob?.cancel()
        readJob = viewModelScope.launch(Dispatchers.IO) {
            // Mode auto-negotiation: send the framed HELLO; if no CAPS/DeviceInfo lands
            // within ~1.5s the device is likely sitting at its normal CLI menu, so nudge it
            // into companion mode with the RAW (un-framed) line "companion\n" and retry
            // HELLO. When the device is already in companion mode the first HELLO just works
            // and the nudge never fires (CAPS arrives, _device becomes non-null).
            c.hello()
            var helloDeadline = companionNowMs() + 1500
            while (isActive) {
                if (transport?.isOpen != true) {
                    // Device unplugged / link lost.
                    withContext(Dispatchers.Main) { if (client === c) disconnect() }
                    break
                }
                // Never let a read/parse/handle error crash the app: this runs in a background
                // coroutine whose uncaught exceptions would otherwise reach the default handler.
                val msg = try {
                    c.readMessage(1000)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (error: Exception) {
                    app.brucia.BruciaLog.w("VM", "companion read failed", error)
                    withContext(Dispatchers.Main) { if (client === c) disconnect() }
                    break
                }
                ensureActive()
                if (msg != null) {
                    lastPongAt = companionNowMs() // any frame (ACK/EVENT/PONG) proves the link is alive
                    try {
                        handle(msg)
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (error: Exception) {
                        app.brucia.BruciaLog.w("VM", "invalid companion message", error)
                    }
                }
                if (_device.value == null && companionNowMs() > helloDeadline) {
                    // Raw bytes, NOT a framed message: the plain-text CLI reads this line.
                    try { transport?.write("companion\n".toByteArray(Charsets.US_ASCII)) } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "companion-mode nudge write", e) }
                    kotlinx.coroutines.delay(300)
                    c.hello()
                    helloDeadline = companionNowMs() + 1500
                }
            }
        }
        // Periodic ping so the battery % stays fresh while connected.
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(8000)
                if (transport?.isOpen != true || client !== c) break
                c.ping(c.nextId())
                // Also re-fetch CAPS so a theme change made ON THE DEVICE propagates to the app: CAPS
                // carries the live device UI color (theme), and handling it rebuilds _device, which
                // re-themes the app accent. The device always wins; app-side theme_set updates _device
                // immediately. Cheap (cached caps) and no device redraw (helloSeen already true). Skip
                // while a command is awaiting its ACK so we don't interleave during a long blocking op.
                if (!s.hasPending) c.hello()
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
                val cmdPending = s.hasPending
                val silentMs = companionNowMs() - lastPongAt
                if (_device.value != null && ((!cmdPending && silentMs > 26000L) || silentMs > 650000L)) {
                    _device.value = null
                    _status.value = "Reconnecting (device restarted?)..."
                    stopAllBurstLoops(silent = true)
                    lastPongAt = companionNowMs() // give the re-negotiation time before retriggering
                }
            }
        }
    }

    // --- Remote control (drive the real Bruce UI: nav + screen mirror over raw serial) ---
    @Volatile private var remote: app.brucia.remote.RemoteController? = null
    private var remoteJob: Job? = null
    private var remoteWatchdogJob: Job? = null
    private val _remoteFrame = MutableStateFlow(0L)
    val remoteFrame: StateFlow<Long> = _remoteFrame.asStateFlow()
    // Whether the remote session is up (startRemote .. stopRemote). Held here, not in the screen,
    // so a rotation or resize cannot show "Start" while the mirror still runs (and Stop, which
    // brings companion mode back, would be disabled).
    private val _remoteRunning = MutableStateFlow(false)
    val remoteRunning: StateFlow<Boolean> = _remoteRunning.asStateFlow()
    fun remoteBitmap(): android.graphics.Bitmap? = remote?.renderer?.bitmap
    // Mirror watchdog state: last time we saw any raw bytes vs. a decoded TFT frame.
    @Volatile private var lastRemoteByteAt = 0L
    @Volatile private var lastRemoteFrameAt = 0L

    /**
     * Companion operations that would wait forever once Remote leaves companion mode, ended through
     * their own stop paths before `companion_exit` (see [RemoteHandover]).
     */
    private val remoteHandover by lazy {
        val reason = RemoteHandover.REASON
        RemoteHandover(listOf(
            RemoteHandover.Step("macro", { macroRunner.running.value }) {
                macroRunner.stop("Macro stopped: Remote control took over the device; a command already sent may still be running.")
            },
            RemoteHandover.Step("script", { script.busy.value }) { script.stopForHandover(reason) },
            RemoteHandover.Step("file transfer", { fileTransfers.active }) { fileTransfers.cancelAllAndJoin() },
            RemoteHandover.Step("burst loops", { burstLoops.anyActive }) {
                withContext(Dispatchers.Main) { stopAllBurstLoops(silent = false) }
            },
        ))
    }

    /** Start remote control. Drives the device's normal UI over raw CLI + screen mirror. */
    fun startRemote() {
        val t = transport ?: return
        if (_remoteRunning.value) return
        _remoteRunning.value = true
        val now = System.currentTimeMillis()
        lastRemoteByteAt = now; lastRemoteFrameAt = now
        remoteJob = viewModelScope.launch(Dispatchers.IO) {
            // While the framed link still reads replies: end what would otherwise hang (and send
            // its stop frames) before the device is asked to leave companion mode.
            val stopped = remoteHandover.stopRunning()
            if (stopped.isNotEmpty()) BruciaLog.i("Remote", "stopped before remote: ${stopped.joinToString()}")
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
        _remoteRunning.value = false
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

    private val callbackCommands by lazy { CommandTasks(viewModelScope) }

    /**
     * Fire-and-forget console / transmit / screen-control commands: no reply is consumed here (the
     * output reaches the console through the ACK route and the command log). Bound to the original
     * link and cancellable as a group; request/response features use the services instead.
     */
    private fun dispatchCmd(cmd: String) {
        val captured = session ?: return
        val runner = CommandRunner { command, timeout ->
            withContext(Dispatchers.IO) { sendAwait(command, timeout, captured) }
        }
        callbackCommands.run(runner, cmd, 650_000)
    }

    /** Echo a command line into the console, as the legacy result adapters did. */
    private fun echoConsole(cmd: String) {
        _console.value = (_console.value + "> $cmd").takeLast(500)
    }

    // Device screen/LED power (lite mode): kept here so the Device screen's switches show the
    // device's state after a rotation instead of resetting to on, and set from CAPS on connect when
    // the firmware reports it (scrOn/ledOn).
    private val outputs = DeviceOutputs()
    val deviceOutputs: StateFlow<DeviceOutputs.State> = outputs.state

    fun setDeviceScreen(on: Boolean) = setDeviceOutput(DeviceOutputs.Output.SCREEN, on)
    fun setDeviceLed(on: Boolean) = setDeviceOutput(DeviceOutputs.Output.LED, on)

    private fun setDeviceOutput(output: DeviceOutputs.Output, on: Boolean) {
        val captured = session ?: return
        val token = outputs.request(output, on)
        val runner = CommandRunner { command, timeout ->
            withContext(Dispatchers.IO) { sendAwait(command, timeout, captured) }
        }
        callbackCommands.run(runner, DeviceOutputs.command(output, on), 650_000) { ok, _ -> outputs.finish(output, token, on, ok) }
    }
    /** Send one command and suspend until its ACK (or a timeout). Used by the spam burst loops. */
    // Default ACK wait must exceed the burst duration: bounded ops (spam/deauth/jammer) only ACK
    // AFTER running for SPAM_BURST_SECS, so a shorter timeout would always report "no response".
    private suspend fun sendAwait(cmd: String, timeoutMs: Long = (SPAM_BURST_SECS + 4) * 1000L, expectedSession: CommandSession? = session): Pair<Boolean, String> {
        val s = expectedSession ?: return false to "Not connected."
        if (session !== s) return false to "Connection closed or changed."
        val logId = logCmdStart(cmd)
        try {
            val res = s.await(cmd, timeoutMs)
            logCmdDone(logId, res.first, res.second)
            return res
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Loop stopped (Stop / disconnect) mid-burst: resolve the entry so it never hangs on RUNNING.
            logCmdDone(logId, false, "Stopped.")
            throw ce
        }
    }

    /**
     * Refresh the Diagnostics section: snapshot the app link counters and, when the firmware
     * advertises `features.diag`, ask the device once for its `diag` counters. Nothing is sent
     * automatically; older firmware is reported as not supported.
     */
    fun refreshDiagnostics() {
        _appDiag.value = linkCounters.snapshot()
        val info = _device.value ?: return
        if (!info.features.diag) { _deviceDiag.value = DeviceDiagState.Unsupported; return }
        val s = session ?: return
        if (_deviceDiag.value is DeviceDiagState.Loading) return
        _deviceDiag.value = DeviceDiagState.Loading
        viewModelScope.launch {
            val (ok, d) = sendAwaitJson("diag", DIAG_TIMEOUT_MS, s)
            val diag = if (ok) DeviceDiagnostics.fromAck(d) else null
            val at = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            _deviceDiag.value = when {
                diag != null -> DeviceDiagState.Ready(diag, at)
                ok -> DeviceDiagState.Failed("malformed diag reply")
                else -> DeviceDiagState.Failed(d?.optString("msg")?.takeIf { it.isNotBlank() } ?: "no response")
            }
            _appDiag.value = linkCounters.snapshot()
        }
    }

    /** [sendAwait] for commands whose ACK data object matters (eth_status, lora_recv). */
    private suspend fun sendAwaitJson(cmd: String, timeoutMs: Long, expectedSession: CommandSession? = session): Pair<Boolean, JSONObject?> {
        val s = expectedSession
        if (s == null || session !== s) return false to JSONObject().put("msg", "Connection closed or changed.")
        val logId = logCmdStart(cmd)
        try {
            val res = s.awaitJson(cmd, timeoutMs)
            logCmdDone(logId, res.first, res.second?.toString() ?: "")
            return res
        } catch (ce: kotlinx.coroutines.CancellationException) {
            logCmdDone(logId, false, "Stopped.")
            throw ce
        }
    }

    /** Per-burst ACK wait: bounded ops only ACK after running for SPAM_BURST_SECS. */
    private val burstTimeoutMs = (SPAM_BURST_SECS + 4) * 1000L

    /**
     * Start continuous, abortable BLE advertisement spam for one platform's pairing popup
     * (`ble_spam_platform <ios|android|windows|samsung|all> <secs> <copies>`). Instead of one long
     * blocking op (which froze the device + link until it ended), this launches a cancellable loop
     * that fires SHORT (~8s) bursts back to back and awaits each burst's ACK before the next, so
     * bursts are sequential and never queued. Between bursts the device and link are free: the user
     * can [stopBleSpam] within ~8s and the mic.pcm audio tools get serviced again. [copies] is the
     * number of advertisements per burst (passed as the 3rd token; ignored by current firmware, so
     * backward-compatible). Single entry point for the Spam hub and the native BLE screen.
     */
    fun startBleSpam(target: String, copies: Int = DEFAULT_SPAM_COPIES) {
        if (client == null) { _bleSpamStatus.value = "Connect the device over USB first."; return }
        // A live mic.pcm stream and spam contend for the single-threaded companion loop; drop it.
        if (_micPcmActive.value) stopMicPcm()
        val n = copies.coerceIn(1, 100)
        bleSpamLoop.start("Sending $target... (Stop to end)", { "ble_spam_platform $target $SPAM_BURST_SECS $n" }, burstTimeoutMs)
    }

    /** Stop the BLE spam loop. [silent] keeps the status line (used when restarting). */
    fun stopBleSpam(silent: Boolean = false) = bleSpamLoop.stop(silent)

    /** Apple ProximityPair popups; delegates to [startBleSpam] ("ios"). */
    fun bleSpam(copies: Int = DEFAULT_SPAM_COPIES) = startBleSpam("ios", copies)

    // --- File manager (device SD/LittleFS via the storage CLI; state in DeviceFileBrowser) ---
    fun listDir(path: String) { DeviceFileBrowser.listCommand(path)?.let { echoConsole(it) }; fileBrowser.list(path) }
    /** Remove [path]; the directory last listed is listed again when the device answers. */
    fun deleteDeviceFile(path: String) { DeviceFileBrowser.cliPath(path)?.let { echoConsole("storage remove $it") }; fileBrowser.remove(path) }
    /** Create [path]; the directory last listed is listed again when the device answers. */
    fun makeDeviceDir(path: String) { DeviceFileBrowser.cliPath(path)?.let { echoConsole("storage mkdir $it") }; fileBrowser.mkdir(path) }
    /** Crack tab "List captures": text listing of the handshake directory on [captureList]. */
    fun listCaptures() { echoConsole("storage list ${DeviceFileBrowser.CAPTURE_DIR}"); fileBrowser.listCaptures() }

    private suspend fun handle(msg: JSONObject) {
        when (msg.optInt("t")) {
            Kind.DATA -> {
                val id = msg.optInt("id", -1)
                val d = msg.optJSONObject("d") ?: return
                if (d.optString("stream") == "file") {
                    fileReader.chunk(id, d.optInt("seq", -1), d.optString("chunk"))
                }
            }
            Kind.ACK, Kind.ERR -> {
                val out = session?.route(msg) ?: ""
                if (out.isNotEmpty()) {
                    val lines = out.split("\n").filter { it.isNotBlank() }
                    _console.value = (_console.value + lines).takeLast(500)
                }
            }
            Kind.CAPS -> {
                val d = msg.optJSONObject("d") ?: return
                val info = DeviceInfo.fromCaps(d)
                _device.value = info
                // Screen/LED switches follow the device when it reports them (older firmware: unchanged).
                outputs.reported(info.screenOn, info.ledOn)
                // Frames above the device's RX buffer would be lost on the wire: refuse them locally.
                client?.sendLimit = app.brucia.protocol.FrameBudget.sendLimit(info.rxMax)
                // Record the exact firmware artifact so an exported log can be tied to it.
                val provenance = info.provenanceLines().joinToString("; ")
                app.brucia.BruciaLog.deviceProvenance = provenance
                app.brucia.BruciaLog.i("Caps", provenance)
                _deviceDiag.value = Diagnostics.afterCaps(info, _deviceDiag.value)
                // Handshake complete: only now is the link truly "Connected".
                lastPongAt = companionNowMs()
                _status.value = "Connected"
                refreshConnectionNotification()
            }
            Kind.PONG -> {
                // PING replies carry fresh battery; keep the device card up to date.
                lastPongAt = companionNowMs()
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
                    if (bytes.size % 2 != 0) return
                    // Offered unconditionally: the buffer decides under its own lock whether it is
                    // recording and publishes recordingState itself, so a Stop/Cancel on Main can
                    // never be overwritten here. Optional seq/dropped feed the gap accounting.
                    val seq = if (d.has("seq")) d.optLong("seq", -1L).takeIf { it >= 0 } else null
                    val dropped = if (d.has("dropped")) d.optLong("dropped", -1L).takeIf { it >= 0 } else null
                    if (recordingBuffer.append(bytes, seq, dropped) == RecordingBuffer.Append.LIMIT_REACHED) {
                        _micPcmStatus.value = "Recording limit reached; save the captured audio"
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
                if (ev == "speaker.play") {
                    // Terminal event of a non-blocking speaker_play (features.speaker_play_async).
                    d.optJSONObject("data")?.let { speaker.onPlayEvent(it) }
                    return
                }
                if (ev == "subghz.raw") {
                    val raw = SubGhzRawEvent.parse(d.optJSONObject("data") ?: return) ?: return
                    val timings = raw.timings
                    _rfWave.value = timings
                    _rfWaveCompleteness.value = raw.completeness
                    if (_rfCapture.value.capturing) {
                        appendRfCaptureRow(RfCaptureRow(System.currentTimeMillis(), raw.freqHz, timings, raw.symbols, raw.maxSymbols))
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
                    val sweep = SpectrumSweep.fromSweepEvent(data, System.currentTimeMillis())
                    if (sweep != null) {
                        // Per-sweep shape: {start, step (kHz), rssi:[...]} -> one full sweep.
                        pushSpectrumSweep(sweep)
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
     * De Bruijn transmit request. The single-code `subghz tx` path cannot carry a sweep, but the
     * device's raw-timing primitive (`subghz_tx_raw`) can: [DeBruijnTx] plans the sweep into a
     * sequence of raw bursts sized to the frame budget, and [DeBruijnTxService] streams them. For
     * orders/params the planner cannot cover it still refuses with a clear reason and transmits
     * NOTHING - no false "sent", no silent truncation. Returns the immediate status line (the
     * rejection reason, or the "Transmitting ..." text); progress then flows through
     * [deBruijnTxStatus]. Capability gating (CC1101) is enforced by the session preflight.
     */
    fun transmitDeBruijn(bits: Int, freqHz: Long, teUs: Int, repeat: Int): String {
        val frameLimit = app.brucia.protocol.FrameBudget.sendLimit(_device.value?.rxMax)
        return when (val plan = DeBruijnTx.plan(bits, teUs, freqHz, repeat, frameLimit)) {
            is DeBruijnTx.Plan.Rejected -> {
                _console.value = (_console.value + "> de Bruijn B(2,$bits) refused: ${plan.reason}").takeLast(500)
                deBruijnTx.show(plan.reason)
                plan.reason
            }
            is DeBruijnTx.Plan.Ready -> {
                val label = "%.2f MHz".format(freqHz / 1_000_000.0)
                _console.value = (_console.value +
                    "> de Bruijn B(2,$bits): streaming ${plan.chunkCount} raw burst(s) on $label").takeLast(500)
                deBruijnTx.start(plan, label)
            }
        }
    }

    /** De Bruijn raw-sweep streamer (subghz_tx_raw bursts); status/active for the UI; stop on disconnect. */
    private val deBruijnTx by lazy {
        DeBruijnTxService(viewModelScope, commandRunner, isConnected = { session != null })
    }
    val deBruijnTxStatus: StateFlow<String> get() = deBruijnTx.status
    val deBruijnTxActive: StateFlow<Boolean> get() = deBruijnTx.active
    fun stopDeBruijnTx() = deBruijnTx.stop()

    // Wi-Fi deauth of one AP is a time-bounded op that blocks the whole companion link for its full
    // duration on the firmware. Like the spam loops, we run it as a cancellable loop of SHORT (~8s)
    // `wifi_deauth <bssid> <ch> <secs>` bursts, each awaited before the next, so Stop lands within
    // ~2s and other commands/streams get serviced between bursts. Only one AP is deauthed at a time
    // (single-threaded device); [deauthTarget] holds its BSSID so a per-AP card shows Stop only on
    // the AP currently being deauthed.
    private val deauthLoop by lazy { burstLoop() }
    val deauthActive: StateFlow<Boolean> get() = deauthLoop.active
    private val _deauthTarget = MutableStateFlow("")
    val deauthTarget: StateFlow<String> = _deauthTarget.asStateFlow()

    /**
     * Start continuous, abortable Wi-Fi deauth of [bssid] on [channel] (authorized testing only).
     * Replaces the old blocking one-shot: a cancellable loop of ~2s `wifi_deauth` bursts, each
     * awaited before the next, so the device + link stay free between bursts and [stopDeauth] lands
     * within ~8s. [channel] is kept as given.
     */
    fun startDeauth(bssid: String, channel: Int) {
        if (client == null) { _console.value = (_console.value + "> deauth: connect the device first").takeLast(500); return }
        if (_micPcmActive.value) stopMicPcm()
        _deauthTarget.value = bssid
        _console.value = (_console.value + "> wifi_deauth $bssid ch$channel loop").takeLast(500)
        deauthLoop.start("", { "wifi_deauth $bssid $channel $SPAM_BURST_SECS" }, burstTimeoutMs, onEnd = { _deauthTarget.value = "" })
    }

    /** Stop the Wi-Fi deauth loop. [silent] is accepted for a uniform stop-method signature. */
    fun stopDeauth(silent: Boolean = false) {
        deauthLoop.stop(silent)
        _deauthTarget.value = ""
    }

    private val captureWorkflow by lazy {
        CaptureWorkflow(viewModelScope, blockedReason = {
            if (session != null && _device.value?.features?.verifiedFiles != true)
                "Update device firmware to enable verified file transfers" else null
        }, connect = {
            val captured = session
            if (captured == null) null else object : CaptureLink {
                // The device advertises features.capture_descriptor: import exactly the file it names
                // and verify it after transfer, instead of diffing directory listings.
                override val descriptorSupported = _device.value?.features?.captureDescriptor == true
                override suspend fun capture(cmd: String, timeoutMs: Long): Pair<Boolean, org.json.JSONObject?> =
                    withContext(Dispatchers.IO) {
                        if (session !== captured) throw java.io.IOException("Connection changed.")
                        sendAwaitJson(cmd, timeoutMs, captured)
                    }
                override val commands = CommandRunner { cmd, timeout ->
                    withContext(Dispatchers.IO) {
                        if (session !== captured) throw java.io.IOException("Connection changed.")
                        val id = logCmdStart(cmd)
                        try {
                            captured.await(cmd, timeout).also { logCmdDone(id, it.first, it.second) }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            logCmdDone(id, false, "Stopped."); throw e
                        }
                    }
                }
                override suspend fun list(): List<app.brucia.files.FileEntry> {
                    suspend fun listing(path: String): Pair<Boolean, String> {
                        val result = commands.run("storage list $path", 10_000)
                        if (result.second.toByteArray(Charsets.UTF_8).size >= 3500 ||
                            (result.first && result.second.isNotEmpty() && !result.second.endsWith("\n")))
                            throw java.io.IOException("Capture listing may be truncated; select a file manually.")
                        return result
                    }
                    val result = listing(CaptureWorkflow.DIRECTORY)
                    if (result.first) return FileList.parse(result.second)
                    // A first capture may not have created its directory yet. Distinguish
                    // absence via successful parent listings, never turn a storage error into empty.
                    val root = listing("/")
                    if (!root.first) throw java.io.IOException(root.second.ifBlank { "Cannot list device storage." })
                    if (FileList.parse(root.second).none { it.name == "BrucePCAP" }) return emptyList()
                    val parent = listing("/BrucePCAP")
                    if (parent.first && FileList.parse(parent.second).none { it.name == "handshakes" }) return emptyList()
                    throw java.io.IOException(result.second.ifBlank { "Cannot list capture directory." })
                }
                override suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
                    val result = readDeviceFile(path, captured)
                    result.bytes ?: throw java.io.IOException(result.error)
                }
            }
        }, onLoaded = { target ->
            loadedHandshake = target.handshake
            loadedPmkid = target.pmkid
            _handshakeInfo.value = target.handshake?.let { "${it.ssid} (WPA${it.keyVersion}) [pulled]" }
                ?: "${target.pmkid?.ssid} (PMKID) [pulled]"
            // Keep each accepted capture independently; delayed writes cannot overwrite a newer one.
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val dir = java.io.File(getApplication<Application>().filesDir, "pulled").apply { mkdirs() }
                    java.io.File.createTempFile("capture-", ".pcap", dir).writeBytes(target.bytes)
                    FileRetention.prune(dir, PulledFiles.PREFIXES, PulledFiles.KEEP)
                } catch (e: Exception) { BruciaLog.d("Capture", "Local capture copy failed: ${e.message}") }
            }
        })
    }
    val hsFlow: StateFlow<HandshakeFlow> get() = captureWorkflow.status
    fun resetHandshakeFlow() = captureWorkflow.reset()
    fun cancelHandshakeFlow() = captureWorkflow.stop()
    fun captureAndCrack(bssid: String, channel: Int, ssid: String, captureSeconds: Int = 20) =
        captureWorkflow.start(bssid, channel, ssid, captureSeconds, pmkid = false)
    fun capturePmkidAndCrack(bssid: String, channel: Int, ssid: String, seconds: Int = 15) =
        captureWorkflow.start(bssid, channel, ssid, seconds, pmkid = true)

    /** Console: send a command and echo it; the captured output arrives via ACK. */
    fun sendConsole(cmd: String) {
        _console.value = (_console.value + "> $cmd").takeLast(500)
        dispatchCmd(cmd)
    }

    fun clearConsole() {
        _console.value = emptyList()
    }

    /** IR learn: capture a remote via the CLI `ir rx`; IrReceiveService parses the .ir output. */
    fun learnIr() = irRx.learn()

    private fun onIrLearned(parsed: List<IrButton>) {
        _irLearned.value = parsed + _irLearned.value
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            parsed.forEach { b ->
                savedDao.insert(SavedItem(type = "ir", name = b.name, subtitle = b.protocol, payload = b.toCli(), timeMillis = now))
            }
        }
    }

    /**
     * TV-B-Gone power-off sweep (ir_tvbgone [region]); region is "na" or "eu".
     * Fire + ACK; the ACK `out` is surfaced on [irTvBGoneStatus]. Authorized use only.
     */
    fun irTvBGone(region: String = "na") = tvbGone.run(region)

    /**
     * Arm the receiver once (ir_rx, ~10s). The decoded signal is parsed and held in
     * [irRxResult] so the user can review it and choose to save it with [saveLearnedIr].
     */
    fun irReceiveOnce() = irRx.receiveOnce()

    /** Save a received/decoded button into the learned-IR list and persist it for later replay. */
    fun saveLearnedIr(button: IrButton) {
        _irLearned.value = listOf(button) + _irLearned.value
        irRx.markSaved(button)
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

    /**
     * Transmit a raw IR frame via the fixed contract (ir_tx_raw <khz> <csv durations>). A LITE
     * firmware without the companion IR commands gets Bruce's own CLI `ir tx_raw` instead.
     */
    fun irTxRaw(khz: Int, durations: String) {
        dispatchCmd(if (irCmdsOmitted()) IrButton.cliTxRaw(khz, durations) else "ir_tx_raw $khz $durations")
    }

    /** The firmware reported the companion IR commands as compiled out (LITE build). */
    private fun irCmdsOmitted() = _device.value?.features?.omits(DeviceFeatures.IR_CMDS) == true

    // --- Custom IR remote: a user-built grid of captured buttons, persisted on the phone ---
    private val _remoteKeys = MutableStateFlow<List<IrButton>>(emptyList())
    val remoteKeys: StateFlow<List<IrButton>> = _remoteKeys.asStateFlow()
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
        remotePrefs.edit { putString("keys", arr.toString()) }
    }

    /** Capture one IR signal (ir_rx) and add it to the custom remote under [label]. */
    fun captureRemoteKey(label: String) = irRx.captureKey(label)

    /** Transmit a custom-remote button. */
    fun pressRemoteKey(b: IrButton) {
        if (client == null) { irRx.noteCapture("Connect the device over USB first."); return }
        val (khz, csv) = b.toTxRawArgs()
        if (b.raw && csv.isNotBlank()) irTxRaw(khz, csv) else sendCommand(b.toCli())
        irRx.noteCapture("Sending: ${b.name} (device reply in the console)")
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
        songPrefs.edit { putString("songs", arr.toString()) }
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
    private val macroRunner by lazy {
        MacroRunner(viewModelScope, connect = {
            val captured = session
            if (captured == null) null else CommandRunner { cmd, timeout ->
                withContext(Dispatchers.IO) { sendAwait(cmd, timeout, captured) }
            }
        })
    }
    val macroStatus get() = macroRunner.status
    val macroRunning get() = macroRunner.running
    private val macroPrefs by lazy {
        getApplication<Application>().getSharedPreferences("macros", android.content.Context.MODE_PRIVATE)
    }

    private fun loadMacros() {
        _macros.value = app.brucia.automation.Macro.listFromJson(macroPrefs.getString("macros", "[]") ?: "[]")
    }

    private fun saveMacros() {
        macroPrefs.edit { putString("macros", app.brucia.automation.Macro.listToJson(_macros.value)) }
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

    fun runMacro(macro: app.brucia.automation.Macro) = macroRunner.start(macro)
    fun stopMacro() = macroRunner.stop()

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
        portalPrefs.edit { putString("portals", arr.toString()) }
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
        fpPrefs.edit { putString("fps", arr.toString()) }
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
        timelinePrefs.edit { putString("sightings", arr.toString()) }
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
        geoPrefs.edit { putString("geo", arr.toString()) }
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

    // The replay's own status (sending, then the device's answer) is mirrored into geoStatus.
    private val geoReplay by lazy {
        GeoReplayService(viewModelScope, commandRunner, isConnected = { session != null }).also { svc ->
            viewModelScope.launch { svc.status.collect { if (it.isNotEmpty()) _geoStatus.value = it } }
        }
    }

    /** Replay a geo-signal's command now (the device transmits); the status shows the device's answer. */
    fun replayGeoSignal(g: GeoSignal) {
        if (client == null) { _geoStatus.value = "Connect the device over USB first."; return }
        geoReplay.replay(g.name, g.payload)
    }

    // --- NFC / RFID (read/info/ndef owned by NfcTagService; the rest are console actions) ---
    fun nfcRead() { echoConsole(NfcTagService.READ); nfcTag.read() }

    fun nfcEmulate() = sendConsole("rfid emulate")
    fun nfcClone() = sendConsole("rfid clone")
    fun nfcWrite() = sendConsole("rfid write")
    fun nfcErase() = sendConsole("rfid erase")
    fun nfcInfo() { echoConsole(NfcTagService.INFO); nfcTag.info() }
    fun nfcSave() = sendConsole("rfid save brucia_${System.currentTimeMillis()}")
    fun nfcNdef() { echoConsole(NfcTagService.NDEF); nfcTag.ndef() }

    // --- Advanced NFC (fixed contract; firmware built in parallel) ---

    /**
     * Push an NDEF record to a nearby reader/phone on tap: nfc_ndef_push <url|text|contact>
     * <payload>. For a contact, payload is "name,phone" or a raw vCard. Fire + ACK; the result
     * is reported on the shared [nfcNdefStatus] line. This is the single entry point used by
     * both the NFC screen's push card and the Spam hub's NFC tile, so the two can never drift.
     */
    fun nfcNdefPush(type: String, payload: String) = nfcTools.pushNdef(type, payload)

    /** Read an EMV bank card (masked / read-only): nfc_emv_read -> masked PAN/expiry/scheme. */
    fun nfcEmvRead() = nfcTools.readEmv()

    /** Write a UID onto a magic / UID-writable card: nfc_write_uid <hexuid>. */
    fun nfcWriteUid(hex: String) = nfcTools.writeUid(hex)

    /** Amiibo helper: nfc_amiibo <action>. Shows whatever the firmware ACKs back. */
    fun nfcAmiibo(action: String) = nfcTools.amiibo(action)
    /** MIFARE nested key-recovery attack: mifare_nested. */
    fun mifareNested() = nfcTools.nested()
    /** MIFARE mfkey32 key-recovery attack: mifare_mfkey32. */
    fun mifareMfkey32() = nfcTools.mfkey32()
    /** SRIX / ST25TB (ISO14443B) read: srix_read [secs]. Reports the tag JSON or a timeout. */
    fun srixRead(secs: Int = 8) = nfcTools.srixRead(secs)
    /** 125 kHz LF read: rfid125_read [secs]. Only boards with the UART LF reader return a tag. */
    fun rfid125Read(secs: Int = 8) = nfcTools.rfid125Read(secs)

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
    fun resetMifareAttack() = mifareKeyCheck.reset()
    fun stopMifareKeyCheck() = mifareKeyCheck.stop()

    /** Existing bundled and imported dictionary, snapshotted by the batch service. */
    fun runMifareKeyCheck() = mifareKeyCheck.start(
        NfcSecurity.loadKeyDictionary(getApplication()) + _mifareUserKeys.value
    )

    // --- Sub-GHz capture / replay via the CLI (device decodes; phone stores) ---
    fun rfCapture(freqHz: Long = 433_920_000L) { echoConsole("rf rx $freqHz"); rfReceive.capture(freqHz) }

    fun replaySaved(item: SavedItem) = replayPayload(item.payload)

    /**
     * The one replay path for stored payloads (saved items and geo-signals pinned from them).
     * Legacy unquoted raw IR payloads are rewritten to the quoted CLI form (IrButton.replayable).
     */
    private fun replayPayload(payload: String) {
        if (payload.startsWith("rf tx")) sendCommand(payload) else sendConsole(IrButton.replayable(payload))
    }

    fun deleteSaved(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { savedDao.delete(id) }
    }

    private fun updateStreamState(stream: String, on: Boolean) {
        when (stream) {
            "wifi.ap" -> _wardriving.value = on
            "ble.scan" -> _bleScanning.value = on
            "wifi.sta" -> _sniffing.value = on
            "wifi.probe" -> _probing.value = on
            "wifi.monitor" -> _monitoring.value = on
            "subghz.spectrum" -> subghzRx.onState(stream, on)
            "subghz.raw" -> {
                subghzRx.onState(stream, on)
                if (!on) stopRfCapture()
            }
            "mic.pcm" -> {
                _micPcmActive.value = on
                if (on) recordingBuffer.streamRestarted()
                if (!on) {
                    pauseRecording()
                    dtmfJob?.cancel()
                    dtmfJob = null
                    _dtmfListening.value = false
                }
            }
        }
    }

    /** Shared SUB owner covers individual and composite screens. */
    private fun setStream(stream: String, on: Boolean, channel: Int? = null, bssid: String? = null): Boolean =
        subscriptions.set(stream, on, channel, bssid)

    /**
     * The device may still be running [stream] (on, starting, or unconfirmed). Tool screens check it
     * when they are left, so leaving sends one OFF for a started stream and none for a stopped one.
     */
    fun streamMayRun(stream: String): Boolean = subscriptions.mayRun(stream)

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
        setSubghzStream("subghz.raw", on)
    }

    /** Sub-GHz receive SUBs record the request first so a rejected start is reported, not a stop. */
    private fun setSubghzStream(stream: String, on: Boolean) {
        subghzRx.request(stream, on)
        setStream(stream, on)
    }

    /** Start recording subghz.raw events to an in-memory buffer, ensuring the stream is on. */
    fun startRfCapture() {
        synchronized(rfCaptureRows) { rfCaptureRows.clear() }
        _rfCapture.value = RfCaptureState(true, 0)
        setSubghzStream("subghz.raw", true)
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
        val atLimit = SubGhzRawEvent.completenessOf(row.symbols, row.maxSymbols) == RawCompleteness.UNKNOWN_AT_LIMIT
        val cur = _rfCapture.value
        _rfCapture.value = cur.copy(samples = count, atBufferLimit = cur.atBufferLimit + if (atLimit) 1 else 0)
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


    /**
     * Connect to [mac] over GATT and discover its services. Discovered characteristics arrive as
     * EVENT ble.gatt and build up [gattServices]; the command ACK reports connect-failed on
     * [gattStatus]. Clears any previous exploration state first.
     */
    fun gattConnect(mac: String) { releaseBleScan(); gatt.connect(mac) }

    /** Forget the current GATT exploration and clear its state (used on screen leave). */
    fun gattStop() = gatt.stop()

    /** Read one characteristic (ble_gatt_read <mac> <uuid> -> ACK {ok,out}); result shown per-char. */
    fun gattRead(mac: String, uuid: String) { releaseBleScan(); gatt.read(mac, uuid) }

    /**
     * Run a DuckyScript over BLE HID (ble_badble <script> -> ACK {ok,out}). This is the real
     * device-execution path the USB-disabled BadUSB screen points to. Authorized use only.
     */
    fun bleBadble(script: String) { releaseBleScan(); badBle.run(script) }

    /**
     * GATT, BadBLE and the media hijack take the single BLE controller: the firmware stops a running
     * ble.scan for GATT/BadBLE without telling the app (the toggle stayed on with no events) and
     * refuses the media hijack with 409 while it runs. End the stream first so the SUB OFF reaches
     * the device before the command and the Scan toggle stays truthful.
     */
    private fun releaseBleScan() {
        if (streamMayRun("ble.scan")) setBleScan(false)
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
        setSubghzStream("subghz.spectrum", true)
    }

    /** Stop the spectrum stream; flush any half-collected per-point sweep so it is not lost. */
    fun stopSpectrum() {
        if (spectrumPts.isNotEmpty()) flushSpectrumPoints()
        setSubghzStream("subghz.spectrum", false)
        subghzRx.onState("subghz.spectrum", false)
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

    // Scan and sniff are two loops sharing the _nrf24Status line.
    private val nrf24ScanLoop by lazy { burstLoop(_nrf24Status) }
    private val nrf24SniffLoop by lazy { burstLoop(_nrf24Status) }
    val nrf24Scanning: StateFlow<Boolean> get() = nrf24ScanLoop.active
    val nrf24Sniffing: StateFlow<Boolean> get() = nrf24SniffLoop.active
    private val _nrf24Status = MutableStateFlow("")
    val nrf24Status: StateFlow<String> = _nrf24Status.asStateFlow()

    // Jammer and MouseJack each run for a fixed window and ACK on completion; their status
    // lines are separate so the two cards never clobber each other.
    private val nrf24JammerLoop by lazy { burstLoop(_nrf24JammerStatus) }
    val nrf24Jamming: StateFlow<Boolean> get() = nrf24JammerLoop.active
    private val _nrf24JammerStatus = MutableStateFlow("")
    val nrf24JammerStatus: StateFlow<String> = _nrf24JammerStatus.asStateFlow()
    private val nrf24MjLoop by lazy { burstLoop(_nrf24MjStatus) }
    val nrf24MjRunning: StateFlow<Boolean> get() = nrf24MjLoop.active
    private val _nrf24MjStatus = MutableStateFlow("")
    val nrf24MjStatus: StateFlow<String> = _nrf24MjStatus.asStateFlow()

    // Each NRF24 op runs a bounded device command that blocks the single-threaded link for its full
    // window, so (like the spam loops) they run as cancellable loops of ~2s bursts; these hold the
    // Jobs that Stop/disconnect cancel.

    private fun nrf24ErrMessage(out: String) = out.ifBlank { "No NRF24 module on this device." }

    /**
     * Start the 2.4GHz channel scanner as a cancellable loop of ~2s `nrf24_scan` bursts; per-channel
     * activity arrives via EVENT nrf24.scan between bursts. Replaces the blocking one-shot so Stop
     * aborts within ~8s instead of only flipping a flag while the device kept running. [seconds] is
     * kept for call-site compatibility; each burst is a fixed ~2s window.
     */
    fun startNrf24Scan(seconds: Int = 20) {
        if (client == null) { _nrf24Status.value = "Connect the device over USB first."; return }
        if (_micPcmActive.value) stopMicPcm()
        _nrf24Channels.value = emptyMap()
        nrf24ScanLoop.start("Scanning 2.4GHz... (Stop to end)", { "nrf24_scan $SPAM_BURST_SECS" }, burstTimeoutMs, "Scan stopped.")
    }

    fun stopNrf24Scan(silent: Boolean = false) = nrf24ScanLoop.stop(silent)

    /**
     * Start the ESB sniffer as a cancellable loop of ~2s `nrf24_sniff` bursts; devices arrive via
     * EVENT nrf24.dev between bursts. Replaces the blocking one-shot so Stop aborts within ~8s
     * instead of only flipping a flag while the device kept running. [seconds] is kept for call-site
     * compatibility; each burst is a fixed ~2s window.
     */
    fun startNrf24Sniff(seconds: Int = 20) {
        if (client == null) { _nrf24Status.value = "Connect the device over USB first."; return }
        if (_micPcmActive.value) stopMicPcm()
        nrf24SniffLoop.start("Sniffing ESB devices... (Stop to end)", { "nrf24_sniff $SPAM_BURST_SECS" }, burstTimeoutMs, "Sniff stopped.")
    }

    fun stopNrf24Sniff(silent: Boolean = false) = nrf24SniffLoop.stop(silent)

    fun clearNrf24Devices() { _nrf24Devices.value = emptyMap() }

    fun clearNrf24MjDevices() { _nrf24MjDevices.value = emptyMap() }

    /**
     * Start the 2.4GHz jammer as a cancellable loop of ~2s `nrf24_jammer` bursts (authorized use
     * only). Replaces the blocking one-shot so the device + link stay free between bursts and
     * [stopNrf24Jammer] aborts within ~8s.
     */
    fun startNrf24Jammer() {
        if (client == null) { _nrf24JammerStatus.value = "Connect the device over USB first."; return }
        if (_micPcmActive.value) stopMicPcm()
        nrf24JammerLoop.start("Jamming 2.4GHz... (Stop to end)", { "nrf24_jammer $SPAM_BURST_SECS" }, burstTimeoutMs, "Jammer stopped.")
    }

    fun stopNrf24Jammer(silent: Boolean = false) = nrf24JammerLoop.stop(silent)

    // --- Sub-GHz jammer (CC1101) --- cancellable loop of ~Ns `subghz_jammer <freqKHz> <secs>` bursts.
    private val subghzJammerLoop by lazy { burstLoop(_subghzJammerStatus) }
    val subghzJamming: StateFlow<Boolean> get() = subghzJammerLoop.active
    private val _subghzJammerStatus = MutableStateFlow("")
    val subghzJammerStatus: StateFlow<String> = _subghzJammerStatus.asStateFlow()

    /** Start the CC1101 jammer at [freqKHz] (authorized use only) as abortable ~Ns bursts. */
    fun startSubghzJammer(freqKHz: Int) {
        if (client == null) { _subghzJammerStatus.value = "Connect the device over USB first."; return }
        if (_micPcmActive.value) stopMicPcm()
        val f = freqKHz.coerceIn(300000, 928000)
        subghzJammerLoop.start("Jamming ${f / 1000.0} MHz... (Stop to end)", { "subghz_jammer $f $SPAM_BURST_SECS" }, burstTimeoutMs, "Jammer stopped.")
    }

    fun stopSubghzJammer(silent: Boolean = false) = subghzJammerLoop.stop(silent)

    // --- ARP spoof / NetCut (device joins the target Wi-Fi, then poisons target<->gateway) ---
    private val arpSpoofLoop by lazy { burstLoop(_arpSpoofStatus) }
    val arpSpoofActive: StateFlow<Boolean> get() = arpSpoofLoop.active
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
        if (_micPcmActive.value) stopMicPcm()
        arpSpoofLoop.start("Spoofing $targetIp via $gwIp... (Stop to end)", { "arp_spoof $ssid $pass $targetIp $gwIp $SPAM_BURST_SECS" }, burstTimeoutMs, "Stopped.")
    }

    fun stopArpSpoof(silent: Boolean = false) = arpSpoofLoop.stop(silent)

    // --- IR jammer --- abortable loop of ~Ns `ir_jammer <secs>` bursts.
    private val irJammerLoop by lazy { burstLoop(_irJammerStatus) }
    val irJamming: StateFlow<Boolean> get() = irJammerLoop.active
    private val _irJammerStatus = MutableStateFlow("")
    val irJammerStatus: StateFlow<String> = _irJammerStatus.asStateFlow()

    fun startIrJammer() {
        if (client == null) { _irJammerStatus.value = "Connect the device over USB first."; return }
        if (irCmdsOmitted()) { _irJammerStatus.value = DeviceFeatures.OMITTED_REASON; return }
        if (_micPcmActive.value) stopMicPcm()
        irJammerLoop.start("Jamming IR... (Stop to end)", { "ir_jammer $SPAM_BURST_SECS" }, burstTimeoutMs, "Jammer stopped.")
    }

    fun stopIrJammer(silent: Boolean = false) = irJammerLoop.stop(silent)

    // --- BLE iBeacon advertiser --- abortable loop of ~Ns `ble_ibeacon <secs> [uuid]` bursts.
    private val ibeaconLoop by lazy { burstLoop(_ibeaconStatus) }
    val ibeaconActive: StateFlow<Boolean> get() = ibeaconLoop.active
    private val _ibeaconStatus = MutableStateFlow("")
    val ibeaconStatus: StateFlow<String> = _ibeaconStatus.asStateFlow()

    fun startIbeacon(uuid: String = "") {
        if (client == null) { _ibeaconStatus.value = "Connect the device over USB first."; return }
        val u = uuid.trim()
        app.brucia.ble.IbeaconUuid.problem(u)?.let { _ibeaconStatus.value = it; return }
        if (_micPcmActive.value) stopMicPcm()
        ibeaconLoop.start("Broadcasting iBeacon... (Stop to end)", { if (u.isEmpty()) "ble_ibeacon $SPAM_BURST_SECS" else "ble_ibeacon $SPAM_BURST_SECS $u" }, burstTimeoutMs, "Stopped.")
    }

    fun stopIbeacon(silent: Boolean = false) = ibeaconLoop.stop(silent)

    // --- BLE media-control hijack (one-shot): ble_media <mac> ---
    fun bleMedia(mac: String) { releaseBleScan(); bleMediaService.send(mac) }

    // --- Sub-GHz brute force (bounded one-shot): subghz_bruteforce <proto> <freqHz> <bits> <secs> ---
    val subBruteActive: StateFlow<Boolean> get() = subBrute.busy
    val subBruteStatus: StateFlow<String> get() = subBrute.status
    fun subghzBruteforce(protocolIdx: Int, freqHz: Long, bits: Int, secs: Int) = subBrute.run(protocolIdx, freqHz, bits, secs)

    // --- Wi-Fi jam detect (bounded): jam_detect <secs> -> "deauth=.. total=.." ---
    val jamDetectActive: StateFlow<Boolean> get() = jamDetector.busy
    val jamDetectStatus: StateFlow<String> get() = jamDetector.status
    fun jamDetect(secs: Int) = jamDetector.run(secs)

    // --- WIDS: continuous wireless-intrusion monitor. Loops jam_detect windows (802.11 monitor mode -
    //     only the ESP can do this, a phone cannot) and logs deauth/disassoc floods with a phone
    //     timestamp; raises an ALERT when a window exceeds the threshold. Defensive / authorized use.
    val widsActive: StateFlow<Boolean> get() = wids.active
    val widsStatus: StateFlow<String> get() = wids.status
    val widsEvents: StateFlow<List<WidsEvent>> get() = wids.events

    /** Start the WIDS monitor: back-to-back jam_detect windows, alert when deauths >= [threshold]. */
    fun startWids(windowSecs: Int = 15, threshold: Int = 20) = wids.start(windowSecs, threshold)

    fun stopWids() = wids.stop()

    fun clearWidsEvents() = wids.clearEvents()

    // --- Evil Portal (captive-portal / evil twin) --- persistent device-side session, creds streamed.
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
    ) = evilPortal.start(ssid, templateBytes, templateName, channel, deauth)

    fun stopEvilPortal() = evilPortal.stop()

    fun clearEvilCreds() { _evilCreds.value = emptyList() }

    /**
     * Start MouseJack scan/inject as a cancellable loop of ~2s `nrf24_mousejack <secs> [text]`
     * bursts; discovered devices stream via EVENT nrf24.mj between bursts. [text] (optional
     * keystrokes to inject) is kept as given and re-sent each burst. Replaces the blocking one-shot
     * so [stopNrf24MouseJack] aborts within ~8s.
     */
    fun startNrf24MouseJack(text: String = "") {
        if (client == null) { _nrf24MjStatus.value = "Connect the device over USB first."; return }
        if (_micPcmActive.value) stopMicPcm()
        _nrf24MjDevices.value = emptyMap()
        val trimmed = text.trim()
        val cmd = if (trimmed.isEmpty()) "nrf24_mousejack $SPAM_BURST_SECS" else "nrf24_mousejack $SPAM_BURST_SECS $trimmed"
        nrf24MjLoop.start("MouseJack running... (Stop to end)", { cmd }, burstTimeoutMs, "MouseJack stopped.")
    }

    fun stopNrf24MouseJack(silent: Boolean = false) = nrf24MjLoop.stop(silent)

    // --- LoRa (config / send / receive) ----------------------------------------
    // lora_config applies the radio parameters; lora_send transmits one message; lora_recv runs
    // for <secs> and streams received packets via EVENT lora.rx {data, rssi, snr} (a short recv
    // may also return the packet directly in its ACK). Disabled when disconnected.
    private val _loraPackets = MutableStateFlow<List<LoraPacket>>(emptyList())
    val loraPackets: StateFlow<List<LoraPacket>> = _loraPackets.asStateFlow()


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
    fun loraConfig(freqMHz: String, bw: String = "", sf: String = "", cr: String = "") = lora.configure(freqMHz, bw, sf, cr)

    /** Transmit one LoRa message (lora_send <text>). The ACK result is shown on the status line. */
    fun loraSend(text: String) = lora.send(text)

    /**
     * Listen for LoRa packets for [seconds] (lora_recv <secs>). Packets arrive via EVENT lora.rx;
     * a short recv may also return one directly in its ACK data. Fire-and-ack; stop is local.
     */
    fun loraRecv(seconds: Int = 20) = lora.receive(seconds)

    fun stopLoraRecv() = lora.stopReceive()

    fun clearLoraPackets() { _loraPackets.value = emptyList() }

    // --- Ethernet (status / config) --------------------------------------------
    // eth_status reports link state + IP; eth_config applies network settings. Disabled when
    // disconnected.

    /** Query Ethernet link + IP (eth_status). The ACK data carries {link, ip}. */
    fun ethStatus() = ethernet.refresh()

    /** Apply Ethernet configuration (eth_config <...>); [args] is the raw argument string. */
    fun ethConfig(args: String) = ethernet.configure(args)

    /** DHCP-starvation flood over Ethernet: eth_dhcp_starve [secs]. Authorized use only. */
    fun ethDhcpStarve(secs: Int = 10) = ethernet.dhcpStarve(secs)

    /** MAC-flooding over Ethernet: eth_mac_flood [secs]. Authorized use only. */
    fun ethMacFlood(secs: Int = 10) = ethernet.macFlood(secs)

    // --- FM audio transmitter: Android source factories, lifecycle in PcmStreamService ---
    private fun pcmStreamService() = PcmStreamService(viewModelScope, connect = { spec ->
        val captured = session ?: throw IOException("Connect the device over USB first.")
        val caps = _device.value?.caps
        if (spec.fmKHz != null && caps?.fm != true) throw IOException("No confirmed FM module.")
        if (spec.fmKHz == null && caps?.speaker != true) throw IOException("No confirmed speaker.")
        if (spec.deviceMic && caps?.mic != true) throw IOException("No confirmed device microphone.")
        SessionPcmLink(captured, spec, { session === captured })
    })
    private val fmStream by lazy { pcmStreamService() }
    val fmStatus: StateFlow<String> by lazy { fmStream.status.map { it.message }.stateIn(viewModelScope, SharingStarted.Eagerly, "") }
    val fmStreaming: StateFlow<Boolean> by lazy { fmStream.status.map { it.active }.stateIn(viewModelScope, SharingStarted.Eagerly, false) }
    val fmLevel: StateFlow<Float> by lazy { fmStream.status.map { it.level }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f) }
    val fmElapsedMs: StateFlow<Long> by lazy { fmStream.status.map { it.elapsedMs }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L) }

    private fun startFm(freqKHz: Int, prepare: suspend (() -> Unit) -> PcmStreamSource) =
        fmStream.start(PcmStreamSpec(fmKHz = freqKHz, rxMax = _device.value?.rxMax), "Preparing FM at $freqKHz kHz...",
            "Transmitting at $freqKHz kHz...", prepare)

    fun fmStartTone(freqKHz: Int, toneHz: Double = 440.0) = startFm(freqKHz) {
        PcmStreamSource(PcmSource.toneReader(toneHz))
    }

    fun fmStartFile(uri: Uri, freqKHz: Int) = startFm(freqKHz) { check ->
        val pcm = PcmSource.decodeFile(getApplication(), uri, check)
        if (pcm.isEmpty()) throw IOException("Could not decode this audio file (limit: 16 MiB decoded PCM).")
        PcmStreamSource(PcmSource.arrayReader(pcm), pcm.size.toLong())
    }

    fun fmStartPhoneMic(freqKHz: Int) = startFm(freqKHz) {
        val mic = PcmSource.MicReader(failOnReadError = true)
        if (!mic.start()) { mic.close(); throw IOException("Microphone unavailable (permission denied?).") }
        PcmStreamSource(mic)
    }

    fun fmStartDeviceMic(freqKHz: Int) = fmStream.start(
        PcmStreamSpec(fmKHz = freqKHz, deviceMic = true, rxMax = _device.value?.rxMax), "Starting FM at $freqKHz kHz...",
        "Transmitting from the device mic at $freqKHz kHz.",
    )
    fun fmStop() = fmStream.stop()

    /**
     * Start continuous, abortable Wi-Fi beacon spam (authorized testing only): the device
     * broadcasts fake [ssids]. Same abortable short-burst loop as [startBleSpam]: a cancellable
     * loop of ~2s `wifi_beacon_spam <secs> <ssids>` bursts, each awaited before the next, so the
     * device + link stay free between bursts and the user can [stopBeaconSpam] within ~8s. The
     * SSID list is newline-joined into the command payload.
     */
    fun startBeaconSpam(ssids: List<String>) {
        if (client == null) { _beaconStatus.value = "Connect the device over USB first."; return }
        val list = ssids.map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) { _beaconStatus.value = "Add at least one SSID or a number of fake APs."; return }
        if (_micPcmActive.value) stopMicPcm()
        _console.value = (_console.value + "> wifi_beacon_spam loop (${list.size} SSIDs)").takeLast(500)
        BeaconSpam.start(beaconLoop, list, SPAM_BURST_SECS, burstTimeoutMs)
    }

    /** Stop the Wi-Fi beacon spam loop. [silent] keeps the status line (used when restarting). */
    fun stopBeaconSpam(silent: Boolean = false) = beaconLoop.stop(silent)

    /**
     * Stop every abortable short-burst loop at once (BLE/Wi-Fi spam, Wi-Fi deauth, all NRF24 ops).
     * Used on [disconnect] and whenever an audio stream (mic.pcm) needs the single-threaded link.
     */
    private fun stopAllBurstLoops(silent: Boolean) {
        burstLoops.stopAll(silent)
        _deauthTarget.value = ""
        if (wids.active.value) wids.stop() // WIDS is a monitor loop, not a BurstLoop
    }

    /** Stop local work; firmware stop requests keep their real ACK/result in the command log. */
    fun stopAllRunning() {
        deviceLifecycle.stopAll()
        dictionaryTest.cancel()
        stopTimeline()
        stopDtmfDecode()
        stopPhoneMic()
        pauseRecording()
        stopRfCapture()
        // Use the normal SUB ownership path: stale pending starts cannot re-enable a stream.
        subscriptions.stopAll()
    }

    // --- Device panel (info / diagnostics / power) ---
    /** Diagnostics (info/free/uptime/i2c) owned by DiagnosticsService; output on [deviceOut]. */
    fun deviceCmd(cmd: String) { echoConsole(cmd); diagnostics.run(cmd) }
    fun powerReboot() = sendConsole("reboot")
    fun powerSleep() = sendConsole("sleep")

    /** Decode the bundled synthetic sample (works without hardware, for demo/test). */
    fun decodeSample() {
        _rfWave.value = OokSamples.princeton24
        _rfWaveCompleteness.value = RawCompleteness.NO_INFO
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
                        _rfWaveCompleteness.value = RawCompleteness.NO_INFO
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
    private val releaseService by lazy {
        ReleaseUpdateService(viewModelScope, fetch = { AppUpdater.fetchSnapshot() })
    }
    val releaseUpdates get() = releaseService.state
    private val appDownloads by lazy {
        AppDownloadService(viewModelScope, download = { url, integrity, progress, cancelled ->
            AppUpdater.download(getApplication(), url, integrity, progress, cancelled)
        }, install = { apk -> withContext(Dispatchers.Main) { AppUpdater.install(getApplication(), apk) } })
    }
    val appDownload get() = appDownloads.state

    /** null = Auto; explicit selections must belong to the current snapshot. */
    fun selectBoard(b: ReleaseCatalog.Board?) {
        if (!isFlashing()) releaseService.select(b)
    }

    private val _flash = MutableStateFlow(FlashState())
    val flash: StateFlow<FlashState> = _flash.asStateFlow()

    /** One response supplies version, notes, APK and every board image. */
    fun checkUpdates(currentApp: String) {
        if (appDownload.value.downloading || isFlashing() || releaseUpdates.value.checking) return
        pendingAppInstall = null
        appDownloads.reset()
        releaseService.refresh(currentApp)
    }

    /** Download the latest APK and hand it to the system installer. */
    // "Install unknown apps" is a one-time per-app grant. The first tap used to only open the grant
    // screen and require a second tap; now we remember the intent and auto-resume on return.
    @Volatile private var pendingAppInstall: String? = null

    /** Entry point for the "Download & install" button: install now, or request the grant then auto-resume. */
    fun requestAppInstall(ctx: android.content.Context) {
        val url = releaseUpdates.value.apkUrl ?: return
        if (AppUpdater.canInstall(ctx)) { pendingAppInstall = null; downloadAndInstallApp() }
        else { pendingAppInstall = url; AppUpdater.openInstallSettings(ctx) }
    }

    /** Called from the Activity onResume: if the user just granted install permission, proceed. */
    fun resumeAppInstallIfPending(ctx: android.content.Context) {
        val requested = pendingAppInstall ?: return
        if (requested != releaseUpdates.value.apkUrl) { pendingAppInstall = null; return }
        if (AppUpdater.canInstall(ctx)) { pendingAppInstall = null; downloadAndInstallApp() }
    }

    /** Stop the running APK download; nothing is installed. */
    fun cancelAppDownload() = appDownloads.cancel()

    /** Single flight: a second request while a download runs is refused. */
    fun downloadAndInstallApp() {
        val release = releaseUpdates.value
        val url = release.apkUrl ?: return
        // URL and integrity come from one snapshot and stay fixed for this download.
        appDownloads.start(url, release.apkIntegrity)
    }

    /**
     * Download the merged firmware and flash it over USB via [EspFlasher]. [serial]
     * is an already-opened port (provided by the Activity, which also took over the
     * port from companion mode); [port] is adopted by the flash owner and released exactly once.
     */
    /** Set an explicit flash error from the Activity (e.g. bootloader re-enumeration failed). */
    fun flashError(msg: String) { _flash.value = FlashState(phase = "error", message = msg); flashLog("ERROR: $msg") }

    // Single owner of the USB device: the companion link or one flash operation, by token. Ports are
    // adopted by token and released exactly once; stale or duplicate handoffs are closed on arrival.
    // Every end of a flash operation (finish, abort, expiry, cancel, onCleared) releases the
    // foreground service + wake lock the flash took in [flashEnteringBootloader].
    private val usbOwner = FlashPortOwner(onEnded = { token, kind ->
        if (kind == FlashPortOwner.Kind.FLASH) {
            try { ConnectionService.stop(getApplication(), ConnectionServiceCommand.flashOwner(token)) } catch (e: Exception) { BruciaLog.d("VM", "flash service stop: ${e.message}") }
        }
    })

    /**
     * Report that the flasher is rebooting the device into the ROM download mode. Returns the
     * operation token the Activity must pass to [flashFirmware] / [flashAbort], or null if refused.
     * The ROM port must be handed over within [FLASH_HANDOFF_TIMEOUT_MS] or the operation ends.
     */
    fun flashEnteringBootloader(): Long? {
        if (isFlashing()) return null
        if (releaseUpdates.value.flashUrl == null) {
            flashError("No matching firmware asset in the current release. Check for updates and select T-Embed CC1101 or Auto.")
            return null
        }
        // Drop companion mode first so it unsubscribes while the link is still up and releases the
        // device; then the flash owns the device and keeps the phone awake until its operation ends.
        disconnectLink(stopService = false)
        val token = usbOwner.begin(FLASH_HANDOFF_TIMEOUT_MS)
        if (token == null) {
            try { ConnectionService.stop(getApplication(), ConnectionServiceCommand.OWNER_COMPANION) } catch (e: Exception) { app.brucia.BruciaLog.d("VM", "service stop: ${e.message}") }
            return null
        }
        val serviceOwner = ConnectionServiceCommand.flashOwner(token)
        // Replaces the link's notification (if any) and keeps its wake lock: no release in between.
        try { ConnectionService.start(getApplication(), serviceOwner, "Updating firmware", "") } catch (e: Exception) { app.brucia.BruciaLog.w("VM", "flash service start", e) }
        // An end that raced the start must not leave the flash notification and wake lock behind.
        if (usbOwner.flashToken != token) try { ConnectionService.stop(getApplication(), serviceOwner) } catch (e: Exception) { app.brucia.BruciaLog.d("VM", "flash service stop: ${e.message}") }
        // While this phase is active refresh and selection are locked, including USB re-enumeration.
        _flash.value = FlashState(phase = "download", message = "Rebooting into bootloader...")
        flashLog("--- flash start: rebooting into bootloader ---")
        viewModelScope.launch {
            // Margin: this timer and the owner's deadline read different clocks; firing early would
            // leave the operation (and the flash's wake lock) waiting with no timer left.
            delay(FLASH_HANDOFF_TIMEOUT_MS + 1_000L)
            if (usbOwner.expireHandoff(token)) {
                flashError("Bootloader handoff timed out (no USB permission or device). Unplug/replug and try again.")
            }
        }
        return token
    }

    /** End flash operation [token] with an error. Ignored (logged) when [token] is no longer active. */
    fun flashAbort(token: Long, msg: String) {
        if (usbOwner.finish(token)) flashError(msg) else flashLog("Ignored stale flash result: $msg")
    }

    /**
     * True while a flash is in progress. The flash drops companion mode first (so status reads
     * "Disconnected"), and a native-USB ESP32-S3 re-enumerates mid-flash; the USB-attach
     * auto-connect must NOT grab the port during this window ([usbOwner] also refuses it).
     */
    fun isFlashing(): Boolean =
        usbOwner.flashToken != null || _flash.value.phase.let { it == "download" || it == "flash" }

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

    fun flashFirmware(
        token: Long,
        serial: EspFlasher.SerialIo,
        port: PortLease,
        skipBoardCheck: Boolean = false,
        doReset: Boolean = true,
    ) {
        // Operation identity, not phase: the legitimate handoff arrives while the phase is "download".
        when (usbOwner.adopt(token, port)) {
            FlashPortOwner.Adopt.ADOPTED -> Unit
            FlashPortOwner.Adopt.EXPIRED -> {
                flashError("Bootloader handoff timed out. Unplug/replug and try again."); return
            }
            else -> { flashLog("Ignored a stale or duplicate flash port handoff"); return }
        }
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
            usbOwner.finish(token); return
        }
        val snapshot = releaseUpdates.value
        val selected = snapshot.selectedBoard
        if (selected != null && selected.env != "lilygo-t-embed-cc1101") {
            _flash.value = FlashState(phase = "error", message = "Selected image does not match the supported T-Embed CC1101 target")
            usbOwner.finish(token); return
        }
        val url = snapshot.flashUrl
        val integrity = snapshot.flashIntegrity
        if (url == null) {
            flashError("No matching firmware asset in the current release")
            usbOwner.finish(token); return
        }
        _flash.value = FlashState(phase = "download", message = "Downloading firmware...")
        // The port is released by the owner when this job completes, including a job cancelled
        // before its body starts (a finally inside the body would never run then).
        viewModelScope.launchFlashOwned(usbOwner, token, Dispatchers.IO, onAbandoned = {
            flashError("Flash cancelled before it started")
        }) {
            // This operation still holds the USB device (abort/owner shutdown end it underneath this job).
            val ours = { usbOwner.flashToken == token }
            val owned = { ours() && isActive }
            // The device already sits in the ROM loader (rebooted before this handoff). Until the
            // write starts, any failure resets it back into its current firmware, still on this port.
            var writing = false
            fun leaveLoader(): String {
                if (writing || !ours()) return ""
                try { EspFlasher(serial).resetToApp() } catch (e: Exception) { BruciaLog.d("Flash", "reset after aborted flash: ${e.message}") }
                flashLog("Device reset out of the bootloader into its current firmware")
                return " The device was reset into its current firmware."
            }
            try {
                flashLog("Downloading firmware from ${url.substringAfterLast('/')}")
                val bytes = DownloadIO.owned(owned, onProgress = { d, t ->
                    _flash.value = _flash.value.copy(written = d.toInt(), total = t.toInt(), message = "Downloading...")
                }) { progress, cancelled -> AppUpdater.downloadBytes(url, integrity, progress, cancelled) }
                flashLog("Image ${integrity.label}" + if (integrity.size == null) "; release lists no size" else "")
                app.brucia.update.FirmwareImage.validateTEmbed(bytes)
                _flash.value = FlashState(phase = "flash", total = bytes.size, message = "Flashing (do not unplug)...")
                flashLog("Downloaded ${bytes.size} bytes; entering flash write")
                writing = true // EspFlasher.flash resets the device itself on success and on failure
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
                val (phase, message) = EspFlasher.Outcome.of(verify)
                _flash.value = FlashState(phase = phase, message = message)
                flashLog("RESULT: $verifyMsg (wrote ${bytes.size} bytes @0x0, flash=${flashSize / (1024 * 1024)}MB)")
            } catch (_: InterruptedException) {
                // Download stopped because the operation ended; whoever ended it published the result.
                val reset = leaveLoader()
                if (ours()) _flash.value = FlashState(phase = "error", message = "Firmware download cancelled. Nothing was flashed.$reset")
                flashLog("Firmware download stopped: flash operation ended; nothing was flashed")
            } catch (e: app.brucia.update.IntegrityException) {
                val reset = leaveLoader()
                if (ours()) _flash.value = FlashState(phase = "error", message = "${e.message}. Nothing was flashed.$reset")
                flashLog("INTEGRITY FAILURE: ${e.message}; nothing was flashed")
            } catch (e: Exception) {
                val before = !writing
                val reset = leaveLoader()
                val msg = (e.message ?: "Flash failed") + if (before) ". Nothing was flashed.$reset" else ""
                if (ours()) _flash.value = FlashState(phase = "error", message = msg)
                flashLog("EXCEPTION: ${e.message ?: "Flash failed"}" + if (before) " (before writing; nothing was flashed)" else "")
            }
        }
    }

    /** Report that no USB device was available to flash. */
    fun flashNoDevice() { _flash.value = FlashState(phase = "error", message = "No USB device found") }

    /** Clear a finished flash result; never hides an operation that still owns the USB handoff. */
    fun resetFlash() { if (usbOwner.flashToken == null) _flash.value = FlashState() }

    fun disconnect() = disconnectLink(stopService = true)

    /**
     * Tear the companion link down. [stopService] false only when a flash takes the device over
     * right after: the flash then replaces the link's notification and wake lock without a gap.
     */
    private fun disconnectLink(stopService: Boolean) {
        deviceLifecycle.disconnect()
        // Remote mode owns independent readers; never let them consume a replacement transport.
        remoteWatchdogJob?.cancel(); remoteWatchdogJob = null
        remoteJob?.cancel(); remoteJob = null
        remote = null
        _remoteRunning.value = false
        stopTimeline()
        dtmfJob?.cancel()
        dtmfJob = null
        _dtmfListening.value = false
        // Stop any running short-burst loops first so nothing keeps looping / sending after the link drops.
        stopAllBurstLoops(silent = true)
        // Include streams with missing ACKs: a timeout does not prove hardware stopped.
        val c = client?.takeIf { transport?.isOpen == true }
        subscriptions.disconnect { stream ->
            try { c?.sub(stream, false) } catch (e: Exception) { BruciaLog.d("VM", "stream unsubscribe: ${e.message}") }
        }
        // Screen/LED the app turned off: restore them now so a quick reconnect does not find the
        // device dark while the switches show on (the device itself waits 15 s of silence).
        val restoreOutputs = outputs.disconnect()
        if (c != null) {
            restoreOutputs.forEach { cmd ->
                try { c.cmd(cmd) } catch (e: Exception) { BruciaLog.d("VM", "restore $cmd: ${e.message}") }
            }
        }
        fileReader.disconnect()
        readJob?.cancel()
        readJob = null
        pingJob?.cancel()
        pingJob = null
        val closing = session
        session = null
        client = null
        // Release the device through the owner: the lease closes the transport exactly once (it may
        // already be closed if a flash preempted the link).
        companionToken?.let { usbOwner.finish(it) }
        companionToken = null
        companionLease?.close()
        companionLease = null
        transport = null
        closing?.close("Disconnected")
        _status.value = "Disconnected"
        _transportKind.value = "none"
        _wardriving.value = false
        _device.value = null
        _sniffing.value = false
        _probing.value = false
        _monitoring.value = false
        subghzRx.reset()
        pauseRecording()
        _stations.value = emptyMap()
        stopRfCapture()
        // Audio suite: stop any capture/decode/playback and release the wake lock (no leak).
        stopPhoneMic()
        _micPcmActive.value = false
        // Stop the link's foreground service and wake lock (every disconnect path). While a flash
        // holds the device the service belongs to the flash and only the flash's end stops it.
        if (stopService && usbOwner.flashToken == null) try { ConnectionService.stop(getApplication(), ConnectionServiceCommand.OWNER_COMPANION) } catch (e: Exception) { app.brucia.BruciaLog.d("VM", "service stop: ${e.message}") }
    }

    /** Machine-readable JSON of the session. */
    fun buildSessionJson(): String = SessionExport.build(aps.value, ble.value.size, crack.value.result)

    /** Aggregate the session into a Markdown security report. */
    fun buildReport(): String {
        val nfc = nfcTag.last.value
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
            provenance = dev?.provenanceLines() ?: emptyList(),
            diagnostics = Diagnostics.exportLines(linkCounters.snapshot(), _deviceDiag.value),
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

    private val dictionaryTest = DictionaryTestService(viewModelScope)
    val crack: StateFlow<CrackStatus> = dictionaryTest.status

    private val _rules = MutableStateFlow(false)
    val rules: StateFlow<Boolean> = _rules.asStateFlow()
    fun setRules(on: Boolean) { _rules.value = on }

    @Volatile private var loadedHandshake: Handshake? = null
    @Volatile private var loadedPmkid: PmkidTarget? = null

    private val fileTransfers by lazy { FileTransferService(viewModelScope, { fileLink() }) }

    /** Capture the session and verified-files prerequisite synchronously at request time. */
    private fun fileLink(expectedSession: CommandSession? = session): FileTransferLink {
        val captured = expectedSession ?: throw IOException("Not connected.")
        if (session !== captured || captured.isClosed) throw IOException("Connection changed.")
        if (_device.value?.features?.verifiedFiles != true)
            throw IOException("Update device firmware to enable verified file transfers")
        return SessionFileLink(captured, fileReader, fileTransferMutex, { session === captured },
            transactionalUploads = _device.value?.features?.transactionalUploads == true,
            frameBudget = app.brucia.protocol.FrameBudget.chunkBudget(_device.value?.rxMax))
    }

    /** CaptureWorkflow owns its job; it uses the same verified wire adapter and mutex. */
    private suspend fun readDeviceFile(path: String, expectedSession: CommandSession? = session): FileReadResult =
        try { FileReadResult(bytes = fileLink(expectedSession).read(path)) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { FileReadResult(error = e.message ?: "File download failed") }

    private data class PulledCapture(
        val file: java.io.File, val message: String,
        val handshake: Handshake? = null, val pmkid: PmkidTarget? = null,
    )

    /** The latest manual import owns the target. Older/cancelled pulls cannot overwrite it. */
    fun pullFile(path: String, ssidHint: String = "", onDone: ((Boolean) -> Unit)? = null): Job =
        fileTransfers.submit("capture-import", onDiscard = { it: PulledCapture -> it.file.delete() }, work = {
            val bytes = read(path)
            val name = path.substringAfterLast('/').ifEmpty { "pulled.bin" }
            if (name == "." || name == "..") throw IOException("Invalid file name")
            val capture = name.endsWith(".pcap", true) || name.endsWith(".cap", true)
            val hs = if (capture) HandshakeParser.parse(bytes) else null
            val pm = if (capture && hs == null) HandshakeParser.parsePmkid(bytes, ssidHint.ifEmpty { null }) else null
            checkActive()
            val dir = java.io.File(getApplication<Application>().filesDir, "pulled").apply { mkdirs() }
            val file = java.io.File.createTempFile("pull-", if (capture) ".pcap" else ".bin", dir)
            try {
                file.outputStream().use { output ->
                    var offset = 0
                    while (offset < bytes.size) {
                        checkActive()
                        val count = minOf(64 * 1024, bytes.size - offset)
                        output.write(bytes, offset, count)
                        offset += count
                    }
                }
                checkActive()
                FileRetention.prune(dir, PulledFiles.PREFIXES, PulledFiles.KEEP)
                val message = when {
                    hs != null -> "${hs.ssid} (WPA${hs.keyVersion}) [pulled]"
                    pm != null -> "${pm.ssid} (PMKID) [pulled]"
                    capture -> "pulled ${bytes.size} B, no handshake"
                    else -> "pulled ${bytes.size} B: $name"
                }
                PulledCapture(file, message, hs, pm)
            } catch (e: Exception) { file.delete(); throw e }
        }) { result ->
            val pulled = result.getOrNull()
            if (pulled != null) {
                if (pulled.handshake != null || pulled.pmkid != null) {
                    loadedHandshake = pulled.handshake
                    loadedPmkid = pulled.pmkid
                }
                _handshakeInfo.value = pulled.message
            } else _handshakeInfo.value = result.exceptionOrNull()?.message ?: "File download failed"
            onDone?.invoke(pulled?.let { it.handshake != null || it.pmkid != null } == true)
        }

    /** Read/verify before opening the document. Provider writes cannot be rolled back on cancellation. */
    fun pullFileToUri(path: String, uri: Uri, onDone: (Boolean, String) -> Unit): Job =
        fileTransfers.submit("export:$uri", work = {
            val bytes = read(path)
            checkActive()
            val output = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("Cannot open destination")
            output.use {
                var offset = 0
                while (offset < bytes.size) {
                    checkActive()
                    val count = minOf(64 * 1024, bytes.size - offset)
                    it.write(bytes, offset, count)
                    offset += count
                }
            }
            "Saved ${bytes.size} B"
        }) { result -> onDone(result.isSuccess, result.getOrElse { "Save failed: ${it.message}" }) }

    fun pullFileBytes(path: String, onBytes: (ByteArray?) -> Unit): Job =
        fileTransfers.submit("file-preview", work = { read(path) }) { onBytes(it.getOrNull()) }

    /** Bounded snapshot: the caller cannot change bytes while the upload waits in the queue. */
    private fun uploadSnapshot(bytes: ByteArray): ByteArray {
        if (bytes.size > SessionFileLink.MAX_BYTES) throw IOException("File exceeds 16 MiB transfer limit")
        return bytes.copyOf()
    }

    /** Staged upload; success requires the matching commit size/CRC acknowledgement. */
    fun pushFile(path: String, bytes: ByteArray, onDone: (Boolean, String) -> Unit): Job {
        val snapshot = runCatching { uploadSnapshot(bytes) }
        val logId = logCmdStart("upload $path (${bytes.size} B)")
        val job = fileTransfers.submit("upload:$path", work = { write(path, snapshot.getOrThrow()) }) { result ->
            val message = if (result.isSuccess) "Saved ${bytes.size} B to $path"
                else result.exceptionOrNull()?.message ?: "File upload failed"
            logCmdDone(logId, result.isSuccess, message)
            onDone(result.isSuccess, message)
        }
        job.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) logCmdDone(logId, false, "Transfer cancelled; if commit was already sent, verify the destination before retrying.")
        }
        return job
    }

    private suspend fun pushTemplateFile(path: String, bytes: ByteArray): Pair<Boolean, String> {
        val logId = logCmdStart("upload $path (${bytes.size} B)")
        try {
            val snapshot = uploadSnapshot(bytes)
            fileTransfers.await("upload:$path") { write(path, snapshot) }
            logCmdDone(logId, true, "Saved ${bytes.size} B to $path")
            return true to "Saved ${bytes.size} B"
        } catch (e: kotlinx.coroutines.CancellationException) {
            logCmdDone(logId, false, "Transfer cancelled; if commit was already sent, verify the destination before retrying.")
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "File upload failed"
            logCmdDone(logId, false, message)
            return false to message
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

    fun cancelCrack() = dictionaryTest.cancel()

    /** Synthetic target is used only by the explicit demo. */
    fun demoCrack() {
        val h = sampleHandshake("brucia-demo-pass")
        dictionaryTest.start(h, null, 401, "demo") {
            (List(400) { "fillerpass%05d".format(it) } + "brucia-demo-pass")
                .joinToString("\n").reader().buffered()
        }
    }

    fun crackWithWordlist(name: String) {
        val total = _wordlists.value.firstOrNull { it.name == name }?.lines ?: 0
        dictionaryTest.start(loadedHandshake, loadedPmkid, total, name, _rules.value) {
            WordlistStore.file(getApplication(), name).bufferedReader()
        }
    }

    fun crackBuiltin() = crackBundled("common_wpa.txt", "built-in")

    fun crackBundled(asset: String, note: String = asset) {
        val total = _bundledWordlists.value.firstOrNull { it.asset == asset }?.lines ?: 0
        dictionaryTest.start(loadedHandshake, loadedPmkid, total, note, _rules.value) {
            getApplication<Application>().assets.open(asset).bufferedReader()
        }
    }

    // --- Scripts (JS runner) and small device tools (iButton, clock) ---
    // Fixed contract (firmware built in parallel):
    //   script_run <js source>   -> ACK {ok, out:"<output>"}
    //   ibutton_read             -> ACK {ok, out:"id=.."}
    //   ibutton_write <idhex>    -> ACK {ok, out}
    //   clock_get                -> ACK {ok, out:"<iso>"}
    //   clock_set <epoch>        -> ACK {ok, out}


    // Feature services talk to the device through this seam; it adds the command log and keeps
    // blocking USB writes off the main thread (viewModelScope dispatches on Main).
    private val commandRunner = CommandRunner { cmd, timeoutMs ->
        val captured = session
        withContext(Dispatchers.IO) { sendAwait(cmd, timeoutMs, captured) }
    }
    private val jsonRunner = JsonCommandRunner { cmd, timeoutMs ->
        val captured = session
        withContext(Dispatchers.IO) { sendAwaitJson(cmd, timeoutMs, captured) }
    }

    // Central reviewable inventory; lazy only to respect service property initialization order.
    private val deviceLifecycle by lazy {
        ServiceLifecycle(listOf(
            Entry("callbacks", { callbackCommands.cancel() }),
            Entry("file-browser", { fileBrowser.stop() }),
            Entry("nfc-tag", { nfcTag.stop() }),
            Entry("rf-receive", { rfReceive.stop() }),
            Entry("diagnostics", { diagnostics.stop() }),
            Entry("files", { fileTransfers.cancelAll() }),
            Entry("fm-stream", { fmStream.stop() }, { fmStream.stop("Disconnected") }),
            Entry("sstv-stream", { sstvStream.stop() }, { sstvStream.stop("Disconnected") }),
            Entry("bursts", { stopAllBurstLoops(silent = false) }, { stopAllBurstLoops(silent = true) }),
            Entry("macro", { macroRunner.stop() }, { macroRunner.stop("Disconnected") }),
            Entry("mifare", { mifareKeyCheck.stop() }, { mifareKeyCheck.stop("Disconnected") }),
            Entry("capture", { captureWorkflow.stop() }, { captureWorkflow.stop("Disconnected") }),
            Entry("subghz", { subBrute.stop() }),
            Entry("debruijn-tx", { deBruijnTx.stop() }),
            Entry("jam-detect", { jamDetector.stop() }),
            Entry("lora", { lora.stop() }),
            Entry("ethernet", { ethernet.stop() }),
            Entry("nfc", { nfcTools.stopAll() }),
            Entry("ir-receive", { irRx.stopReceive(); irRx.stopCapture(); irRx.stopLearn() }),
            Entry("tvbgone", { tvbGone.stop() }),
            Entry("ble-hid", { bleHid.stop(); bleHid.end() }, { bleHid.stop() }),
            Entry("badble", { badBle.stop() }),
            Entry("ble-media", { bleMediaService.stop() }),
            Entry("gatt", { gatt.stop() }),
            Entry("portal", { evilPortal.stop() }, { evilPortal.abandon() }),
            Entry("ibutton", { ibutton.stop() }),
            Entry("clock", { clock.stop() }),
            Entry("script", { script.stop() }, { script.abandon() }),
            Entry("espnow", { espNow.stop() }),
            Entry("speaker", { speaker.stop(); speaker.stopSong() }, { speaker.disconnect() }),
            Entry("mic-record", { micRecorder.stop() }),
            Entry("appearance", { appearance.stop() }),
            Entry("wifi-mac", { wifiMacService.stop() }),
            Entry("wifi-join", { wifiJoinService.stop() }),
            Entry("responder", { responder.stop() }, { responder.abandon() }),
            Entry("karma", { karma.stop() }, { karma.abandon() }),
            Entry("brucegotchi", { brucegotchi.stop() }, { brucegotchi.abandon() }),
        )) { name, error -> BruciaLog.w("Cleanup", name, error) }
    }

    // Abortable short-burst loops. Every loop registers with [burstLoops] when first built, so
    // stopAllBurstLoops() cannot miss one. Lazy: a loop that never started needs no stopping.
    private val burstLoops = BurstLoopRegistry()

    // Bounded monitors (lazy: built on first use, after commandRunner).
    private val subBrute by lazy { SubGhzBruteforceService(viewModelScope, commandRunner, isConnected = { session != null }) }
    private val jamDetector by lazy { JamDetectService(viewModelScope, commandRunner, isConnected = { session != null }) }
    private val wids by lazy { WidsService(viewModelScope, commandRunner, isConnected = { session != null }, linkOpen = { transport?.isOpen == true }) }
    private fun burstLoop(line: MutableStateFlow<String> = MutableStateFlow("")) =
        BurstLoop(viewModelScope, commandRunner, { transport?.isOpen == true }, burstLoops, line)

    // LoRa and Ethernet (optional modules; replies carry structured data, hence jsonRunner).
    // The receive stop is polled by the firmware and never ACKed: send it directly (a lambda,
    // directStop is declared below).
    private val lora = LoraService(viewModelScope, commandRunner, jsonRunner, isConnected = { session != null },
        stopRunner = { cmd, timeoutMs -> directStop.run(cmd, timeoutMs) }) { addLoraPacket(it) }
    val loraStatus: StateFlow<String> = lora.status
    val loraReceiving: StateFlow<Boolean> = lora.receiving
    private val ethernet = EthernetService(viewModelScope, commandRunner, jsonRunner, isConnected = { session != null })
    val ethStatus: StateFlow<EthStatus?> = ethernet.status
    val ethMessage: StateFlow<String> = ethernet.message

    // Basic tag actions (rfid read/info/ndef) share the last-result line; a read with a UID is saved.
    private val nfcTag = NfcTagService(viewModelScope, commandRunner, isConnected = { session != null }) { uid, type, out ->
        viewModelScope.launch(Dispatchers.IO) {
            savedDao.insert(SavedItem(type = "nfc", name = uid, subtitle = type, payload = out, timeMillis = System.currentTimeMillis()))
        }
    }
    val lastNfc: StateFlow<String?> = nfcTag.last

    // One-shot Sub-GHz capture (rf rx); a decoded capture is saved for replay.
    private val rfReceive = RfReceiveService(viewModelScope, commandRunner, isConnected = { session != null }) { c ->
        viewModelScope.launch(Dispatchers.IO) {
            savedDao.insert(SavedItem(type = "rf", name = c.name, subtitle = c.protocol, payload = c.payload, timeMillis = System.currentTimeMillis()))
        }
    }
    val lastRf: StateFlow<String?> = rfReceive.last

    // Device panel diagnostics and the device file manager.
    private val diagnostics = DiagnosticsService(viewModelScope, commandRunner, isConnected = { session != null })
    val deviceOut: StateFlow<String> = diagnostics.output
    private val fileBrowser = DeviceFileBrowser(viewModelScope, commandRunner, isConnected = { session != null })
    val deviceEntries: StateFlow<List<app.brucia.files.FileEntry>> = fileBrowser.entries
    val deviceListing: StateFlow<Boolean> = fileBrowser.loading
    val deviceListStatus: StateFlow<String> = fileBrowser.listStatus
    val deviceFileStatus: StateFlow<String> = fileBrowser.changeStatus
    val captureList: StateFlow<String> = fileBrowser.captureList

    // Advanced NFC lines (NDEF push, EMV, UID write, advanced tiles), independent of each other.
    // nfcNdefStatus is shared by the NFC screen's push card and the Spam hub's NFC tile.
    private val nfcTools = NfcToolsService(viewModelScope, commandRunner, isConnected = { session != null })
    val nfcNdefStatus: StateFlow<String> = nfcTools.ndefStatus
    val nfcEmvStatus: StateFlow<String> = nfcTools.emvStatus
    val nfcUidStatus: StateFlow<String> = nfcTools.uidStatus
    val nfcAdvStatus: StateFlow<String> = nfcTools.advStatus

    // IR receive/capture and TV-B-Gone (declared after commandRunner: property init order).
    private val irRx = IrReceiveService(viewModelScope, commandRunner, isConnected = { session != null }, onLearned = ::onIrLearned,
        useCli = { irCmdsOmitted() }) { key ->
        _remoteKeys.value = _remoteKeys.value + key
        saveRemoteKeys()
    }
    val irRxStatus: StateFlow<String> = irRx.rxStatus
    val irRxResult: StateFlow<IrButton?> = irRx.rxResult
    val remoteCapture: StateFlow<String> = irRx.captureStatus
    private val tvbGone = TvBGoneService(viewModelScope, commandRunner, isConnected = { session != null })
    val irTvBGoneStatus: StateFlow<String> = tvbGone.status

    // BLE HID / BadBLE / media control (authorized use; see the ble/ services).
    private val bleHid = BleHidService(viewModelScope, commandRunner, isConnected = { session != null })
    val bleHidStatus: StateFlow<String> = bleHid.status
    private val badBle = BadBleService(viewModelScope, commandRunner, isConnected = { session != null })
    val badbleStatus: StateFlow<String> = badBle.status
    private val bleMediaService = BleMediaService(viewModelScope, commandRunner, isConnected = { session != null })
    val bleMediaStatus: StateFlow<String> = bleMediaService.status

    // GATT exploration: commands in GattService; the EVENT ble.gatt tree stays here (_gattTree).
    private val gatt = GattExplorer(viewModelScope, commandRunner, isConnected = { session != null }) { _gattTree.value = emptyMap() }
    val gattMac: StateFlow<String?> = gatt.mac
    val gattStatus: StateFlow<String> = gatt.status
    val gattConnecting: StateFlow<Boolean> = gatt.connecting
    val gattReads: StateFlow<Map<String, String>> = gatt.reads

    // Evil Portal: a device-side session; captured creds arrive as EVENT evil.cred (_evilCreds).
    private val evilPortal = EvilPortalService(
        viewModelScope, commandRunner, isConnected = { session != null },
        pushTemplate = { path, bytes -> pushTemplateFile(path, bytes) },
        onStart = { _evilCreds.value = emptyList() },
    )
    val evilActive: StateFlow<Boolean> = evilPortal.active
    val evilStatus: StateFlow<String> = evilPortal.status

    private val ibutton = IButtonService(
        viewModelScope, commandRunner,
        isConnected = { session != null },
        hasInterface = { _device.value?.caps?.ibutton == true }, // same source as the session preflight
    )
    val ibuttonStatus: StateFlow<String> = ibutton.status
    val ibuttonBusy: StateFlow<Boolean> = ibutton.busy

    private val clock = ClockService(viewModelScope, commandRunner, isConnected = { session != null })
    val clockStatus: StateFlow<String> = clock.status

    // script_stop goes out like the responder/karma stops: unlogged and unawaited (the firmware
    // consumes it while script_run waits and never ACKs it). A lambda: directStop is declared below.
    private val script = ScriptService(viewModelScope, commandRunner, isConnected = { session != null },
        stopRunner = { cmd, timeoutMs -> directStop.run(cmd, timeoutMs) })
    val scriptOutput: StateFlow<String> = script.output
    /** True while a script_run awaits its reply (the Scripts screen shows Stop instead of Run). */
    val scriptBusy: StateFlow<Boolean> = script.busy
    /** True while a stop is on its way and the run has not ended yet. */
    val scriptStopping: StateFlow<Boolean> = script.stopping

    private val espNow = EspNowService(viewModelScope, commandRunner, isConnected = { session != null })
    val espnowStatus: StateFlow<String> = espNow.status

    // --- Audio (speaker / mic) + ESP-NOW (fixed contract; firmware built in parallel) ---
    private val speaker = SpeakerService(
        viewModelScope, commandRunner, isConnected = { session != null }, json = jsonRunner,
        asyncPlay = { _device.value?.features?.speakerPlayAsync == true },
    )
    val speakerStatus: StateFlow<String> = speaker.status

    private val micRecorder = MicRecordService(viewModelScope, commandRunner, isConnected = { session != null })
    val micStatus: StateFlow<String> = micRecorder.status


    /** Run a JavaScript source on the device; the captured output lands on [scriptOutput]. */
    fun scriptRun(src: String) = script.run(src)

    /** Stop the running script on the device (script_stop); the run's own reply shows the outcome. */
    fun scriptStop() = script.stop()

    /** Clear the script output (kept while a script is still running). */
    fun clearScriptOutput() = script.clear()

    /** Read an iButton / 1-Wire id; result surfaced on [ibuttonStatus]. */
    fun ibuttonRead() = ibutton.read()

    /** Write a hex id onto a writable iButton; result surfaced on [ibuttonStatus]. */
    fun ibuttonWrite(hex: String) = ibutton.write(hex)

    /** Cancel the in-flight iButton read/write; its late reply is ignored. */
    fun ibuttonStop() = ibutton.stop()

    /** Read the device clock; result surfaced on [clockStatus]. */
    fun clockGet() = clock.get()

    /** Set the device clock from a Unix epoch (seconds); result surfaced on [clockStatus]. */
    fun clockSet(epoch: Long) = clock.set(epoch)

    /** Set the device clock to the phone's local time (Bruce's clock holds local wall time). */
    fun clockSyncFromPhone() = clock.syncFromPhone()

    // --- Device theme / LED control (device UI color wins; app matches via DeviceInfo.themeRgb565) ---
    // Success reflects the color in the app immediately, without waiting for the next CAPS.
    private val appearance = DeviceAppearanceService(
        viewModelScope, commandRunner, isConnected = { session != null },
        onThemeApplied = { rgb565 -> _device.value = _device.value?.copy(themeRgb565 = rgb565) },
        onLedApplied = { rgb -> _device.value = _device.value?.copy(ledRgb = rgb) },
    )
    val themeStatus: StateFlow<String> = appearance.status

    /** Set the device UI theme color from an 0xRRGGBB value (converted to RGB565 for the device). */
    fun setDeviceTheme(rgb: Int) = appearance.setTheme(rgb)

    /** Set the device LED color (0xRRGGBB). */
    fun setDeviceLedColor(rgb: Int) = appearance.setLedColor(rgb)

    /** Persist the current device theme/LED config to flash (the Theme "Save" button). */
    fun saveDeviceConfig() = appearance.save()

    /** Onboard LED brightness 0..100 %: led_bright <pct>. */
    fun setLedBright(pct: Int) = appearance.setLedBright(pct)

    /** Onboard LED animation 0..9 (0=solid): led_effect <n>. */
    fun setLedEffect(effect: Int) = appearance.setLedEffect(effect)

    /** Device screen brightness 0..100 %: scr_bright <pct>. */
    fun setScreenBright(pct: Int) {
        if (client == null) return
        dispatchCmd("scr_bright ${pct.coerceIn(0, 100)}")
    }

    // --- Speaker volume (bruceConfig.soundVolume 0-100 via companion `speaker_volume <pct>`) ---
    val speakerVolume: StateFlow<Int> = speaker.volume

    /** Set the device speaker volume (0-100). Persists in the device config. */
    fun setSpeakerVolume(pct: Int) = speaker.setVolume(pct)

    /** Play a tone on the device speaker: speaker_tone <freqHz> <ms>. Result on [speakerStatus]. */
    fun speakerTone(freqHz: Int, ms: Int) = speaker.tone(freqHz, ms)

    // --- Song player: the whole RTTTL string goes to the native on-device player (speaker_song,
    // async on the device, so speaker_stop can interrupt it) ---
    val songPlaying: StateFlow<String> = speaker.songPlaying

    /** Play a melody on the device; [name] is shown while it plays. */
    fun playSong(name: String, rtttl: String) = speaker.playSong(name, rtttl)

    fun stopSong() = speaker.stopSong()

    /** Play an audio file on the device speaker: speaker_play <path>. Result on [speakerStatus]. */
    fun speakerPlay(path: String) = speaker.playFile(path)

    /** File the device is playing (firmware with features.speaker_play_async), "" when none. */
    val speakerFilePlaying: StateFlow<String> = speaker.filePlaying

    /** Stop the file started by [speakerPlay]; the outcome lands on [speakerStatus]. */
    fun speakerStopFile() = speaker.stopFile()

    /** Record from the device mic for [secs] seconds: mic_record <secs>. Saved path on [micStatus]. */
    fun micRecord(secs: Int) = micRecorder.record(secs)

    // --- Wi-Fi MAC (Bruce "Change MAC" parity) --------------------------------
    private val wifiMacService = WifiMacService(viewModelScope, commandRunner, isConnected = { session != null })
    val wifiMac: StateFlow<String> = wifiMacService.status

    /** Ask the device for its current STA MAC: wifi_get_mac. */
    fun getWifiMac() = wifiMacService.get()

    /** Set the device STA MAC: wifi_set_mac <mac|random>. Result on [wifiMac]. */
    fun setWifiMac(mac: String) = wifiMacService.set(mac)

    // --- Brucegotchi (Pwnagotchi parity): headless WPA-handshake collector -----
    // Long, bounded, early-stoppable ops. The stop frame goes straight to the device (polled by
    // the firmware mid-run, never ACKed), so it is neither logged nor awaited.
    private val directStop = CommandRunner { cmd, _ ->
        val captured = client
        withContext(Dispatchers.IO) {
            try {
                if (client === captured && captured != null) {
                    // Same preflight as the session (stops are always permitted on an open link).
                    CommandCapabilities.check(cmd, _device.value?.caps, _device.value?.features)
                    captured.cmd(cmd)
                }
            } catch (e: Exception) { BruciaLog.d("Op", "$cmd: ${e.message}") }
        }
        true to ""
    }
    private val responder = ResponderService(viewModelScope, commandRunner, isConnected = { session != null }, stopRunner = directStop)
    private val karma = KarmaService(viewModelScope, commandRunner, isConnected = { session != null }, stopRunner = directStop)
    private val brucegotchi = BrucegotchiService(viewModelScope, commandRunner, isConnected = { session != null }, stopRunner = directStop)
    val brucegotchiStatus: StateFlow<String> = brucegotchi.status

    // --- BLE HID presenter / keyboard (parity with Bruce BLE Keyboard / Presenter) ---
    fun bleHidBegin() = bleHid.begin()
    fun bleHidEnd() = bleHid.end()
    fun bleHidRefresh() = bleHid.refresh()
    fun bleHidKey(name: String) = bleHid.key(name)
    fun bleHidType(text: String) = bleHid.type(text)

    // --- Responder (LLMNR/NBT-NS poisoning), headless. Authorized use only; needs Wi-Fi joined. ---
    val responderStatus: StateFlow<String> = responder.status
    val responderRunning: StateFlow<Boolean> = responder.running
    fun startResponder(secs: Int) = responder.start(secs)
    /** End Responder early: the firmware polls "stop" mid-run and still ACKs the result. */
    fun stopResponder() = responder.stop()

    // --- Karma (evil-twin probe-response lure), headless. Authorized use only. ---
    val karmaStatus: StateFlow<String> = karma.status
    val karmaRunning: StateFlow<Boolean> = karma.running
    /** Run the headless Karma attack for [secs] seconds: karma <secs>. Authorized use only. */
    fun startKarma(secs: Int) = karma.start(secs)
    fun stopKarma() = karma.stop()

    val brucegotchiRunning: StateFlow<Boolean> = brucegotchi.running
    /** Run the headless Brucegotchi for [secs] seconds: brucegotchi <secs>. Authorized use only. */
    fun startBrucegotchi(secs: Int) = brucegotchi.start(secs)
    /** Stop Brucegotchi early (brucegotchi_stop, polled by the firmware mid-run). */
    fun stopBrucegotchi() = brucegotchi.stop()

    // --- Wi-Fi station join (Bruce "Connect to Wifi" parity) ------------------
    private val wifiJoinService = WifiJoinService(viewModelScope, commandRunner, isConnected = { session != null })
    val wifiJoinStatus: StateFlow<String> = wifiJoinService.status

    /** Ask the device whether its station is connected: wifi_status. */
    fun wifiStatus() = wifiJoinService.refresh()

    /** Join an AP: wifi_connect <ssid>\x1f<pass> (0x1F lets ssid/pass contain spaces). */
    fun wifiJoin(ssid: String, pass: String) = wifiJoinService.join(ssid, pass)

    /** Drop the device's station link: wifi_disconnect. */
    fun wifiLeave() = wifiJoinService.leave()

    /** Broadcast a message over ESP-NOW: espnow_send <message>. Result on [espnowStatus]. */
    fun espnowSend(message: String) = espNow.send(message)

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
    private val recordingBuffer = RecordingBuffer()
    /** Recording/unsaved-data/limit/integrity state, published by the buffer under its own lock. */
    val recordingState: StateFlow<RecordingBuffer.State> = recordingBuffer.state
    private val _recordingExporting = MutableStateFlow(false)
    val recordingExporting: StateFlow<Boolean> = _recordingExporting.asStateFlow()

    /** Start capturing the device mic to memory. Ensures the mic stream is live first. */
    fun startRecording() {
        if (_recordingExporting.value) { _micPcmStatus.value = "Wait for the recording export to finish"; return }
        if (client == null) { _micPcmStatus.value = "Connect the device over USB first."; return }
        if (_device.value?.caps?.mic != true) { _micPcmStatus.value = "No confirmed device microphone"; return }
        if (recordingBuffer.hasData) { _micPcmStatus.value = "Save or discard the previous recording first"; return }
        if (!_micPcmActive.value) startMicPcm()
        if (!_micPcmActive.value) return
        recordingBuffer.start()
    }

    /**
     * Stop capturing and export the recording off the main thread: a standard WAV plus one PNG per
     * visualization type (waveform, spectrum, spectrogram). [onDone] gets the written files (empty on
     * failure / no audio) and the recording's stream continuity back on the main thread.
     */
    fun stopRecordingAndExport(
        context: android.content.Context,
        onDone: (List<java.io.File>, RecordingBuffer.Integrity) -> Unit,
    ) {
        if (_recordingExporting.value) { onDone(emptyList(), RecordingBuffer.Integrity()); return }
        val snapshot = recordingBuffer.snapshot()
        if (snapshot.bytes.size < 2) { onDone(emptyList(), snapshot.integrity); return }
        _recordingExporting.value = true
        val appContext = context.applicationContext
        viewModelScope.launch(Dispatchers.Default) {
            val files = try {
                app.brucia.audio.AudioExport.saveAll(appContext, snapshot.bytes, 16000).files
            } catch (_: Exception) { emptyList() }
            if (files.isNotEmpty()) recordingBuffer.commit(snapshot)
            withContext(Dispatchers.Main) {
                _recordingExporting.value = false
                if (files.isEmpty()) _micPcmStatus.value = "Export failed; captured audio retained for retry"
                onDone(files, snapshot.integrity)
            }
        }
    }

    /** Preserve samples when leaving the screen or losing the transport. */
    fun pauseRecording() = recordingBuffer.stop()

    /** Drop an in-progress recording without exporting (e.g. leaving the Audio screen). */
    fun cancelRecording() = recordingBuffer.cancel()

    /** Status line shared by the Audio tools (mic/record). */
    private val _micPcmStatus = MutableStateFlow("")
    val micPcmStatus: StateFlow<String> = _micPcmStatus.asStateFlow()

    /**
     * Subscribe the device-mic PCM stream (caps.mic). Safe to call repeatedly: while mic.pcm is on
     * or starting it does nothing, so an Audio screen recreated by a rotation sends no second ON.
     */
    fun startMicPcm() {
        if (client == null) return
        if (_device.value?.caps?.mic != true) return
        if (subscriptions.isOn("mic.pcm")) return
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

    // --- SSTV encoding and speaker PCM share one owned run, including preparation ---
    private val sstvStream by lazy { pcmStreamService() }
    val sstvTx get() = sstvStream.status

    fun sstvTransmit(img: Sstv.Image, mode: Sstv.Mode) {
        val snapshot = Sstv.Image(img.width, img.height, img.pixels.copyOf())
        sstvStream.start(PcmStreamSpec(rxMax = _device.value?.rxMax), "Encoding ${mode.name}...", "Transmitting ${mode.name}...") { check ->
            val pcm = Sstv.encode(snapshot, mode, check)
            PcmStreamSource(PcmSource.arrayReader(pcm), pcm.size.toLong())
        }
    }

    fun sstvTransmitStop() = sstvStream.stop()

    /**
     * Play a DTMF digit natively on the device speaker (speaker_dtmf). The firmware synthesizes the
     * dual tone straight to I2S at full scale (gain carries the volume, like the RTTTL songs) so it
     * is actually audible - the earlier generator path came out ~10x too quiet.
     */
    fun sendDtmf(key: Char, ms: Int = 200) = speaker.dtmf(key, ms)

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
        if (dtmfJob != null) return
        startMicPcm()
        if (!_micPcmActive.value) return
        _dtmfListening.value = true
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
        dictionaryTest.cancel()
        fused.removeLocationUpdates(locationCallback)
        stopPhoneMic()
        // Link first (it still unsubscribes over its open transport), then the owner: ends a flash,
        // closes any held port and refuses every later handoff.
        disconnect()
        usbOwner.close()
        if (BruciaLog.diagnostics === diagnosticsExport) BruciaLog.diagnostics = null
    }

    private val diagnosticsExport: () -> List<String> = {
        Diagnostics.exportLines(linkCounters.snapshot(), _deviceDiag.value)
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
        BruciaLog.diagnostics = diagnosticsExport // log export header (local only)
    }
}

/** Local copies of pulled files (filesDir/pulled), not reachable by the user: only the newest are kept. */
private object PulledFiles {
    val PREFIXES = listOf("pull-", "capture-")
    const val KEEP = 20
}
