package app.brucia.badusb

/**
 * A single DuckyScript (Rubber Ducky) payload entry.
 *
 * This is a plain, immutable data holder used by the phone-side BadUSB library.
 * It carries the metadata shown to the user plus the raw DuckyScript body that
 * can be sent to the device for execution.
 *
 * @property name        Human-readable title shown in the library list.
 * @property category    Grouping label (for example "Basics", "Browser", "Security").
 * @property description Short explanation of what the payload does.
 * @property os          Target operating system: "windows", "macos", "linux" or "any".
 * @property script      The raw DuckyScript body using standard tokens
 *                       (DELAY, STRING, GUI, ENTER, CTRL, ALT, SHIFT, TAB, ...).
 */
data class DuckyScript(
    val name: String,
    val category: String,
    val description: String,
    val os: String,
    val script: String
)
