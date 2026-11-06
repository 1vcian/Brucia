package app.brucia.ble

/**
 * The iBeacon proximity UUID the app may send in `ble_ibeacon <secs> [uuid]`. The firmware
 * (src/companion/ibeacon_uuid.h) refuses anything but the canonical 8-4-4-4-12 hexadecimal form
 * with ERR 400; the app checks first so the burst loop never starts with a value the device
 * would reject. An empty UUID means the device default.
 */
object IbeaconUuid {
    private val CANONICAL = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    const val PROBLEM = "UUID must be 8-4-4-4-12 hex digits (e.g. 8ec76ea3-6668-48da-9866-75be8bc86f4d), or empty for the default."

    /** Null when [uuid] (already trimmed) is empty or canonical, else the user-facing reason. */
    fun problem(uuid: String): String? = if (uuid.isEmpty() || CANONICAL.matches(uuid)) null else PROBLEM
}
