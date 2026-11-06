package app.brucia

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.brucia.net.LocalNetworkAccess
import app.brucia.netscan.ScanHost
import app.brucia.netscan.ServicePort
import app.brucia.netscan.VulnHit
import app.brucia.ui.AdaptiveLayout
import app.brucia.ui.BruciaTheme
import app.brucia.ui.CenteredMaxWidth
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

/**
 * Renders the REAL home dashboard and the real Network Scan page (not preview replicas).
 * The two phone snapshots homeGridPhone / networkScanPhone were recorded before the adaptive
 * layout existed and must stay pixel-identical. The tablet ones use the same window as the
 * emulator check: 1920x1200 px at 240 dpi = 1280x800 dp.
 */
class AdaptiveLayoutScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun homeGridPhone() {
        paparazzi.snapshot { Screen { HomeGrid() } }
    }

    @Test
    fun networkScanPhone() {
        paparazzi.snapshot { Screen { ToolFrame { NetworkScanSample() } } }
    }

    @Test
    fun networkScanPhoneLanNeedsPermission() {
        paparazzi.snapshot { Screen { ToolFrame { NetworkScanSample(LocalNetworkAccess.Status.NEEDS_PERMISSION, hosts = emptyList()) } } }
    }

    @Test
    fun networkScanPhoneLanBlocked() {
        paparazzi.snapshot { Screen { ToolFrame { NetworkScanSample(LocalNetworkAccess.Status.BLOCKED, hosts = emptyList()) } } }
    }

    @Test
    fun homeGridTabletLandscape() {
        paparazzi.unsafeUpdateConfig(deviceConfig = TABLET_LANDSCAPE)
        paparazzi.snapshot { Screen { HomeGrid(connected = false) } }
    }

    @Test
    fun homeGridTabletPortrait() {
        paparazzi.unsafeUpdateConfig(deviceConfig = TABLET_PORTRAIT)
        paparazzi.snapshot { Screen { HomeGrid(connected = false) } }
    }

    @Test
    fun networkScanTabletLandscape() {
        paparazzi.unsafeUpdateConfig(deviceConfig = TABLET_LANDSCAPE)
        paparazzi.snapshot { Screen { ToolFrame { NetworkScanSample() } } }
    }

    @Test
    fun networkScanTabletPortrait() {
        paparazzi.unsafeUpdateConfig(deviceConfig = TABLET_PORTRAIT)
        paparazzi.snapshot { Screen { ToolFrame { NetworkScanSample() } } }
    }
}

private val TABLET_LANDSCAPE = DeviceConfig.PIXEL_TABLET.copy(
    screenWidth = 1920, screenHeight = 1200, xdpi = 240, ydpi = 240,
    density = Density.HIGH, orientation = ScreenOrientation.LANDSCAPE,
)
private val TABLET_PORTRAIT = TABLET_LANDSCAPE.copy(
    screenWidth = 1200, screenHeight = 1920, orientation = ScreenOrientation.PORTRAIT,
)

/** The same wrapper AppRoot puts around every tool screen. */
@Composable
private fun ToolFrame(content: @Composable () -> Unit) {
    CenteredMaxWidth(AdaptiveLayout.ToolMaxWidth) { content() }
}

@Composable
private fun Screen(content: @Composable () -> Unit) {
    BruciaTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun HomeGrid(connected: Boolean = true) {
    HomeDashboard(category = -1, caps = null, connected = connected, onConnect = {}, onOpen = {})
}

private val sampleHosts = listOf(
    ScanHost("192.168.1.1", listOf(
        ServicePort(53, "dns"),
        ServicePort(80, "http", version = "lighttpd/1.4.59"),
    )),
    ScanHost("192.168.1.20", listOf(
        ServicePort(22, "ssh", version = "SSH-2.0-OpenSSH_7.4", vulns = listOf(
            VulnHit("CVE-2018-15473", "OpenSSH username enumeration", "medium", "https://nvd.nist.gov/vuln/detail/CVE-2018-15473"),
        )),
        ServicePort(445, "smb"),
    )),
    ScanHost("192.168.1.42", listOf(ServicePort(9100, "printer"))),
)

@Composable
private fun NetworkScanSample(
    lan: LocalNetworkAccess.Status = LocalNetworkAccess.Status.READY,
    hosts: List<ScanHost> = sampleHosts,
) {
    NetworkScanContent(
        subnet = "192.168.1.0/24", localIp = "192.168.1.57", hosts = hosts,
        scanning = false, progress = if (hosts.isEmpty()) 0 else 254, total = 254, lanStatus = lan,
    )
}
