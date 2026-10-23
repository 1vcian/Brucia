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
import org.json.JSONObject

/** Owns the companion connection, the GPS fix, and persists AP observations. */
class WardriveViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BruciaDb.get(app).apDao()

    private val _status = MutableStateFlow("Disconnected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _caps = MutableStateFlow<String?>(null)
    val caps: StateFlow<String?> = _caps.asStateFlow()

    private val _wardriving = MutableStateFlow(false)
    val wardriving: StateFlow<Boolean> = _wardriving.asStateFlow()

    /** Access points, newest first, backed by Room (survives app restarts). */
    val aps: StateFlow<List<ApObservation>> =
        dao.observeAll()
            .map { list -> list.map { it.toObservation() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
                val msg = c.readMessage(1000)
                ensureActive()
                if (msg != null) handle(msg)
            }
        }
    }

    private suspend fun handle(msg: JSONObject) {
        when (msg.optInt("t")) {
            Kind.CAPS -> _caps.value = msg.optJSONObject("d")?.toString()
            Kind.EVENT -> {
                val d = msg.optJSONObject("d") ?: return
                if (d.optString("ev") != "wifi.ap") return
                val r = d.optJSONObject("data") ?: return
                val bssid = r.optString("bssid")
                if (bssid.isEmpty()) return
                val loc = lastLocation
                dao.upsert(
                    ApEntity(
                        bssid = bssid,
                        ssid = r.optString("ssid"),
                        channel = r.optInt("ch"),
                        rssi = r.optInt("rssi"),
                        enc = r.optString("enc"),
                        lat = loc?.latitude,
                        lon = loc?.longitude,
                        timeMillis = System.currentTimeMillis(),
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

    fun setWardriving(on: Boolean) {
        client?.subWifiAp(on)
        _wardriving.value = on
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
    }

    override fun onCleared() {
        fused.removeLocationUpdates(locationCallback)
        disconnect()
    }
}
