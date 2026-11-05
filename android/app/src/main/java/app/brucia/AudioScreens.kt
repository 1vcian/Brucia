package app.brucia

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.brucia.audio.Fft
import app.brucia.audio.Oscilloscope
import app.brucia.audio.Sstv
import app.brucia.wardrive.WardriveViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import app.brucia.ui.BruciaButtonDefaults
import app.brucia.ui.Button
import app.brucia.ui.OutlinedButton

// Shared sample rate for all Audio tools (matches the ESP mic/speaker contract).
private const val AUDIO_FS = 16000

// --- small shared UI bits -------------------------------------------------------

@Composable
private fun AudioHeader(title: String, subtitle: String, connected: Boolean, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Image(painter = androidx.compose.ui.res.painterResource(R.drawable.ic_flame), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.material3.MaterialTheme.colorScheme.primary),  contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(8.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = cs.primary)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (connected) Color(0xFF3DDC84) else cs.surfaceVariant))
        }
        Text(subtitle, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (!connected) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect over USB") }
        }
    }
}

/** Cold -> warm -> hot colour ramp for magnitude displays. */
private fun magColor(v: Float): Color {
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

private fun concat(a: ShortArray, b: ShortArray, maxLen: Int): ShortArray {
    val total = a.size + b.size
    if (total <= maxLen) return a + b
    val out = ShortArray(maxLen)
    val keepFromA = (maxLen - b.size).coerceAtLeast(0)
    if (keepFromA > 0) System.arraycopy(a, a.size - keepFromA, out, 0, keepFromA)
    System.arraycopy(b, (b.size - (maxLen - keepFromA)).coerceAtLeast(0), out, keepFromA, maxLen - keepFromA)
    return out
}

// --- Combined parallel dashboard ------------------------------------------------

/**
 * The default Audio experience: Spectrum, Waterfall, Oscilloscope/vectorscope and SSTV all rendered
 * LIVE and SIMULTANEOUSLY as separate stacked panels in one scrollable screen. The device mic is
 * subscribed exactly once here (a single [WardriveViewModel.micPcm] collection); the same PCM chunks
 * are fanned to every panel, so the four renderers never open the stream four times. Opening the
 * screen starts mic.pcm (auto-stopping any spam per startMicPcm); leaving it stops the stream.
 */
@Composable
internal fun AudioDashboardScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val connected = status == "Connected"
    val pcm by vm.micPcm.collectAsState() // single subscription, fanned to all panels below

    DisposableEffect(connected, device?.caps?.mic) {
        if (connected && device?.caps?.mic == true) vm.startMicPcm()
        onDispose { vm.stopMicPcm() }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AudioHeader("Audio", "All mic visualizations live in parallel, from a single mic feed", connected, onConnect)
        RecordBar(vm, connected)
        SpectrumPanel(pcm)
        WaterfallPanel(pcm)
        ScopePanel(vm, pcm)
        SstvCascadePanel(pcm)
    }
}

// --- Record: capture the mic and export WAV + a PNG per visualization type ------

