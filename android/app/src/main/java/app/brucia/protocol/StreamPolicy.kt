package app.brucia.protocol

/** Streams whose device drivers exclusively own the same peripheral. */
object StreamPolicy {
    val streams: Set<String> = setOf("wifi.ap", "wifi.sta", "wifi.probe", "wifi.monitor",
        "ble.scan", "subghz.raw", "subghz.spectrum", "mic.spectrum", "mic.pcm")
    private val groups = listOf(
        setOf("wifi.ap", "wifi.sta", "wifi.probe", "wifi.monitor"),
        setOf("subghz.raw", "subghz.spectrum"),
        setOf("mic.spectrum", "mic.pcm"),
    )
    fun exclusivePeers(stream: String): Set<String> =
        groups.firstOrNull { stream in it }?.minus(stream) ?: emptySet()
}
