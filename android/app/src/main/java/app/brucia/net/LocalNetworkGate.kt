package app.brucia.net

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.brucia.BruciaLog
import app.brucia.ui.Button
import app.brucia.ui.OutlinedButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** One-datagram LAN reachability check that tells "blocked by the platform" apart from "sent". */
object LocalNetworkProbe {
    /** RFC 863 discard port: a stray byte there is ignored by any host that listens at all. */
    private const val DISCARD_PORT = 9

    /**
     * Sends one 1-byte UDP datagram to [host] (an IPv4 literal, so no DNS lookup). Returns true
     * when the send fails with EPERM (local network access blocked), false when it was handed to
     * the network, null when it failed for another reason (no route, socket error).
     */
    fun udpBlocked(host: String): Boolean? = try {
        DatagramSocket().use { s ->
            s.send(DatagramPacket(byteArrayOf(0), 1, InetAddress.getByName(host), DISCARD_PORT))
        }
        false
    } catch (e: Exception) {
        if (LocalNetworkAccess.isPermissionDenied(e)) {
            BruciaLog.w("LanAccess", "local network blocked: ${e.message}")
            true
        } else {
            BruciaLog.d("LanAccess", "probe inconclusive: ${e.message}")
            null
        }
    }
}

/**
 * Per-screen local network gate: knows whether [LocalNetworkAccess.PERMISSION] is needed on
 * this device and target, asks for it on the user's tap, and records a blocked probe.
 */
@Stable
class LocalNetworkGate internal constructor(private val context: Context) {
    /** Permission to hold, or null (below Android 17 or while the app targets SDK 36 or lower). */
    val required: String? = LocalNetworkAccess.requiredPermission(
        Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion)

    private var granted by mutableStateOf(isGranted())
    private var asked by mutableStateOf(false)
    private var canAskAgain by mutableStateOf(false)
    private var probeBlocked by mutableStateOf(false)
    internal var launcher: ActivityResultLauncher<String>? = null

    val status: LocalNetworkAccess.Status
        get() = LocalNetworkAccess.status(required, granted, asked, canAskAgain, probeBlocked)

    private fun isGranted(): Boolean = required == null ||
        ContextCompat.checkSelfPermission(context, required) == PackageManager.PERMISSION_GRANTED

    /** Shows the system prompt (only when a permission is required and not yet granted). */
    fun request() {
        val p = required ?: return
        if (granted) return
        launcher?.launch(p)
    }

    internal fun onResult(ok: Boolean) {
        asked = true
        granted = ok || isGranted()
        canAskAgain = !granted && (context.findActivity()?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, required ?: return@let false)
        } ?: false)
        if (granted) probeBlocked = false
    }

    /** Re-reads the grant (the user may have changed it in settings while the app was paused). */
    fun refresh() {
        val now = isGranted()
        if (now && !granted) probeBlocked = false
        granted = now
    }

    /**
     * Call right before a LAN tool starts. Returns true when it may run: the permission (if any)
     * is held and a probe to [localIpv4]'s subnet was not refused with EPERM.
     */
    suspend fun checkBeforeStart(localIpv4: String?): Boolean {
        refresh()
        if (required != null && !granted) return false
        val target = LocalNetworkAccess.probeTarget(localIpv4) ?: return true
        probeBlocked = withContext(Dispatchers.IO) { LocalNetworkProbe.udpBlocked(target) } == true
        return !probeBlocked
    }

    /** Opens this app's system settings page (Permissions > Nearby devices lives there). */
    fun openSettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            BruciaLog.w("LanAccess", "open app settings", e)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** Remembers a [LocalNetworkGate] for this screen and re-checks the grant on every resume. */
@Composable
fun rememberLocalNetworkGate(): LocalNetworkGate {
    val context = LocalContext.current
    val gate = remember(context) { LocalNetworkGate(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        gate.onResult(ok)
    }
    SideEffect { gate.launcher = launcher }
    LifecycleResumeEffect(gate) {
        gate.refresh()
        onPauseOrDispose { }
    }
    return gate
}

/**
 * Reason and next step for a LAN tool that cannot start; draws nothing when [status] is
 * [LocalNetworkAccess.Status.READY], so the tool's layout is unchanged then.
 */
@Composable
fun LocalNetworkGateCard(
    status: LocalNetworkAccess.Status,
    toolName: String,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (status == LocalNetworkAccess.Status.READY) return
    val cs = MaterialTheme.colorScheme
    val (title, body) = when (status) {
        LocalNetworkAccess.Status.NEEDS_PERMISSION -> "Local network access needed" to
            "$toolName talks to devices on your Wi-Fi network. Android 17 lets an app reach the " +
            "local network only with the Nearby devices permission. Brucia uses it only while " +
            "$toolName runs, after you start it; internet access is not affected."
        LocalNetworkAccess.Status.DENIED -> "Local network access denied" to
            "$toolName stays disabled until Brucia may reach the local network. Allow it in " +
            "Settings > Apps > Brucia > Permissions > Nearby devices."
        else -> "Local network blocked by Android" to
            "Android refused a test packet to this network (EPERM), so $toolName would only " +
            "time out. Allow Brucia local network access (Nearby devices) in its app settings, " +
            "then retry."
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.errorContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall,
                color = cs.onErrorContainer)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
            Spacer(Modifier.height(10.dp))
            when (status) {
                LocalNetworkAccess.Status.NEEDS_PERMISSION ->
                    Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("Allow local network access") }
                LocalNetworkAccess.Status.DENIED ->
                    Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open app settings") }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("App settings") }
                    Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Retry") }
                }
            }
        }
    }
}
