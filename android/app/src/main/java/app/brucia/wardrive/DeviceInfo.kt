package app.brucia.wardrive

/** Parsed device capabilities from the CAPS handshake. */
data class DeviceInfo(
    val board: String,
    val chip: String,
    val fw: String,
    val modules: List<String>,
)
