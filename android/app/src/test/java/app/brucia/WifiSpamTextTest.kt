package app.brucia

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Honest framing for the Wi-Fi beacon-spam tool (feature org.brucia.spam.spamwifi).
 *
 * The device broadcasts beacon frames advertising the given SSID names; whether a nearby client's
 * Wi-Fi scanner actually lists them is not verified here (it depends on the scanner, scan timing and
 * de-dup rules). As with the BLE advertising tools (see [BleSpamTextTest]), the card text must
 * describe what is broadcast and not assert the receiver reaction as a fact. This pins that the old
 * "so nearby clients see bogus SSIDs" / "appear as that many separate networks" effect claims are
 * gone and the honest, device-side wording is present.
 */
class WifiSpamTextTest {
    private val mainActivity = File("src/main/java/app/brucia/MainActivity.kt")
    private val evilPortal = File("src/main/java/app/brucia/EvilPortalScreen.kt")

    @Test fun sourcesFound() {
        assertTrue("MainActivity.kt not found", mainActivity.isFile)
        assertTrue("EvilPortalScreen.kt not found", evilPortal.isFile)
    }

    @Test fun noUnverifiedReceiverEffectClaimInEvilPortalDeauth() {
        val text = evilPortal.readText()
        // Asserting the deauthed client joins the fake AP as a fact.
        assertFalse("evil portal deauth text still claims the client joins the fake AP",
            text.contains("Disconnect devices from the real AP so they join this one."))
        // Describes what is sent and marks the reconnection unverified.
        assertTrue("evil portal deauth text missing the 'not verified' qualifier",
            text.contains("Whether a client then reconnects, and to which AP, is up to the client and is not verified here"))
    }

    @Test fun noUnverifiedReceiverEffectClaimInBeaconSpam() {
        val main = mainActivity.readText()
        val banned = listOf(
            // Asserting that the receiver (client) sees / lists the broadcast as a fact.
            "so nearby clients see bogus SSIDs",
            "appear as that many separate networks",
        )
        val found = banned.filter { main.contains(it) }
        assertTrue("unverified receiver-effect claim(s) still present in beacon spam: $found", found.isEmpty())
    }

    @Test fun honestFramingPresentInBeaconSpam() {
        val main = mainActivity.readText()
        // Describes the device action (broadcasting beacon frames) ...
        assertTrue("beacon spam text should describe the broadcast beacon frames",
            main.contains("Broadcasts beacon frames advertising these Wi-Fi network names"))
        // ... and marks the receiver reaction as unverified.
        assertTrue("beacon spam text missing the 'not verified' qualifier",
            main.contains("Whether a nearby device lists them depends on its Wi-Fi scanner and is not verified here"))
    }
}