@Composable
private fun RecordBar(vm: WardriveViewModel, connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val recState by vm.recordingState.collectAsState()
    val recording = recState.active
    val ready = recState.hasData
    val exporting by vm.recordingExporting.collectAsState()
    var elapsed by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(recording) {
        if (recording) { elapsed = 0; while (true) { kotlinx.coroutines.delay(1000); elapsed++ } }
    }
    DisposableEffect(Unit) { onDispose { vm.pauseRecording() } }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (recording || ready) {
                            msg = "Saving..."
                            vm.stopRecordingAndExport(context) { files, integrity ->
                                if (files.isEmpty()) msg = "Export failed or no audio; captured data is kept for retry"
                                else {
                                    msg = "Saved ${files.size} files; ${integrity.summary()}"
                                    shareAudioFiles(context, files)
                                }
                            }
                        } else { msg = ""; vm.startRecording() }
                    },
                    enabled = (connected || ready) && !exporting,
                    colors = BruciaButtonDefaults.buttonColors(
                        containerColor = if (recording) Color(0xFFD32F2F) else cs.primary
                    ),
                ) { Text(if (recording) "Stop & save" else if (ready) "Save recording" else "Record") }
                Spacer(Modifier.size(12.dp))
                if (recording) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFD32F2F)))
                    Spacer(Modifier.size(6.dp))
                    Text("REC  ${"%d:%02d".format(elapsed / 60, elapsed % 60)}", color = cs.onSurface)
                } else {
                    Text(when {
                            msg.isNotBlank() -> msg
                            ready && recState.limitReached -> "Recording limit reached; save the captured audio"
                            else -> "Save WAV + spectrum / waterfall / scope PNGs"
                        },
                        color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!recording && msg.startsWith("Saved")) {
                Text("Exported to app files; share sheet opened.", color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Open a share sheet for the exported recording files via the app FileProvider. */
private fun shareAudioFiles(context: android.content.Context, files: List<java.io.File>) {
    try {
        val uris = ArrayList(files.map {
            androidx.core.content.FileProvider.getUriForFile(context, "app.brucia.fileprovider", it)
        })
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, "Share recording").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (e: Exception) { app.brucia.BruciaLog.w("Audio", "open url", e) }
}

// --- Panel 1: Spectrum ----------------------------------------------------------

@Composable
private fun SpectrumPanel(pcm: ShortArray) {
    val cs = MaterialTheme.colorScheme
    val bars = 64
    val level = remember(pcm) { Fft.rms(pcm) }
    val peakHz = remember(pcm) { if (pcm.size >= 256) Fft.dominantFreq(pcm, AUDIO_FS, 2048) else 0.0 }
    val barVals = remember(pcm) {
        val size = Fft.floorPow2(pcm.size).coerceAtMost(2048)
        if (size < 64) FloatArray(bars) else {
            val mag = Fft.magnitude(pcm, size)
            var mx = 1e-9; for (m in mag) if (m > mx) mx = m
            val half = mag.size
            FloatArray(bars) { b ->
                val lo = (b.toLong() * half / bars).toInt()
                val hi = ((b + 1).toLong() * half / bars).toInt().coerceAtLeast(lo + 1)
                var s = 0.0; var c = 0
                for (k in lo until hi.coerceAtMost(half)) { s += mag[k]; c++ }
                val avg = if (c > 0) s / c else 0.0
                Fft.toDbNorm(avg, mx, -55.0)
            }
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp)) {
            Text("Spectrum", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp)) {
                val w = size.width / bars
                for (i in 0 until bars) {
                    val v = barVals.getOrElse(i) { 0f }
                    val h = v * size.height
                    drawRect(magColor(v), topLeft = Offset(i * w, size.height - h), size = Size(w * 0.82f, h))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Peak ${peakHz.roundToInt()} Hz   Level ${(level * 100).roundToInt()}%",
                color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// --- Panel 2: Waterfall ---------------------------------------------------------

@Composable
private fun WaterfallPanel(pcm: ShortArray) {
    val cs = MaterialTheme.colorScheme
    val cols = 240
    val bins = 256
    val fb = remember { IntArray(cols * bins) }
    val bmp = remember { createBitmap(cols, bins, Bitmap.Config.ARGB_8888) }
    var ver by remember { mutableIntStateOf(0) }

    LaunchedEffect(pcm) {
        if (pcm.size >= 512) {
            val mag = Fft.magnitude(pcm, 512) // 256 bins
            var mx = 1e-9; for (m in mag) if (m > mx) mx = m
            for (y in 0 until bins) {
                val base = y * cols
                System.arraycopy(fb, base + 1, fb, base, cols - 1)
                val binIdx = bins - 1 - y // top row = high frequency
                val v = Fft.toDbNorm(mag[binIdx], mx, -55.0)
                val c = magColor(v)
                fb[base + cols - 1] = 0xFF000000.toInt() or
                    ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
            }
            bmp.setPixels(fb, 0, cols, 0, 0, cols, bins)
            ver++
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp)) {
            Text("Waterfall", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Canvas(Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp)) {
                val v = ver // subscribe to updates
                if (v >= 0) {
                    drawImage(
                        image = bmp.asImageBitmap(),
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(cols, bins),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    )
                }
            }
            Text("Time right -> left; vertical axis 0 - 8000 Hz (top = high).",
                color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// --- Panel 3: Oscilloscope / vectorscope ----------------------------------------
//
// Three modes, toggled in the panel:
//   Time      - the classic 2D scope: X = time, Y = amplitude, centred baseline, grid + trace.
//   XY mono   - mono phase plot (delay embedding) X = s[n], Y = s[n+d]: draws Lissajous-like figures
//               from one channel. True stereo oscilloscope-music needs a stereo source.
//   XY 2-mic  - true 2-channel vectorscope: X = ESP mic, Y = phone mic (captured via AudioRecord).

@Composable
internal fun ScopePanel(vm: WardriveViewModel, pcm: ShortArray) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    var scopeMode by remember { mutableIntStateOf(0) } // 0=Time, 1=XY mono, 2=XY 2-mic

    // Time-mode controls.
    var timebase by remember { mutableFloatStateOf(1024f) }  // samples per screen
    var gain by remember { mutableFloatStateOf(1.5f) }
    var triggerLevel by remember { mutableFloatStateOf(0f) }
    var triggerMode by remember { mutableIntStateOf(0) }   // 0=rising, 1=falling, 2=auto
    // XY-mono control.
    var xyDelay by remember { mutableFloatStateOf(24f) }     // samples of delay for the phase plot

    // Rolling ESP buffer (shared by Time and XY-mono).
    var buf by remember { mutableStateOf(ShortArray(0)) }
    LaunchedEffect(pcm) { if (pcm.isNotEmpty()) buf = concat(buf, pcm, 8192) }

    // Phone mic for the dual-mic vectorscope (captured only while that mode is selected).
    val phonePcm by vm.phoneMicPcm.collectAsState()
    var hasAudioPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasAudioPerm = granted
        if (granted && scopeMode == 2) vm.startPhoneMic()
    }
    DisposableEffect(scopeMode, hasAudioPerm) {
        if (scopeMode == 2) {
            if (hasAudioPerm) vm.startPhoneMic() else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            vm.stopPhoneMic()
        }
        onDispose { vm.stopPhoneMic() }
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp)) {
            Text("Oscilloscope / Vectorscope", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Time", "XY mono", "XY 2-mic").forEachIndexed { i, label ->
                    val sel = scopeMode == i
                    OutlinedButton(
                        onClick = { scopeMode = i }, modifier = Modifier.weight(1f),
                        colors = if (sel) BruciaButtonDefaults.outlinedButtonColors(containerColor = cs.primary.copy(alpha = 0.2f)) else BruciaButtonDefaults.outlinedButtonColors(),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
            Spacer(Modifier.height(8.dp))

            when (scopeMode) {
                0 -> TimeScope(cs, buf, timebase, gain, triggerLevel, triggerMode)
                1 -> XyScope(cs, buf, xyDelay.roundToInt(), isDual = false, dualX = null, dualY = null)
                else -> XyScope(cs, buf, 0, isDual = true, dualX = buf, dualY = phonePcm)
            }

            Spacer(Modifier.height(8.dp))
            when (scopeMode) {
                0 -> {
                    val win = Oscilloscope.window(buf, timebase.roundToInt(), triggerLevel.roundToInt(),
                        rising = triggerMode == 0, auto = triggerMode == 2)
                    val vpp = Oscilloscope.peakToPeak(win)
                    val rms = Oscilloscope.rms(win)
                    val freq = Oscilloscope.fundamentalHz(win, AUDIO_FS)
                    Text("Vpp $vpp   RMS ${rms.roundToInt()}   Fundamental ${freq.roundToInt()} Hz",
                        color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Rising", "Falling", "Auto").forEachIndexed { i, label ->
                            val sel = triggerMode == i
                            OutlinedButton(
                                onClick = { triggerMode = i }, modifier = Modifier.weight(1f),
                                colors = if (sel) BruciaButtonDefaults.outlinedButtonColors(containerColor = cs.primary.copy(alpha = 0.2f)) else BruciaButtonDefaults.outlinedButtonColors(),
                            ) { Text(label, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    Text("Trigger level ${triggerLevel.roundToInt()}", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Slider(value = triggerLevel, onValueChange = { triggerLevel = it }, valueRange = -20000f..20000f, enabled = triggerMode != 2)
                    Text("Time base ${timebase.roundToInt()} samples/screen", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Slider(value = timebase, onValueChange = { timebase = it }, valueRange = 128f..4096f)
                    Text("Vertical gain ${"%.1f".format(gain)}x", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Slider(value = gain, onValueChange = { gain = it }, valueRange = 0.5f..8f)
                }
                1 -> {
                    Text("Mono phase plot: X = sample n, Y = sample n+d. Draws Lissajous-like figures from tonal audio. True stereo XY (oscilloscope music) needs a stereo source; this is the mono phase view.",
                        color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text("Delay d = ${xyDelay.roundToInt()} samples (${"%.1f".format(xyDelay / AUDIO_FS * 1000f)} ms)", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Slider(value = xyDelay, onValueChange = { xyDelay = it }, valueRange = 2f..400f)
                }
                else -> {
                    Text("True 2-channel vectorscope: X = ESP mic, Y = phone mic. Place the ESP near one speaker and the phone near the other, then play a stereo / oscilloscope-music source to draw a figure. The two mics are free-running, so the figure may rotate or drift - that is expected.",
                        color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (!hasAudioPerm) {
                        Spacer(Modifier.height(4.dp))
                        Text("Phone-mic permission denied; grant RECORD_AUDIO to use the dual-mic scope.", color = cs.error, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Grant mic access") }
                    } else if (phonePcm.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Waiting for phone mic...", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** The classic 2D time-domain scope: time across X, amplitude on Y, centred baseline, grid + trace. */
@Composable
private fun TimeScope(
    cs: androidx.compose.material3.ColorScheme,
    buf: ShortArray,
    timebase: Float,
    gain: Float,
    triggerLevel: Float,
    triggerMode: Int,
) {
    val win = remember(buf, timebase, triggerLevel, triggerMode) {
        Oscilloscope.window(buf, timebase.roundToInt(), triggerLevel.roundToInt(),
            rising = triggerMode == 0, auto = triggerMode == 2)
    }
    val cols = 10
    val rows = 8
    val msPerDiv = timebase / cols / AUDIO_FS * 1000f
    val countsPerDiv = (32768f / gain.coerceAtLeast(0.01f)) / (rows / 2f)
    Box {
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val grid = cs.onSurfaceVariant.copy(alpha = 0.22f)
            // Vertical divisions (time) and horizontal divisions (amplitude): a real 2D graticule.
            for (i in 0..cols) {
                val x = size.width * i / cols
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
            for (i in 0..rows) {
                val y = size.height * i / rows
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            // Centred 0 baseline.
            val mid = size.height / 2f
            drawLine(cs.onSurfaceVariant.copy(alpha = 0.5f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.5f)
            // Waveform trace (connected line).
            val n = win.size
            if (n >= 2) {
                val stepX = size.width / (n - 1)
                val scale = gain * (size.height / 2f) / 32768f
                var prev = Offset(0f, mid - win[0].toInt() * scale)
                for (i in 1 until n) {
                    val cur = Offset(i * stepX, mid - win[i].toInt() * scale)
                    drawLine(cs.primary, prev, cur, strokeWidth = 2f)
                    prev = cur
                }
            }
            // Trigger level marker.
            if (triggerMode != 2) {
                val ty = mid - triggerLevel * (gain * (size.height / 2f) / 32768f)
                drawLine(Color(0xFFFF8A00), Offset(0f, ty), Offset(size.width, ty), strokeWidth = 1f)
            }
        }
        Text("${countsPerDiv.roundToInt()} cnt/div", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopStart).padding(2.dp))
        Text("${"%.1f".format(msPerDiv)} ms/div", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
    }
}

/** XY vectorscope on a centred square graticule: either the mono phase plot or the dual-mic plot. */
@Composable
private fun XyScope(
    cs: androidx.compose.material3.ColorScheme,
    buf: ShortArray,
    delay: Int,
    isDual: Boolean,
    dualX: ShortArray?,
    dualY: ShortArray?,
) {
    val maxPoints = 2000
    val pts = remember(buf, delay, isDual, dualX, dualY) {
        val (xa, ya) = if (isDual) {
            Oscilloscope.pairChannels(dualX ?: ShortArray(0), dualY ?: ShortArray(0))
        } else {
            Oscilloscope.delayEmbed(buf, delay)
        }
        val n = min(xa.size, ya.size)
        val from = (n - maxPoints).coerceAtLeast(0)
        val peak = (Oscilloscope.peakAbs(xa).coerceAtLeast(Oscilloscope.peakAbs(ya))).coerceAtLeast(1)
        Triple(xa.copyOfRange(from, n), ya.copyOfRange(from, n), peak)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth(0.9f).aspectRatio(1f)) {
            val grid = cs.onSurfaceVariant.copy(alpha = 0.22f)
            val divs = 8
            for (i in 0..divs) {
                val x = size.width * i / divs
                val y = size.height * i / divs
                drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawLine(cs.onSurfaceVariant.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.2f)
            drawLine(cs.onSurfaceVariant.copy(alpha = 0.5f), Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.2f)
            val (xs, ys, peak) = pts
            val n = min(xs.size, ys.size)
            if (n >= 2) {
                val sx = (size.width / 2f) * 0.92f / peak
                val sy = (size.height / 2f) * 0.92f / peak
                var prev = Offset(cx + xs[0].toInt() * sx, cy - ys[0].toInt() * sy)
                for (i in 1 until n) {
                    val cur = Offset(cx + xs[i].toInt() * sx, cy - ys[i].toInt() * sy)
                    // Fade older samples for a trailing-phosphor look.
                    val a = 0.15f + 0.85f * i / n
                    drawLine(cs.primary.copy(alpha = a), prev, cur, strokeWidth = 1.5f)
                    prev = cur
                }
            }
        }
    }
}

// --- Panel 4: SSTV cascade (all modes in parallel) ------------------------------

private val SSTV_MODES = listOf(
    "Robot36" to Sstv.Mode.ROBOT36,
    "Robot72" to Sstv.Mode.ROBOT72,
    "Martin M1" to Sstv.Mode.MARTIN_M1,
    "Martin M2" to Sstv.Mode.MARTIN_M2,
    "Scottie S1" to Sstv.Mode.SCOTTIE_S1,
    "Scottie S2" to Sstv.Mode.SCOTTIE_S2,
)

/**
 * Continuous, always-listening SSTV receive. One [Sstv.StreamDecoder] per mode runs in parallel on
 * the same incoming PCM, so whichever mode a transmission uses decodes without the user pre-selecting
 * it. Each decoder scrolls its own image (a cascade of decoded lines), shown together in a grid - so
 * the matching mode fills in with the real picture while the others stay noise.
 */
@Composable
internal fun SstvCascadePanel(pcm: ShortArray) {
    val cs = MaterialTheme.colorScheme
    val decoders = remember { SSTV_MODES.map { Sstv.StreamDecoder(it.second) } }
    val fbs = remember { SSTV_MODES.map { IntArray(it.second.mWidth * it.second.mHeight) } }
    val bmps = remember { mutableStateListOf<Bitmap?>(null, null, null, null, null, null) }
    val rows = remember { mutableStateListOf(0, 0, 0, 0, 0, 0) }

    LaunchedEffect(pcm) {
        if (pcm.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            for (i in SSTV_MODES.indices) {
                val mode = SSTV_MODES[i].second
                val dec = decoders[i]
                dec.feed(pcm)
                val lines = dec.drainLines()
                if (lines.isEmpty()) continue
                val fb = fbs[i]
                val w = mode.mWidth
                val h = mode.mHeight
                for (line in lines) {
                    System.arraycopy(fb, w, fb, 0, w * (h - 1))
                    val base = w * (h - 1)
                    for (x in 0 until w) fb[base + x] = 0xFF000000.toInt() or (line[x] and 0xFFFFFF)
                }
                rows[i] = rows[i] + lines.size
                bmps[i] = Bitmap.createBitmap(fb, w, h, Bitmap.Config.ARGB_8888)
            }
        }
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(12.dp)) {
            Text("SSTV - all modes decoded in parallel", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Text("Always listening; each cell scrolls decoded lines as they arrive (cascade).",
                color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            SSTV_MODES.indices.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { i ->
                        SstvCell(cs, SSTV_MODES[i].first, bmps[i], rows[i], Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SstvCell(cs: androidx.compose.material3.ColorScheme, label: String, bmp: Bitmap?, lines: Int, modifier: Modifier) {
    Column(modifier) {
        Text("$label  ($lines)", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = "SSTV $label",
                modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit)
        } else {
            Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(6.dp)).background(cs.surfaceVariant),
                contentAlignment = Alignment.Center) { Text("...", color = cs.onSurfaceVariant) }
        }
    }
}

// --- SSTV tool (receive cascade + transmit) -------------------------------------

private fun bitmapToSstv(bmp: Bitmap, mode: Sstv.Mode): Sstv.Image {
    val scaled = bmp.scale(mode.mWidth, mode.mHeight, filter = true)
    val px = IntArray(mode.mWidth * mode.mHeight)
    scaled.getPixels(px, 0, mode.mWidth, 0, 0, mode.mWidth, mode.mHeight)
    val img = Sstv.Image(mode.mWidth, mode.mHeight)
    for (i in px.indices) img.pixels[i] = px[i] and 0xFFFFFF
    return img
}

@Composable
internal fun SstvScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val connected = status == "Connected"
    val speakerOk = device?.caps?.speaker == true
    val pcm by vm.micPcm.collectAsState() // single mic subscription; fanned to the all-modes decoders
    val tx by vm.sstvTx.collectAsState()

    var txMode by remember { mutableStateOf(Sstv.Mode.ROBOT36) }

    DisposableEffect(connected, device?.caps?.mic) {
        if (connected && device?.caps?.mic == true) vm.startMicPcm()
        onDispose { vm.stopMicPcm() }
    }
    DisposableEffect(Unit) { onDispose { vm.sstvTransmitStop() } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val bmp = try { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
            if (bmp != null) vm.sstvTransmit(bitmapToSstv(bmp, txMode), txMode)
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AudioHeader("SSTV", "Decode (live cascade, all modes) and transmit slow-scan TV - all DSP on the phone", connected, onConnect)
        RecordBar(vm, connected)

        // RX: always-listening cascade, every mode in parallel.
        SstvCascadePanel(pcm)

        // TX
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Transmit", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Spacer(Modifier.height(6.dp))
                Text("Transmit mode", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                SSTV_MODES.forEach { (label, m) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !tx.active) { txMode = m }.padding(vertical = 4.dp),
                    ) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(if (txMode == m) cs.primary else cs.surfaceVariant))
                        Spacer(Modifier.size(10.dp))
                        Text(label, color = cs.onSurface)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Pick an image; it is encoded to ${modeLabel(txMode)} audio and streamed to the device speaker.",
                    color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                if (tx.active) {
                    LinearProgressIndicator(progress = { tx.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                }
                Text(tx.message.ifBlank { "Idle." }, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                if (tx.active) {
                    OutlinedButton(onClick = { vm.sstvTransmitStop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                } else {
                    Button(enabled = connected && speakerOk, modifier = Modifier.fillMaxWidth(),
                        onClick = { picker.launch("image/*") }) { Text("Pick image and transmit") }
                    if (!speakerOk) Text("This device has no speaker.", color = cs.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun modeLabel(m: Sstv.Mode): String = SSTV_MODES.firstOrNull { it.second == m }?.first ?: m.name

// --- Oscilloscope tool (full controls) ------------------------------------------

@Composable
internal fun OscilloscopeScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val connected = status == "Connected"
    val pcm by vm.micPcm.collectAsState()

    DisposableEffect(connected, device?.caps?.mic) {
        if (connected && device?.caps?.mic == true) vm.startMicPcm()
        onDispose { vm.stopMicPcm() }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AudioHeader("Oscilloscope", "2D time-domain scope and XY vectorscope from the device mic", connected, onConnect)
        RecordBar(vm, connected)
        ScopePanel(vm, pcm)
    }
}

// --- Tone / DTMF ----------------------------------------------------------------

@Composable
internal fun ToneScreen(vm: WardriveViewModel, onConnect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val status by vm.status.collectAsState()
    val device by vm.device.collectAsState()
    val connected = status == "Connected"
    val speakerOk = device?.caps?.speaker == true
    val pcm by vm.micPcm.collectAsState()
    val tx by vm.sstvTx.collectAsState()

    val dtmfHeard by vm.dtmfHeard.collectAsState()
    val listening by vm.dtmfListening.collectAsState()
    // The detector/decoder only run while listening (mic on). Mic and speaker are half-duplex, so the
    // mic is off otherwise and the generator / keypad can use the speaker. Always stop on leaving.
    DisposableEffect(Unit) { onDispose { vm.stopDtmfDecode() } }

    val toneHz = remember(pcm) { if (pcm.size >= 256) Fft.dominantFreq(pcm, AUDIO_FS, 2048) else 0.0 }
    val level = remember(pcm) { Fft.rms(pcm) }

    var genFreq by remember { mutableFloatStateOf(1000f) }
    var genMs by remember { mutableFloatStateOf(500f) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AudioHeader("Tone", "Decode DTMF from the ESP mic; generate tones / DTMF on the speaker", connected, onConnect)

        // DTMF decoder: press Start to put the ESP mic in listening mode; the phone decodes (Goertzel)
        // and shows the keys heard. Press Stop to end. Listens to an EXTERNAL DTMF source held near the
        // device mic (not the keypad below - mic and speaker share the audio path, so the device can't
        // hear its own output).
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface),
            border = if (listening) androidx.compose.foundation.BorderStroke(2.dp, cs.primary) else null) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("DTMF decoder", fontWeight = FontWeight.SemiBold, color = cs.primary)
                    if (listening) {
                        Spacer(Modifier.width(8.dp))
                        Text("LISTENING", color = cs.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    if (dtmfHeard.isNotEmpty()) OutlinedButton(onClick = { vm.clearDtmfHeard() }) { Text("Clear") }
                }
                Spacer(Modifier.height(6.dp))
                Text("Hold an external DTMF source (another phone, a call) near the device mic, then Start. " +
                    "The device can't decode its own keypad (mic and speaker are half-duplex).",
                    color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(dtmfHeard.ifBlank { "-" }, color = cs.onSurface, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                if (!listening) {
                    Button(enabled = connected && device?.caps?.mic == true, onClick = { vm.startDtmfDecode() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Start listening")
                    }
                } else {
                    Button(onClick = { vm.stopDtmfDecode() }, modifier = Modifier.fillMaxWidth(),
                        colors = BruciaButtonDefaults.buttonColors(containerColor = cs.error)) { Text("Stop") }
                }
                // Live diagnostics while listening: mic level + the strongest DTMF low/high group
                // frequency and its power. This tells you at a glance whether the mic is picking up
                // the tones at all (power) and whether the right frequencies land (e.g. 697 + 1209 = 1).
                if (listening) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Level", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.width(60.dp))
                        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(4.dp)).background(cs.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(10.dp).clip(RoundedCornerShape(4.dp)).background(cs.primary))
                        }
                    }
                    val heard = remember(pcm) {
                        if (pcm.size >= 256) {
                            val lp = app.brucia.audio.Dtmf.LOW.map { app.brucia.audio.Goertzel.power(pcm, AUDIO_FS, it) }
                            val hp = app.brucia.audio.Dtmf.HIGH.map { app.brucia.audio.Goertzel.power(pcm, AUDIO_FS, it) }
                            val li = lp.indices.maxByOrNull { lp[it] } ?: 0
                            val hi = hp.indices.maxByOrNull { hp[it] } ?: 0
                            Triple(app.brucia.audio.Dtmf.LOW[li].toInt() to app.brucia.audio.Dtmf.HIGH[hi].toInt(),
                                maxOf(lp[li], hp[hi]), minOf(lp[li], hp[hi]))
                        } else null
                    }
                    if (heard != null) {
                        val (freqs, pk, _) = heard
                        Text("Heard: ${freqs.first} + ${freqs.second} Hz   power ${"%.5f".format(pk)}",
                            color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Generator", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Spacer(Modifier.height(6.dp))
                Text("Frequency: ${genFreq.roundToInt()} Hz", color = cs.onSurfaceVariant)
                Slider(value = genFreq, onValueChange = { genFreq = it }, valueRange = 100f..4000f)
                Text("Duration: ${genMs.roundToInt()} ms", color = cs.onSurfaceVariant)
                Slider(value = genMs, onValueChange = { genMs = it }, valueRange = 50f..2000f)
                val vol by vm.speakerVolume.collectAsState()
                var volSlider by remember { mutableFloatStateOf(vol.toFloat()) }
                Text("Speaker volume: ${volSlider.roundToInt()}%", color = cs.onSurfaceVariant)
                Slider(
                    value = volSlider,
                    onValueChange = { volSlider = it },
                    onValueChangeFinished = { vm.setSpeakerVolume(volSlider.roundToInt()) },
                    valueRange = 0f..100f,
                    enabled = connected && speakerOk,
                )
                Spacer(Modifier.height(6.dp))
                Button(enabled = connected && speakerOk, modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.speakerTone(genFreq.roundToInt(), genMs.roundToInt()) }) { Text("Play tone") }
                if (!speakerOk) Text("This device has no speaker.", color = cs.error, style = MaterialTheme.typography.bodySmall)
                val spk by vm.speakerStatus.collectAsState()
                if (spk.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(spk, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Songs", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Spacer(Modifier.height(4.dp))
                Text("Play meme / classic tunes on the device speaker (composed on the phone, note by note).",
                    color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                val playing by vm.songPlaying.collectAsState()
                app.brucia.audio.Rtttl.PRESETS.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { song ->
                            val on = playing == song.name
                            OutlinedButton(
                                enabled = connected && speakerOk,
                                modifier = Modifier.weight(1f),
                                onClick = { if (on) vm.stopSong() else vm.playSong(song.name, song.rtttl) },
                            ) { Text(if (on) "- ${song.name}" else song.name, maxLines = 1) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (playing.isNotBlank()) {
                    Button(onClick = { vm.stopSong() }, modifier = Modifier.fillMaxWidth()) { Text("Stop ($playing)") }
                }
            }
        }

        // --- Melody composer: build a custom RTTTL tune note-by-note and play it on the device ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("Composer", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Spacer(Modifier.height(4.dp))
                Text("Tap notes to build a melody, then play it on the device speaker. Save it to keep it.",
                    color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))

                var steps by remember { mutableStateOf(listOf<String>()) }
                var dur by remember { mutableIntStateOf(8) }
                var oct by remember { mutableIntStateOf(5) }
                var bpm by remember { mutableFloatStateOf(120f) }
                var songName by remember { mutableStateOf("") }
                val customSongs by vm.customSongs.collectAsState()
                val composerPlaying by vm.songPlaying.collectAsState()

                // Duration picker.
                Text("Note length", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 4, 8, 16).forEach { d ->
                        val sel = dur == d
                        OutlinedButton(
                            onClick = { dur = d }, modifier = Modifier.weight(1f),
                            colors = if (sel) BruciaButtonDefaults.outlinedButtonColors(containerColor = cs.primary.copy(alpha = 0.18f)) else BruciaButtonDefaults.outlinedButtonColors(),
                        ) { Text("1/$d", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Octave stepper.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Octave $oct", color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    OutlinedButton(enabled = oct > 3, onClick = { if (oct > 3) oct-- }) { Text("-") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = oct < 7, onClick = { if (oct < 7) oct++ }) { Text("+") }
                }
                Spacer(Modifier.height(6.dp))
                // Note buttons (two rows) + rest.
                val notes = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
                notes.chunked(6).forEach { rowNotes ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowNotes.forEach { n ->
                            OutlinedButton(
                                onClick = { steps = steps + "$dur${n.lowercase()}$oct" },
                                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                            ) { Text(n, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { steps = steps + "${dur}p" }, modifier = Modifier.weight(1f)) { Text("Rest") }
                    OutlinedButton(enabled = steps.isNotEmpty(), onClick = { steps = steps.dropLast(1) }, modifier = Modifier.weight(1f)) { Text("Undo") }
                    OutlinedButton(enabled = steps.isNotEmpty(), onClick = { steps = emptyList() }, modifier = Modifier.weight(1f)) { Text("Clear") }
                }
                Spacer(Modifier.height(8.dp))
                // Tempo.
                Text("Tempo: ${bpm.roundToInt()} BPM", color = cs.onSurfaceVariant)
                Slider(value = bpm, onValueChange = { bpm = it }, valueRange = 40f..300f)
                // Sequence preview.
                Text(
                    if (steps.isEmpty()) "No notes yet." else steps.joinToString(" "),
                    color = cs.onSurface, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = connected && speakerOk && steps.isNotEmpty(), modifier = Modifier.weight(1f),
                        onClick = { vm.playSong("Composer", app.brucia.audio.Rtttl.build("Composer", bpm.roundToInt(), steps)) },
                    ) { Text("Play") }
                    OutlinedButton(enabled = composerPlaying.isNotBlank(), onClick = { vm.stopSong() }, modifier = Modifier.weight(1f)) { Text("Stop") }
                }
                Spacer(Modifier.height(8.dp))
                // Save.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = songName, onValueChange = { songName = it }, singleLine = true,
                        label = { Text("Name") }, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = steps.isNotEmpty(), onClick = {
                        vm.saveCustomSong(songName, app.brucia.audio.Rtttl.build(songName, bpm.roundToInt(), steps))
                        songName = ""
                    }) { Text("Save") }
                }
                // Saved custom songs.
                if (customSongs.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("My songs", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    customSongs.forEachIndexed { i, s ->
                        val on = composerPlaying == s.name
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                enabled = connected && speakerOk, modifier = Modifier.weight(1f),
                                onClick = { if (on) vm.stopSong() else vm.playSong(s.name, s.rtttl) },
                            ) { Text(if (on) "- ${s.name}" else s.name, maxLines = 1) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { vm.deleteCustomSong(i) }) { Text("Del") }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text("DTMF keypad", fontWeight = FontWeight.SemiBold, color = cs.primary)
                Spacer(Modifier.height(6.dp))
                Text("Played on the device speaker (ESP).", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                val rows = listOf("123A", "456B", "789C", "*0#D")
                rows.forEach { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        r.forEach { key ->
                            OutlinedButton(enabled = connected && speakerOk && !tx.active, onClick = { vm.sendDtmf(key) },
                                modifier = Modifier.weight(1f)) { Text(key.toString()) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
