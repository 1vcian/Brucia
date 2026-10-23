package app.brucia.wardrive

import android.annotation.SuppressLint
import android.app.Application
import android.location.Location
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.brucia.data.ApEntity
import app.brucia.data.BruciaDb
import app.brucia.protocol.CompanionClient
import app.brucia.protocol.Kind
import app.brucia.subghz.DecodedSignal
import app.brucia.subghz.OokSamples
import app.brucia.subghz.SubGhzDecoder
import app.brucia.transport.Transport
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Owns the companion connection, the GPS fix, and persists AP observations. */
class WardriveViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BruciaDb.get(app).apDao()

    private val _status = MutableStateFlow("Disconnected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    private val _wardriving = MutableStateFlow(false)
    val wardriving: StateFlow<Boolean> = _wardriving.asStateFlow()

    private val _subghz = MutableStateFlow<List<DecodedSignal>>(emptyList())
    val subghz: StateFlow<List<DecodedSignal>> = _subghz.asStateFlow()

    /** Access points, newest first, backed by Room (survives app restarts). */
    val aps: StateFlow<List<ApObservation>> =
        dao.observeAll()
            .map { list -> list.map { it.toObservation() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Live analytics over the collected APs (encryption, channels, vendors). */
    val stats: StateFlow<WardriveStats> =
        aps.map { computeStats(it) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, computeStats(emptyList()))

    init {
        // Load the OUI vendor database off the main thread.
        viewModelScope.launch(Dispatchers.IO) { OuiResolver.ensureLoaded(getApplication()) }
    }

    private var client: CompanionClient? = null
    private var readJob: Job? = null
    private var transport: Transport? = null

    @Volatile private var lastLocation: Location? = null
    private val fused = LocationServices.getFusedLocationProviderClient(app)
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { lastLocation = it }
        }
    }

    fun connect(t: Transport) {
        disconnect()
        transport = t
        val c = CompanionClient(t)
        client = c
        _status.value = "Connected"
        readJob = viewModelScope.launch(Dispatchers.IO) {
            c.hello()
            while (isActive) {
                if (transport?.isOpen != true) {
                    // Device unplugged / link lost.
                    _status.value = "Disconnected (device unplugged)"
                    _device.value = null
                    _wardriving.value = false
                    break
                }
                val msg = c.readMessage(1000)
                ensureActive()
                if (msg != null) handle(msg)
            }
        }
    }

    private suspend fun handle(msg: JSONObject) {
        when (msg.optInt("t")) {
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
                )
            }
            Kind.EVENT -> {
                val d = msg.optJSONObject("d") ?: return
                val ev = d.optString("ev")
                if (ev == "subghz.raw") {
                    val arr: JSONArray = d.optJSONObject("data")?.optJSONArray("timings") ?: return
                    val timings = IntArray(arr.length()) { arr.optInt(it) }
                    val decoded = SubGhzDecoder.decode(timings)
                    if (decoded.isNotEmpty()) _subghz.value = (decoded + _subghz.value).take(50)
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
        client?.cmd(cmd)
    }

    fun setWardriving(on: Boolean) {
        client?.subWifiAp(on)
        _wardriving.value = on
    }

    fun setSubghz(on: Boolean) {
        client?.sub("subghz.raw", on)
    }

    /** Decode the bundled synthetic sample (works without hardware, for demo/test). */
    fun decodeSample() {
        val decoded = SubGhzDecoder.decode(OokSamples.princeton24)
        _subghz.value = (decoded + _subghz.value).take(50)
    }

    fun clearSubghz() {
        _subghz.value = emptyList()
    }

    fun clearSession() {
        viewModelScope.launch(Dispatchers.IO) { dao.clear() }
    }

    fun disconnect() {
        readJob?.cancel()
        readJob = null
        client = null
        transport?.close()
        transport = null
        _status.value = "Disconnected"
        _wardriving.value = false
        _device.value = null
    }

    override fun onCleared() {
        fused.removeLocationUpdates(locationCallback)
        disconnect()
    }
}
