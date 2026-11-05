package app.brucia

import app.brucia.ui.LeaveEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.brucia.mitm.MitmCa
import app.brucia.net.LocalNetworkAccess
import app.brucia.net.LocalNetworkGateCard
import app.brucia.net.rememberLocalNetworkGate
import kotlinx.coroutines.launch
import app.brucia.mitm.MitmController
import app.brucia.mitm.MitmLog
import app.brucia.mitm.MitmTxn
import app.brucia.ui.BruciaButtonDefaults
import app.brucia.ui.Button
import app.brucia.ui.OutlinedButton

/**
 * ARP-spoof card inputs. [MitmScreen] owns them above its list/detail switch, so opening a request
 * detail (the list, card included, leaves composition) keeps what was typed. The SSID and the two
 * addresses are also saved across rotation and process death; the Wi-Fi password is deliberately
 * not saved ([Saver] leaves it out), it lives only while the screen is open, as before.
 */
@Stable
internal class ArpSpoofForm(ssid: String = "", target: String = "", gateway: String = "") {
    var ssid by mutableStateOf(ssid)
    var password by mutableStateOf("")
    var target by mutableStateOf(target)
    var gateway by mutableStateOf(gateway)

    companion object {
        val Saver: Saver<ArpSpoofForm, Any> = listSaver(
            save = { listOf(it.ssid, it.target, it.gateway) },
            restore = { ArpSpoofForm(it[0], it[1], it[2]) },
        )
    }
}

@Composable
internal fun rememberArpSpoofForm(): ArpSpoofForm = rememberSaveable(saver = ArpSpoofForm.Saver) { ArpSpoofForm() }

