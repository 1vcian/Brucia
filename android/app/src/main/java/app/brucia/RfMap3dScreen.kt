package app.brucia

import android.util.Base64
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.brucia.wardrive.WardriveViewModel
import org.json.JSONArray
import org.json.JSONObject
import app.brucia.ui.Button
import app.brucia.ui.OutlinedButton

/**
 * Interactive 3D map of the RF sources around you, built from signals only the ESP can hear
 * (Wi-Fi APs, BLE devices, sub-GHz peaks on the CC1101). Each emitter is placed in 3D by its
 * RSSI (weaker = farther from you at the centre) and coloured by band; drag to rotate, use the
 * slider to zoom, Save exports a PNG. Approximate by nature - RSSI -> distance is noisy - so it
 * maps RF SOURCES, not room geometry.
 */
@Composable
internal fun RfMap3dScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val status by vm.status.collectAsState()
    val connected = status == "Connected"
    val aps by vm.aps.collectAsState()
    val ble by vm.ble.collectAsState()
    val spectrum by vm.spectrum.collectAsState()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var sourceCount by remember { mutableIntStateOf(0) }

    // Build the source list from the current ESP data: {name, band(0=wifi,1=ble,2=subghz), rssi}.
    fun buildSourcesJson(): String {
        val arr = JSONArray()
        aps.filter { it.ssid.isNotBlank() }.distinctBy { it.bssid }.take(40).forEach {
            arr.put(JSONObject().apply { put("name", it.ssid); put("band", 0); put("rssi", it.rssi) })
        }
        ble.take(40).forEach {
            arr.put(JSONObject().apply { put("name", it.name.ifBlank { it.mac }); put("band", 1); put("rssi", it.rssi) })
        }
        // Sub-GHz: take the strongest few peaks of the latest sweep.
        spectrum?.let { sw ->
            val idx = sw.rssi.indices.sortedByDescending { sw.rssi[it] }.take(8)
            idx.forEach { i ->
                val mhz = (sw.freqs.getOrElse(i) { 0L } / 1_000_000.0)
                arr.put(JSONObject().apply { put("name", "%.2f MHz".format(mhz)); put("band", 2); put("rssi", sw.rssi[i]) })
            }
        }
        return arr.toString()
    }

    fun refresh() {
        val json = buildSourcesJson()
        sourceCount = try { JSONArray(json).length() } catch (_: Exception) { 0 }
        webView?.evaluateJavascript("window.setData($json)", null)
    }

    val savePng = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) {
            webView?.evaluateJavascript("window.getPNG()") { result ->
                try {
                    // result is a JSON string: "data:image/png;base64,....". Strip quotes + prefix.
                    val dataUrl = result.trim().removeSurrounding("\"")
                    val b64 = dataUrl.substringAfter("base64,", "")
                    if (b64.isNotEmpty()) {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        Toast.makeText(context, "3D map saved", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Nothing to save yet - Refresh first", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("RF Map 3D", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (connected) Color(0xFF3DDC84) else cs.surfaceVariant))
        }
        Text("A 3D view of the RF sources around you - Wi-Fi (orange), BLE (blue), sub-GHz (red). " +
            "Placed by signal strength (weaker = farther). Drag to rotate, slider to zoom. Approximate.",
            color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (!connected) {
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
        }

        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    @android.annotation.SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    setBackgroundColor(0xFF0E0E10.toInt())
                    loadDataWithBaseURL(null, RF_MAP_HTML, "text/html", "UTF-8", null)
                    webView = this
                }
            },
            update = { /* data pushed via evaluateJavascript on refresh */ },
            onRelease = { it.destroy(); webView = null }, // free the native WebView when leaving
            modifier = Modifier.fillMaxWidth().height(380.dp).clip(MaterialTheme.shapes.medium),
        )

        Text("Zoom", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        Slider(value = zoom, onValueChange = {
            zoom = it; webView?.evaluateJavascript("window.setZoom($it)", null)
        }, valueRange = 0.4f..2.5f)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { refresh() }, modifier = Modifier.weight(1f)) { Text("Refresh") }
            OutlinedButton(onClick = { savePng.launch("brucia-rfmap.png") }, modifier = Modifier.weight(1f)) { Text("Save PNG") }
        }
        Text("Sources plotted: $sourceCount. Tip: start a Wi-Fi scan, BLE scan and the sub-GHz spectrum sweep first, then Refresh.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

// Self-contained 3D point-cloud renderer (canvas 2D, manual projection) - no external libraries,
// so it runs with network loads blocked. Exposes setData(json), setZoom(z) and getPNG().
private val RF_MAP_HTML = """
<!DOCTYPE html><html><head><meta charset='utf-8'>
<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>
<style>html,body{margin:0;padding:0;background:#0e0e10;overflow:hidden}canvas{display:block;touch-action:none}</style>
</head><body>
<canvas id='c'></canvas>
<script>
var cv=document.getElementById('c'),ctx=cv.getContext('2d');
var DPR=window.devicePixelRatio||1;
var pts=[],rotX=-0.5,rotY=0.6,zoom=1;
var COL=['#ff6d1a','#4aa3ff','#ff4d4d']; // wifi, ble, subghz
function resize(){cv.width=cv.clientWidth*DPR;cv.height=cv.clientHeight*DPR;cv.style.width=cv.clientWidth+'px';draw();}
function layout(data){
  pts=[];
  // group an index per source for an even angular spread per band
  var perBand=[0,0,0],cnt=[0,0,0];
  data.forEach(function(d){cnt[d.band]=(cnt[d.band]||0)+1;});
  data.forEach(function(d){
    var b=d.band||0;
    var i=perBand[b]++;
    var n=Math.max(cnt[b],1);
    // distance from centre (you): weaker RSSI -> farther. rssi in dBm (~ -30 near, -95 far).
    var dist=Math.min(1,Math.max(0.12,(-(d.rssi||-90)-25)/70));
    var theta=(i/n)*Math.PI*2 + b*0.7;        // angle around
    var phi=(b-1)*0.6 + (((i*53)%17)/17-0.5)*0.5; // band -> vertical layer
    var R=1.6*dist;
    pts.push({x:R*Math.cos(phi)*Math.cos(theta),y:R*Math.sin(phi),z:R*Math.cos(phi)*Math.sin(theta),
              c:COL[b]||'#aaa',name:d.name||'',rssi:d.rssi||0,size:Math.max(3,10+(d.rssi||-90)/9)});
  });
  draw();
}
function draw(){
  var w=cv.width,h=cv.height;ctx.clearRect(0,0,w,h);
  ctx.fillStyle='#0e0e10';ctx.fillRect(0,0,w,h);
  var cx=w/2,cy=h/2,f=Math.min(w,h)*0.42*zoom;
  var cosY=Math.cos(rotY),sinY=Math.sin(rotY),cosX=Math.cos(rotX),sinX=Math.sin(rotX);
  function proj(p){
    var x=p.x*cosY - p.z*sinY, z=p.x*sinY + p.z*cosY;
    var y=p.y*cosX - z*sinX, z2=p.y*sinX + z*cosX;
    var pz=z2+4;
    var s=f/pz;
    return {sx:cx+x*s, sy:cy+y*s, s:s, pz:pz};
  }
  // ground ring + centre (you)
  ctx.strokeStyle='rgba(255,255,255,0.12)';ctx.lineWidth=1*DPR;
  ctx.beginPath();for(var a=0;a<=Math.PI*2+0.01;a+=0.1){var gp=proj({x:1.6*Math.cos(a),y:0,z:1.6*Math.sin(a)});if(a===0)ctx.moveTo(gp.sx,gp.sy);else ctx.lineTo(gp.sx,gp.sy);}ctx.stroke();
  var c0=proj({x:0,y:0,z:0});
  ctx.fillStyle='#ffb300';ctx.beginPath();ctx.arc(c0.sx,c0.sy,5*DPR,0,Math.PI*2);ctx.fill();
  // sort far->near
  var ps=pts.map(function(p){var q=proj(p);q.p=p;return q;}).sort(function(a,b){return b.pz-a.pz;});
  ps.forEach(function(q){
    var p=q.p;
    ctx.strokeStyle='rgba(255,255,255,0.08)';ctx.beginPath();ctx.moveTo(c0.sx,c0.sy);ctx.lineTo(q.sx,q.sy);ctx.stroke();
    ctx.fillStyle=p.c;ctx.beginPath();ctx.arc(q.sx,q.sy,p.size*q.s*0.5*DPR,0,Math.PI*2);ctx.fill();
  });
  // label the nearest 6
  ctx.fillStyle='rgba(243,241,239,0.9)';ctx.font=(11*DPR)+'px sans-serif';
  ps.slice(-6).forEach(function(q){ctx.fillText(q.p.name.substring(0,18),q.sx+6*DPR,q.sy-6*DPR);});
}
var drag=false,lx=0,ly=0;
cv.addEventListener('pointerdown',function(e){drag=true;lx=e.clientX;ly=e.clientY;});
cv.addEventListener('pointermove',function(e){if(!drag)return;rotY+=(e.clientX-lx)*0.01;rotX+=(e.clientY-ly)*0.01;lx=e.clientX;ly=e.clientY;draw();});
cv.addEventListener('pointerup',function(){drag=false;});
cv.addEventListener('pointercancel',function(){drag=false;});
window.setData=function(d){try{layout(d);}catch(e){}};
window.setZoom=function(z){zoom=z;draw();};
window.getPNG=function(){try{return cv.toDataURL('image/png');}catch(e){return '';}};
window.addEventListener('resize',resize);
resize();
</script></body></html>
"""
