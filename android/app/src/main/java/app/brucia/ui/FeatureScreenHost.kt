package app.brucia.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.brucia.features.FeatureScreenRegistry
import app.brucia.ui.TextButton
import app.brucia.wardrive.CapabilityEvidence
import app.brucia.wardrive.DeviceCaps

internal typealias FeatureScreen = @Composable () -> Unit

/** Generic host: screen registration determines the destination; the shell owns navigation. */
@Composable
internal fun FeatureScreenHost(
    registry: FeatureScreenRegistry<FeatureScreen>,
    tabId: Int,
    caps: DeviceCaps?,
    evidence: CapabilityEvidence? = null,
    onHome: () -> Unit,
) {
    val destination = registry.byTabId(tabId)
    if (destination == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Tool not available", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onHome) { Text("Back to Home") }
        }
        return
    }
    // Separate remember/rememberSaveable and leave effects even when two registrations
    // invoke the same composable (the filtered Spam screens). Rotation retains this key.
    key(destination.feature.id) {
        val cap = destination.feature.cap
        if (caps != null && !destination.handlesCapability && !caps.allows(cap)) {
            CapabilityDisabled(capMessage(cap), CapabilityEvidence.unavailableReason(cap, evidence))
        } else {
            destination.screen()
        }
    }
}

/** User-facing "this device has no <module>" message for a missing capability. */
private fun capMessage(cap: String?): String = when (cap) {
    "wifi" -> "This device has no Wi-Fi radio."
    "cc1101" -> "This device has no CC1101 sub-GHz module."
    "nfc" -> "No supported NFC reader was detected or reported by this firmware."
    "ble" -> "This device has no BLE radio."
    "ir" -> "This device has no IR module."
    "nrf24" -> "This device has no NRF24L01 module."
    "fm" -> "This device has no FM radio module."
    "lora" -> "This device has no LoRa module"
    "eth" -> "This device has no Ethernet"
    "mic" -> "This device has no microphone."
    "speaker" -> "This device has no speaker."
    else -> "This feature is not available on this device."
}

/** Shown in place of a tool page when the connected device lacks the required module. */
@Composable
private fun CapabilityDisabled(message: String, evidenceReason: String? = null) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Module not available", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onSurface)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        // CAPS d.evidence: why the firmware reports it unavailable (probed absent, busy, no pins...).
        if (evidenceReason != null) {
            Spacer(Modifier.height(8.dp))
            Text(evidenceReason, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Attach the required module and reconnect to enable this tool.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}