@Composable
private fun ArpSpoofCard(vm: app.brucia.wardrive.WardriveViewModel, connected: Boolean, form: ArpSpoofForm) {
    val cs = MaterialTheme.colorScheme
    val active by vm.arpSpoofActive.collectAsState()
    val status by vm.arpSpoofStatus.collectAsState()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surface)) {
        Column(Modifier.padding(14.dp)) {
            Text("ARP spoof / NetCut (device)", fontWeight = FontWeight.SemiBold, color = cs.primary)
            Text(
                "The device joins a Wi-Fi network and poisons the ARP table between a target and the gateway (cut its access, or route through the device). Authorized / lawful use only.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(form.ssid, { form.ssid = it }, label = { Text("Wi-Fi SSID") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(form.password, { form.password = it }, label = { Text("Wi-Fi password") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(form.target, { form.target = it }, label = { Text("Target IP (e.g. 192.168.1.42)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(form.gateway, { form.gateway = it }, label = { Text("Gateway IP (e.g. 192.168.1.1)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
                colors = BruciaButtonDefaults.buttonColors(
                    containerColor = if (active) androidx.compose.ui.graphics.Color(0xFFD32F2F) else cs.primary),
                onClick = {
                    if (active) vm.stopArpSpoof()
                    else vm.startArpSpoof(form.ssid.trim(), form.password.trim(), form.target.trim(), form.gateway.trim())
                },
            ) { Text(if (active) "Stop ARP spoof" else "Start ARP spoof") }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

/**
 * MITM intercepting-proxy tool UI. Phone-only (needs no connected device): it runs the
 * on-phone HTTP(S) proxy, exports the root CA, and shows a live transaction log with a
 * detail view. Authorized use only.
 */
@Composable
internal fun MitmScreen(vm: app.brucia.wardrive.WardriveViewModel, connected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The proxy accepts connections from LAN clients and may reach LAN servers: local network
    // access (Android 17, target 37) is checked before it starts.
    val lan = rememberLocalNetworkGate()
    LeaveEffect { vm.stopArpSpoof(silent = true) }

    val running by MitmLog.running.collectAsState()
    val port by MitmLog.port.collectAsState()
    val localIp by MitmLog.localIp.collectAsState()
    val error by MitmLog.error.collectAsState()
    val txns by MitmLog.txns.collectAsState()

    var portText by rememberSaveable { mutableStateOf(port.toString()) }
    var selected by remember { mutableStateOf<MitmTxn?>(null) }
    val arpForm = rememberArpSpoofForm()

    // The list state is remembered above the list/detail switch, so Back from a
    // transaction detail returns to the same scroll position instead of the top.
    ListDetailSwitch(selected, detail = { MitmDetail(it, onBack = { selected = null }) }) { listState ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("MITM proxy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onSurface)
            }
            item { ArpSpoofCard(vm, connected, arpForm) }

            // Prominent authorized-use warning.
            item {
                Card(colors = CardDefaults.cardColors(containerColor = cs.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Authorized use only", fontWeight = FontWeight.Bold, color = cs.onErrorContainer)
                        Text(
                            "This terminates TLS to read HTTPS traffic. Use it only on devices and " +
                                "networks you own or are explicitly authorized to test. Intercepting " +
                                "other people's traffic is illegal in most jurisdictions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onErrorContainer,
                        )
                    }
                }
            }

            // Controls.
            item {
                Card(colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!running) {
                            LocalNetworkGateCard(lan.status, "The MITM proxy", { lan.request() }, { lan.openSettings() },
                                { scope.launch { lan.checkBeforeStart(MitmController.localIpv4()) } })
                        }
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("Listen port") },
                            enabled = !running,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (running) {
                            Button(
                                onClick = { MitmController.stop() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Stop proxy") }
                        } else {
                            Button(
                                onClick = {
                                    val p = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080
                                    scope.launch {
                                        if (lan.checkBeforeStart(MitmController.localIpv4())) MitmController.start(context, p)
                                    }
                                },
                                enabled = LocalNetworkAccess.canStart(lan.status),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Start proxy") }
                        }
                        if (running) {
                            val ip = localIp ?: "this phone's Wi-Fi IP"
                            Text(
                                "Running. On the target device, set the HTTP proxy to:",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                            Text(
                                "$ip : $port",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = cs.primary,
                            )
                            Text(
                                "Both devices must share the same network. Alternatively, the ESP32 " +
                                    "device could ARP-redirect a target to this phone (not yet wired up).",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error)
                        }
                    }
                }
            }

            // CA export + honest limitation note.
            item {
                Card(colors = CardDefaults.cardColors(containerColor = cs.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Root certificate", fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        Text(
                            "To decrypt HTTPS, install this CA on your own target device " +
                                "(Settings > Security > Install a certificate > CA certificate).",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                val f = runCatching { MitmCa.exportCaCert(context) }.getOrNull()
                                if (f != null) mitmShareFile(context, f, "application/x-x509-ca-cert")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Export CA certificate (.crt)") }
                        Text(
                            "Note: apps using HSTS or certificate pinning will refuse interception " +
                                "even with the CA installed. Those connections appear in the log as " +
                                "non-decrypted tunnels.",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }

            // Log header.
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Transactions (${txns.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (txns.isNotEmpty()) {
                        OutlinedButton(onClick = { MitmLog.clear() }) { Text("Clear") }
                    }
                }
            }

            if (txns.isEmpty()) {
                item {
                    Text(
                        if (running) "Waiting for traffic from the target..." else "Start the proxy to capture traffic.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            } else {
                mitmRows(txns) { t -> MitmRow(t, onClick = { selected = t }) }
            }
        }
    }
}

/**
 * Transaction rows keyed by [MitmTxn.id] (unique per entry, see [MitmLog.add]): when new traffic
 * is added at the top, the list keeps the row the user is looking at instead of its index, also
 * across a request detail (the list state then anchors on the key when the list comes back).
 */
internal fun LazyListScope.mitmRows(txns: List<MitmTxn>, row: @Composable (MitmTxn) -> Unit) {
    items(txns, key = { it.id }) { row(it) }
}

@Composable
private fun MitmRow(t: MitmTxn, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val statusColor = when {
        t.status < 0 -> cs.error
        t.status in 200..299 -> Color(0xFF4CAF50)
        t.status in 300..399 -> cs.primary
        else -> cs.error
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = cs.surface),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (t.status < 0) "ERR" else t.status.toString(),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
                Spacer(Modifier.size(8.dp))
                Text(t.method, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Spacer(Modifier.size(8.dp))
                Text(
                    if (t.decrypted) t.scheme else "${t.scheme} (tunnel)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (t.decrypted) cs.primary else cs.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text("${t.durationMs} ms", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            Text(
                "${t.host}${t.path}",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface,
                maxLines = 2,
            )
            if (t.note.isNotEmpty()) {
                Text(t.note, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            } else {
                Text(
                    "req ${t.reqBytes} B   resp ${t.respBytes} B",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * List/detail switch whose list scroll state outlives the detail page: the [LazyListState] is
 * remembered here, above the branch, instead of inside the list that leaves composition while
 * [selected] is shown.
 */
@Composable
internal fun <T : Any> ListDetailSwitch(
    selected: T?,
    detail: @Composable (T) -> Unit,
    list: @Composable (LazyListState) -> Unit,
) {
    val listState = rememberLazyListState()
    if (selected != null) detail(selected) else list(listState)
}

@Composable
private fun MitmDetail(t: MitmTxn, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // System Back returns to the transaction list (same as "Back to list"), not to the dashboard.
    BackHandler { onBack() }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text("Back to list") }
        Spacer(Modifier.height(12.dp))
        Text(t.url, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = cs.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "${t.method}   status ${if (t.status < 0) "-" else t.status}   ${if (t.decrypted) "decrypted" else "not decrypted"}   ${t.durationMs} ms",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
        if (t.note.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(t.note, style = MaterialTheme.typography.bodySmall, color = cs.error)
        }
        MitmBlock("Request headers", t.reqHeaders)
        MitmBlock("Request body", t.reqPreview)
        MitmBlock("Response headers", t.respHeaders)
        MitmBlock("Response body", t.respPreview)
    }
}

@Composable
private fun MitmBlock(title: String, body: String) {
    if (body.isBlank()) return
    val cs = MaterialTheme.colorScheme
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onSurface)
    Spacer(Modifier.height(4.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(8.dp),
    ) {
        Text(body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
    }
}

private fun mitmShareFile(context: android.content.Context, file: java.io.File, mime: String) {
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
