package app.brucia

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * Also exercises Channels owning/borrowing the Wardrive scan, real Activity rotation with a
 * pending SUB reply, replacement-link reconnect, and the actual command-log Stop button with
 * delayed success/error replies. A subsequent PONG is an observable read barrier: assertions
 * about stale replies run only after the real read loop has processed them.
 * Wardrive's beacon loop survives rotation, stops on leave, and ignores a late burst reply.
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

    private fun connect(device: SimulatedDevice = SimulatedDevice()): SimulatedDevice {
        onMain {
            if (!::vm.isInitialized) vm = ViewModelProvider(compose.activity)[WardriveViewModel::class.java]
            val token = vm.beginCompanionLink()
            assertNotNull("companion token", token)
            assertTrue("transport adopted", vm.connect(requireNotNull(token), device))
        }
        awaitUi("connected") { vm.status.value == "Connected" && compose.shows("Connected") }
        return device
    }

    private fun openWifiTool(name: String) {
        compose.onNodeWithText("Wi-Fi", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText(name, useUnmergedTree = true).performScrollTo().performClick()
    }

    private fun wardriveSwitch() = compose.onNode(
        isToggleable() and hasAnySibling(hasText("Wardriving") or androidx.compose.ui.test.hasAnyDescendant(hasText("Wardriving"))),
        useUnmergedTree = true,
    )

    private fun rotate(orientation: Int, expected: Int) {
        val previous = compose.activity
        onMain { previous.requestedOrientation = orientation }
        awaitUi("Activity recreated after rotation") {
            compose.activity !== previous && compose.activity.resources.configuration.orientation == expected
        }
        compose.waitForIdle()
        onMain { assertSame("rotation retains VM", vm, ViewModelProvider(compose.activity)[WardriveViewModel::class.java]) }
    }

    private fun readBarrier(device: SimulatedDevice, battery: Int) {
        device.reportBattery(battery)
        awaitUi("read loop passed delayed frames") { vm.device.value?.battery == battery }
        compose.waitForIdle()
    }

    @Test
    fun wardriveBeaconLoopSurvivesRotationButStopsOnLeaveBeforeLateReply() {
        val device = connect(SimulatedDevice().apply { holdBeaconReplies = true })
        openWifiTool("Wardrive")
        // One synthetic SSID, simulated transport only: no radio or actual beacon emission.
        onMain { vm.startBeaconSpam(listOf("Lifecycle fixture")) }
        awaitUi("first beacon burst waiting") { device.heldBeacons.size == 1 && vm.beaconActive.value }
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        assertTrue("rotation preserves the loop", vm.beaconActive.value)
        assertEquals(1, device.commands.count { it.startsWith("wifi_beacon_spam ") })

        device.releaseBeaconReplies()
        awaitUi("rotation preserved the next burst") {
            device.commands.count { it.startsWith("wifi_beacon_spam ") } == 2 && device.heldBeacons.size == 1
        }
        Espresso.pressBack()
        compose.waitForIdle()
        assertFalse("leaving Wardrive cancels further bursts immediately", vm.beaconActive.value)
        assertEquals("Stopped.", vm.beaconStatus.value)

        device.releaseBeaconReplies()
        readBarrier(device, 67)
        assertEquals("late ACK cannot schedule another burst", 2,
            device.commands.count { it.startsWith("wifi_beacon_spam ") })
        assertFalse(vm.beaconActive.value)
        assertEquals("Stopped.", vm.beaconStatus.value)
        compose.onNodeWithText("Wardrive", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText("Start beacon spam", useUnmergedTree = true).performScrollTo()
        assertFalse("returning does not restart", vm.beaconActive.value)
        assertEquals("Connected", vm.status.value)
    }

    @Test
    fun channelsOwnScanSurvivesRotationWithPendingAckAndStopsOnceOnLeave() {
        val device = connect(SimulatedDevice().apply { holdSubscriptionStarts = true })
        openWifiTool("Channels")
        awaitUi("Channels requested scan") { device.heldStarts.size == 1 && vm.wardriving.value }
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        assertTrue(compose.shows("Channel Analyzer"))
        assertEquals("rotation sends neither duplicate ON nor OFF", listOf("wifi.ap" to true), device.subs.toList())
        assertTrue(vm.wardriving.value)
        device.releaseSubscriptionStarts()
        readBarrier(device, 61)
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        assertEquals(listOf("wifi.ap" to true), device.subs.toList())

        Espresso.pressBack()
        awaitUi("owned scan stopped on leave") { device.lastSub("wifi.ap") == false && !vm.wardriving.value }
        readBarrier(device, 62)
        assertEquals(listOf("wifi.ap" to true, "wifi.ap" to false), device.subs.toList())
        assertFalse(compose.shows("Channel Analyzer"))
        assertEquals("Connected", vm.status.value)
    }

    @Test
    fun leavingChannelsPreservesTheScanStartedFromWardrive() {
        val device = connect()
        openWifiTool("Wardrive")
        wardriveSwitch().assertIsOff().performClick()
        awaitUi("Wardrive owns scan") { device.lastSub("wifi.ap") == true && vm.wardriving.value }
        Espresso.pressBack()
        compose.onNodeWithText("Channels", useUnmergedTree = true).performScrollTo().performClick()
        awaitUi("Channels borrows scan") { compose.shows("Channel Analyzer") }
        Espresso.pressBack()
        awaitUi("left Channels") { !compose.shows("Channel Analyzer") }
        readBarrier(device, 63)
        assertEquals("borrower must not restart or stop Wardrive", listOf("wifi.ap" to true), device.subs.toList())
        assertTrue(vm.wardriving.value)
        compose.onNodeWithText("Wardrive", useUnmergedTree = true).performScrollTo().performClick()
        wardriveSwitch().assertIsOn().performClick()
        awaitUi("owner stops scan") { device.lastSub("wifi.ap") == false && !vm.wardriving.value }
    }

    @Test
    fun channelsReconnectsWhileOpenAndReleasesOnlyTheNewLinkOnLeave() {
        val first = connect()
        openWifiTool("Channels")
        awaitUi("first scan") { first.lastSub("wifi.ap") == true && vm.wardriving.value }
        onMain { vm.disconnect() }
        awaitUi("disconnected in Channels") { vm.status.value == "Disconnected" && !vm.wardriving.value }
        assertTrue(compose.shows("Channel Analyzer"))
        assertFalse(first.isOpen)
        assertEquals(listOf("wifi.ap" to true, "wifi.ap" to false), first.subs.toList())
        val firstWrites = first.subs.toList()

        val second = connect()
        awaitUi("Channels restarts on replacement link") { second.lastSub("wifi.ap") == true && vm.wardriving.value }
        Espresso.pressBack()
        awaitUi("replacement link stopped on leave") { second.lastSub("wifi.ap") == false && !vm.wardriving.value }
        readBarrier(second, 64)
        assertEquals(listOf("wifi.ap" to true, "wifi.ap" to false), second.subs.toList())
        assertEquals("no cleanup sent to old transport", firstWrites, first.subs.toList())
    }

    @Test
    fun globalStopFromCommandLogFencesLateStartRepliesAcrossRestart() {
        val device = connect(SimulatedDevice().apply { holdSubscriptionStarts = true })
        openWifiTool("Wardrive")
        // Exercise both a delayed success after Stop and a delayed error after a new run starts.
        for (lateSuccess in listOf(true, false)) {
            device.holdSubscriptionStarts = true
            wardriveSwitch().assertIsOff().performClick()
            awaitUi("pending start") { device.heldStarts.size == 1 && vm.wardriving.value }
            compose.onNodeWithText("Cmds", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Stop", useUnmergedTree = true).performClick()
            awaitUi("global Stop wrote OFF") { device.lastSub("wifi.ap") == false && !vm.wardriving.value }
            compose.onNodeWithText("Close", useUnmergedTree = true).performClick()
            wardriveSwitch().assertIsOff()

            if (!lateSuccess) {
                device.holdSubscriptionStarts = false
                wardriveSwitch().performClick()
                awaitUi("new start") { device.lastSub("wifi.ap") == true && vm.wardriving.value }
            }
            val writes = device.subs.toList()
            device.releaseSubscriptionStarts(ok = lateSuccess)
            readBarrier(device, if (lateSuccess) 65 else 66)
            assertEquals("late reply cannot send another SUB", writes, device.subs.toList())
            assertEquals("late reply cannot change the current run", !lateSuccess, vm.wardriving.value)
            if (lateSuccess) wardriveSwitch().assertIsOff() else wardriveSwitch().assertIsOn()
        }
        wardriveSwitch().performClick()
        awaitUi("final cleanup") { device.lastSub("wifi.ap") == false && !vm.wardriving.value }
    }

    @Test
    fun commandHistoryClearIgnoresLateSubscriptionResultAndKeepsNewCliEntry() {
        val device = connect(SimulatedDevice().apply { holdSubscriptionStarts = true })
        openWifiTool("Wardrive")
        onMain {
            vm.clearCommandLog()
            vm.setWardriving(true)
        }
        awaitUi("pending subscription logged") {
            device.heldStarts.size == 1 &&
                vm.commandLog.value.singleOrNull()?.status == app.brucia.wardrive.CmdStatus.RUNNING
        }
        val pending = vm.commandLog.value.single()
        assertEquals("SUB wifi.ap on", pending.text)
        assertNull(pending.doneAtMs)
        onMain { vm.clearCommandLog() }
        device.releaseSubscriptionStarts(ok = false)
        readBarrier(device, 68)
        assertTrue("late result must not resurrect a cleared row", vm.commandLog.value.isEmpty())

        onMain { vm.sendCommand("info") }
        awaitUi("CLI result recorded") {
            vm.commandLog.value.singleOrNull()?.let {
                it.text == "info" && it.status == app.brucia.wardrive.CmdStatus.DONE
            } == true
        }
        val finished = vm.commandLog.value.single()
        assertTrue("clear does not reuse an id", finished.id > pending.id)
        assertEquals(true, finished.ok)
        assertNotNull(finished.doneAtMs)
        assertEquals("Connected", vm.status.value)
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
