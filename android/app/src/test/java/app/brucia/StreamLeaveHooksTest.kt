package app.brucia

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A DisposableEffect's onDispose also runs when the Activity is recreated (rotation, resize,
 * fold/unfold; unavoidable on large screens with targetSdk 36), so a device stream or radio loop
 * stopped there turns off on every rotation. Tool screens stop that work in LeaveEffect, which
 * skips configuration changes and still stops a tool left meanwhile (StreamRotationTest). This
 * check keeps every production screen on that path.
 */
class StreamLeaveHooksTest {

    /** ViewModel/screen calls that stop device streams, radio loops or the mic pipeline. */
    private val stops = listOf(
        "setSubghz", "stopSpectrum", "stopSubghzJammer", "stopRfCapture", "setBleScan", "gattStop",
        "stopIbeacon", "stopMicPcm", "stopPhoneMic", "pauseRecording", "sstvTransmitStop", "stopDtmfDecode",
        "stopStationSniff", "stopProbeSniff", "stopMonitor", "setWardriving", "stopIrJammer", "stopNrf24",
        "stopLoraRecv", "fmStop", "stopWids", "stopArpSpoof", "stopSweep", "stopTimeline", "stopBleSpam",
    )

    private val sources = File("src/main/java").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && !it.path.endsWith("ui/RetainedState.kt") }
        .toList()

    /** Text of every `onDispose { ... }` block (balanced braces). */
    private fun disposeBlocks(text: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = text.indexOf("onDispose", from).takeIf { it >= 0 } ?: break
            val open = text.indexOf('{', at).takeIf { it >= 0 } ?: break
            var depth = 0
            var end = open
            while (end < text.length) {
                when (text[end]) { '{' -> depth++; '}' -> depth-- }
                if (depth == 0) break
                end++
            }
            out += text.substring(open, minOf(end + 1, text.length))
            from = end
        }
        return out
    }

    @Test
    fun sourcesFound() {
        assertTrue("no Kotlin sources under src/main/java", sources.size > 10)
    }

    @Test
    fun noDisposableEffectStopsAStream() {
        val problems = mutableListOf<String>()
        for (f in sources) {
            for (block in disposeBlocks(f.readText())) {
                val hit = stops.firstOrNull { Regex("\\b$it\\w*\\(").containsMatchIn(block) } ?: continue
                problems += "${f.path}: onDispose calls $hit - use LeaveEffect so a rotation does not stop it"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun streamToolsStopTheirStreamsInLeaveEffect() {
        // Screen file to a stop call its LeaveEffect must contain.
        val expected = mapOf(
            "MainActivity.kt" to listOf("vm.setSubghz(false)", "vm.setBleScan(false)", "vm.stopStationSniff()", "vm.stopMonitor()",
                "vm.stopBleSpam()"),
            "AudioScreens.kt" to listOf("vm.stopMicPcm()", "vm.pauseRecording()"),
            "BugSweepScreen.kt" to listOf("stopSweep()"),
            "RfTimelineScreen.kt" to listOf("vm.setBleScan(false)"),
            "RfFingerprintScreen.kt" to listOf("vm.setBleScan(false)"),
        )
        val problems = mutableListOf<String>()
        for ((name, calls) in expected) {
            val text = sources.single { it.name == name }.readText()
            val leaves = Regex("LeaveEffect \\{").findAll(text).map { m ->
                disposeBlocks("onDispose " + text.substring(m.range.last)).firstOrNull().orEmpty()
            }.joinToString("\n")
            calls.filterNot { leaves.contains(it) }.forEach { problems += "$name: no LeaveEffect calls $it" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
