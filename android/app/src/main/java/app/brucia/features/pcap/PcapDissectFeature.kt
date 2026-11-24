package app.brucia.features.pcap

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brucia.features.FeatureScreenBinding
import app.brucia.ui.FeatureScreen
import app.brucia.ui.LeaveEffect
import app.brucia.wifi.PcapDissectViewModel
import app.brucia.wifi.PcapDissectSession
import app.brucia.wifi.PcapDissector

/** Phone-only registration: no connection, transport or application-wide ViewModel input. */
internal fun pcapDissectFeature() = FeatureScreenBinding<FeatureScreen>(
    "org.brucia.tools.pcapdissect", screen = { PcapDissectRoute() },
)

@Composable
private fun PcapDissectRoute() {
    val context = LocalContext.current
    // Keep the same Activity-scoped owner: parse/results survive rotation, leave resets them.
    val session = viewModel<PcapDissectViewModel>().session
    val state by session.state.collectAsState()
    LeaveEffect { session.reset() }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.applicationContext.contentResolver
        openPcapDocument(session, resolver, uri)
    }
    PcapDissectScreen(state = state, onOpen = { open.launch(arrayOf("*/*")) })
}

/** Application resolver only: the session can keep reading across Activity recreation. */
internal fun openPcapDocument(session: PcapDissectSession, resolver: ContentResolver, uri: Uri): Boolean =
    session.open(uri.lastPathSegment?.substringAfterLast('/') ?: "capture.pcap") {
        resolver.openInputStream(uri)?.use { PcapDissector.readBounded(it) } ?: ByteArray(0)
    }
