package app.brucia

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.brucia.ui.BruciaTheme
import app.brucia.ui.rgb565ToColor
import app.brucia.wardrive.Finding
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test

/**
 * Content drawn straight on the app background, outside any Surface/Card, exactly as AppRoot hosts
 * the tool screens (a Column with a background, no Surface): the real StreamToggleRow (Sub-GHz
 * Listen, BLE Scan, Wardriving) and FindingRow, plus a bare Text and Icon. Each must take the
 * theme's onBackground, not LocalContentColor's default black. The other screenshot tests wrap
 * their content in a Surface, which hid this.
 */
class ContentColorScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5)

    @Test
    fun streamRowsOnAppBackground() {
        paparazzi.snapshot { BruciaTheme { Rows() } }
    }

    /** Connected to a device whose UI color becomes the app accent (here magenta). */
    @Test
    fun streamRowsOnAppBackgroundWithDeviceAccent() {
        paparazzi.snapshot { BruciaTheme(accent = rgb565ToColor(0xF81F)) { Rows() } }
    }
}

@Composable
private fun Rows() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().background(cs.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StreamToggleRow("Listen", "subghz.raw stream", checked = false, enabled = false, onCheckedChange = {})
        StreamToggleRow("Listen", "subghz.raw stream", checked = true, enabled = true, onCheckedChange = {})
        StreamToggleRow("Scan", "ble.scan stream", checked = false, enabled = true, onCheckedChange = {})
        StreamToggleRow("Wardriving", "wifi.ap stream", checked = true, enabled = true, onCheckedChange = {})
        FindingRow(Finding("high", "Default keys present", "Sector 0 opens with FFFFFFFFFFFF"))
        FindingRow(Finding("medium", "Weak cipher", "Crypto-1"))
        Text("== Brucia report ==", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Text and icon with no explicit color")
        }
    }
}
