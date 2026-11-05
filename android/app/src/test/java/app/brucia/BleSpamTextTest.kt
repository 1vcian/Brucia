package app.brucia

import app.brucia.session.BurstLoop
import app.brucia.session.BurstLoopRegistry
import app.brucia.session.CommandRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Honest framing for the BLE advertising tools (BLE spam / Apple-style advertisements, iBeacon,
 * Fast Pair / Swift Pair / Samsung).
 *
 * The device sends advertisements of a given type; whether a receiver shows a pairing popup/prompt
 * depends on its OS version and settings and is not verified here. The UI texts must describe what
 * is broadcast and not promise an effect on a Mac / iPhone / Android / Windows / Samsung receiver.
 * This test pins that the old effect-promise phrasings are gone and the honest wording is present,
 * and that a firmware busy/error reply (e.g. the 409 the advertiser returns when a scan or HID
 * session owns the stack) is surfaced to the user rather than a generic success.
 */
class BleSpamTextTest {
    private val mainActivity = File("src/main/java/app/brucia/MainActivity.kt")
    private val builtins = File("src/main/java/app/brucia/features/BuiltinFeatures.kt")

    @Test fun sourcesFound() {
        assertTrue("MainActivity.kt not found", mainActivity.isFile)
        assertTrue("BuiltinFeatures.kt not found", builtins.isFile)
    }

    @Test fun noUnverifiedEffectClaimsInBleTools() {
        val main = mainActivity.readText()
        val feat = builtins.readText()
        val banned = listOf(
            // Apple / Fast Pair cards promising popups appear on a receiver.
            "fake Apple ProximityPair pairing popups to nearby devices",
            "trigger pairing popups on nearby Android devices",
            // iBeacon claiming a receiver will react.
            "apps/OS that watch for beacons will see it",
            // Spam hub tiles promising popups rather than describing the advertisement.
            "BLE Fast Pair: Google pairing popups.",
            "BLE Swift Pair popups.",
            "BLE EasySetup / Buds popups.",
            "fake AirPods, AppleTV and Setup-new-device prompts.",
            // Feature registry promising popup floods.
            "Bluetooth pairing-popup floods",
            // Titles that assert the popup as a fact.
            "BLE spam (Apple popups)",
            "Fast Pair spam (Android popups)",
        )
        val found = banned.filter { main.contains(it) || feat.contains(it) }
        assertTrue("unverified effect claim(s) still present: $found", found.isEmpty())
    }

    @Test fun honestFramingPresent() {
        val main = mainActivity.readText()
        val feat = builtins.readText()
        // The Apple and Fast Pair native cards and the iBeacon card all carry the "not been
        // verified" qualifier.
        assertTrue(
            "expected at least 3 'has not been verified' qualifiers in the BLE tools",
            Regex("has not been verified").findAll(main).count() >= 3,
        )
        // Spam-hub platform tiles describe the advertisement and mark the effect unverified.
        assertTrue("spam hub tiles missing the 'not verified' qualifier",
            main.contains("Receiver reaction depends on OS version and settings; not verified."))
        // Feature registry no longer promises an effect.
        assertTrue("feature registry missing honest BLE spam description",
            feat.contains("receiver reaction is unverified"))
    }

    /**
     * A firmware ERR reply (busy/conflict or advertise failure) must surface to the user via the
     * burst loop status, not a success. Models the 409 the advertiser returns while a scan owns the
     * stack: the loop ends inactive and shows the device's message.
     */
    @Test(timeout = 5000) fun firmwareBusyReplyIsSurfacedNotSuccess() {
        val loop = BurstLoop(
            CoroutineScope(Dispatchers.Unconfined),
            CommandRunner { _, _ -> false to "ble scan active (stop the scan first)" },
            { true }, BurstLoopRegistry(),
        )
        loop.start("Sending ios... (Stop to end)", { "ble_spam_platform ios 6 10" }, 10_000)
        assertFalse("loop must stop on a firmware busy reply", loop.active.value)
        assertEquals("ble scan active (stop the scan first)", loop.status.value)
    }
}
