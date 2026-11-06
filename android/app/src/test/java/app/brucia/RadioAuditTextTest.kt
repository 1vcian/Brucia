package app.brucia

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Radio feature audit (docs/audit/radio.md): texts must describe what the device does, not promise
 * an unverified effect or a success the app has not seen, and the controls must not hide a write.
 */
class RadioAuditTextTest {
    private val main = File("src/main/java/app/brucia/MainActivity.kt")
    private val vm = File("src/main/java/app/brucia/wardrive/WardriveViewModel.kt")
    private val builtins = File("src/main/java/app/brucia/features/BuiltinFeatures.kt")

    @Test fun sourcesFound() {
        assertTrue(main.isFile && vm.isFile && builtins.isFile)
    }

    @Test fun nfcNdefButtonSaysItWritesAndAsksFirst() {
        // `rfid ndef` WRITES a URL record onto the tag (rfidNdefCallback -> write_ndef); the button
        // was labelled "Read NDEF" and overwrote whatever tag was on the reader.
        val text = main.readText()
        assertTrue("'Read NDEF' label still present", !text.contains("Text(\"Read NDEF\")"))
        assertTrue(text.contains("Text(\"Write NDEF URL...\")"))
        assertTrue(text.contains("onClick = { confirmWrite = \"ndef\" }"))
        assertTrue(text.contains("onClick = { confirmWrite = \"erase\" }"))
        // The only direct calls are inside the confirmation dialog.
        assertTrue(Regex("vm\\.nfcNdef\\(\\)").findAll(text).count() == 1)
        assertTrue(Regex("vm\\.nfcErase\\(\\)").findAll(text).count() == 1)
        assertTrue(text.contains("if (ndef) vm.nfcNdef() else vm.nfcErase()"))
    }

    @Test fun noUnverifiedEffectOrSuccessClaims() {
        val all = main.readText() + vm.readText() + builtins.readText()
        val banned = listOf(
            "Any ESP32 running Bruce 'Connect' receive picks it up",
            "Broadcast a short message over ESP-NOW (any ESP32)",
            "FM transmitter - broadcast audio over the air",
            "Broadcast audio over the FM transmitter",
            "Transmits every code of a fixed-code protocol",
            "Floods IR so nearby receivers can't decode their remotes",
            "Replay the last read tag - the device acts as that card.",
            "\"Sent \${b.name}\"",
            "\"Replayed raw ",
            "noteCapture(\"Sent: ",
            "Contact (name,phone) or vCard",
        )
        val found = banned.filter { all.contains(it) }
        assertTrue("overclaiming text(s) still present: $found", found.isEmpty())
        assertTrue(all.contains("broadcasts are not acknowledged, so delivery is not confirmed"))
        assertTrue(all.contains("digital audio routing"))
    }

    @Test fun bleOneShotsReleaseTheScanFirst() {
        // GATT / BadBLE stop the scan on the device without telling the app; the media hijack is
        // refused (409) while it runs. The VM ends the stream first so the toggle stays truthful.
        val text = vm.readText()
        for (call in listOf("gatt.connect(mac)", "gatt.read(mac, uuid)", "badBle.run(script)", "bleMediaService.send(mac)")) {
            assertTrue("$call does not release the BLE scan first", text.contains("{ releaseBleScan(); $call }"))
        }
        assertTrue(text.contains("if (streamMayRun(\"ble.scan\")) setBleScan(false)"))
    }
}
