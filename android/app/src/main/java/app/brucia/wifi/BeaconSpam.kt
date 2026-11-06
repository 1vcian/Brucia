package app.brucia.wifi

import app.brucia.session.BurstLoop

/**
 * Beacon spam (feature org.brucia.spam.spamwifi) status texts. Each `wifi_beacon_spam` burst
 * replies `beacon spam done (<secs>s, <n> ssids)` (src/companion/wifi.cpp), where `<n>` is the
 * number of newline-separated entries the device broadcast in each pass, after its own list
 * handling (the 9000-character cap, commas split into separate SSIDs). That count can differ
 * from what the app sent, so the running text shows the device's count once a burst has
 * replied, and the requested count only while no reply carried one.
 */
object BeaconSpam {
    private val DEVICE_COUNT = Regex("""\(\d+s, (\d+) ssids\)""")

    /** The SSID count the device reported in a burst reply, or null when the reply lacks it. */
    fun deviceCount(reply: String?): Int? =
        reply?.let { DEVICE_COUNT.find(it) }?.groupValues?.get(1)?.toIntOrNull()

    /** Running text for [requested] SSIDs, using the device's count from [reply] when present. */
    fun runningText(requested: Int, reply: String?): String {
        val device = deviceCount(reply) ?: return "Broadcasting $requested SSID(s)... (Stop to end)"
        return if (device == requested) "Broadcasting $device SSID(s) (device count)... (Stop to end)"
        else "Broadcasting $device SSID(s) (device count; $requested requested)... (Stop to end)"
    }

    /**
     * Start [loop] as repeated `wifi_beacon_spam <burstSecs> <ssids>` bursts. The status starts
     * with the requested count and follows the device's count from each successful reply.
     */
    fun start(loop: BurstLoop, ssids: List<String>, burstSecs: Int, timeoutMs: Long) {
        val payload = ssids.joinToString("\n")
        val requested = ssids.size
        loop.start(
            runningText(requested, null),
            { "wifi_beacon_spam $burstSecs $payload" },
            timeoutMs,
            progress = { reply -> runningText(requested, reply) },
        )
    }
}
