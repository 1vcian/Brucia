package app.brucia

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.brucia.DeviceState.waitUntil
import app.brucia.wardrive.WardriveViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real MainActivity + WardriveViewModel wiring driven over the companion protocol by a
 * [SimulatedDevice] plugged in through the same `beginCompanionLink`/`connect` pair the USB path
 * uses. Covers: link up and foreground service, navigation from the dashboard into the Wardrive
 * tool, a stream started from its switch, the device leaving companion mode and the app
 * re-negotiating on the same transport, the global Stop (the entry point behind the command log's
 * Stop button) turning the stream off, and Disconnect releasing transport, service and wake lock.
 *
 * Not covered: the USB permission/driver path, a real board, radio behaviour, timing on hardware.
 */
@RunWith(AndroidJUnit4::class)
class CompanionLinkFlowTest {

    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    )

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var vm: WardriveViewModel

    private fun onMain(block: () -> Unit) = compose.runOnUiThread(block)

    private fun SemanticsNodeInteractionsProvider.shows(text: String): Boolean =
        onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun awaitUi(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        compose.waitUntil(timeoutMs) { cond() }
        assertTrue(what, cond())
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) onMain { vm.disconnect() }
        waitUntil("service stopped") { DeviceState.serviceRecord().isEmpty() }
    }

    @Test
    fun navigateStreamReconnectStopAndDisconnectOverSimulatedLink() {
        onMain { vm = ViewModelProvider(compose.activity)[WardriveViewModel::class.java] }
        val device = SimulatedDevice()
        var token: Long? = null
        var adopted = false
        onMain {
            token = vm.beginCompanionLink()
            adopted = token?.let { vm.connect(it, device) } == true
        }
        assertNotNull("companion token", token)
        assertTrue("simulated transport adopted", adopted)

        // Handshake: HELLO -> CAPS makes the link "Connected" in the ViewModel and on screen.
        awaitUi("connected") { vm.status.value == "Connected" && compose.shows("Connected") }
        assertTrue(device.hellos.get() >= 1)
        waitUntil("foreground service running") { DeviceState.serviceRecord().isNotEmpty() }
        waitUntil("wake lock held") { DeviceState.wakeLockHeld(WAKE_LOCK) }

        // Navigation: dashboard -> Wi-Fi category -> Wardrive tool.
        compose.onNodeWithText("Wi-Fi", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Wardrive", useUnmergedTree = true).performScrollTo().performClick()
        awaitUi("wardrive screen") { compose.shows("Offload companion - wardrive") }
        val wardriveSwitch = compose.onNode(
            isToggleable() and hasAnySibling(hasText("Wardriving") or androidx.compose.ui.test.hasAnyDescendant(hasText("Wardriving"))),
            useUnmergedTree = true,
        )

        // Stream started from the UI: one SUB wifi.ap on reaches the device and is acknowledged.
        wardriveSwitch.assertIsOff().performClick()
        awaitUi("wifi.ap subscribed") { device.lastSub("wifi.ap") == true && vm.wardriving.value }
        wardriveSwitch.assertIsOn()

        // Device drops to its serial CLI with USB open: the app notices, nudges and re-handshakes
        // on the same transport, without the user reconnecting.
        val hellosBefore = device.hellos.get()
        device.leaveCompanion()
        awaitUi("re-negotiating") { vm.status.value.startsWith("Reconnecting") }
        awaitUi("reconnected", timeoutMs = 20_000) {
            vm.status.value == "Connected" && device.nudges.get() >= 1 && device.hellos.get() > hellosBefore
        }
        assertTrue("transport kept across re-negotiation", device.isOpen)

        // Global Stop: whatever the stream state after re-negotiation, nothing may keep running.
        if (device.lastSub("wifi.ap") != true) {
            onMain { assertTrue(vm.setWardriving(true)) }
            awaitUi("wifi.ap subscribed again") { device.lastSub("wifi.ap") == true && vm.wardriving.value }
        }
        onMain { vm.stopAllRunning() }
        awaitUi("wifi.ap stopped") { device.lastSub("wifi.ap") == false && !vm.wardriving.value }
        wardriveSwitch.assertIsOff()
        assertEquals("Stop keeps the link", "Connected", vm.status.value)

        // Disconnect: transport closed, status shown, service and wake lock released.
        onMain { vm.disconnect() }
        awaitUi("disconnected") { vm.status.value == "Disconnected" && !compose.shows("Connected") }
        assertFalse("transport closed", device.isOpen)
        waitUntil("service stopped") { DeviceState.serviceRecord().isEmpty() }
        waitUntil("wake lock released") { !DeviceState.wakeLockHeld(WAKE_LOCK) }

        // Back leaves the tool for the Wi-Fi category, a second Back clears the category filter.
        Espresso.pressBack()
        awaitUi("Wi-Fi category") { !compose.shows("Offload companion - wardrive") && compose.shows("Wardrive") }
        assertFalse(compose.shows("Categories"))
        Espresso.pressBack()
        awaitUi("dashboard categories") { compose.shows("Categories") }
    }

    private companion object {
        const val WAKE_LOCK = "brucia:connection"
    }
}
