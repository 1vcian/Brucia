package app.brucia.files

/**
 * Registry of starter files bundled with the app (in assets/templates). These live on the phone,
 * not on the device: the offload model keeps the ESP32 flash/SD empty until a file is actually
 * needed, at which point the user can push a template to the device from the editor. The list is
 * pure data so it is unit-testable; [content] loads the bytes from assets and needs a Context.
 */
data class FileTemplate(
    /** Human label shown in the "New from template" picker. */
    val title: String,
    /** Default file name suggested when the template is opened or pushed. */
    val fileName: String,
    /** Asset path under assets/. */
    val asset: String,
    val description: String,
)

object FileTemplates {
    const val DIR = "templates"

    val all: List<FileTemplate> = listOf(
        FileTemplate(
            "BruceJS script", "script.js", "$DIR/script.js",
            "Interpreted JS run on the device (script_run / BruceJS).",
        ),
        FileTemplate(
            "Ducky / BadUSB", "payload.ducky", "$DIR/payload.ducky",
            "HID keystroke-injection payload (DuckyScript).",
        ),
        FileTemplate(
            "Sub-GHz capture", "signal.sub", "$DIR/signal.sub",
            "Flipper-style .sub RAW sample for Sub-GHz replay.",
        ),
        FileTemplate(
            "HTML page", "page.html", "$DIR/page.html",
            "Self-contained HTML page (preview in the WebView viewer).",
        ),
        FileTemplate(
            "Config file", "config.conf", "$DIR/config.conf",
            "Plain key=value configuration file.",
        ),
        FileTemplate(
            "Plain text", "notes.txt", "$DIR/notes.txt",
            "Empty-ish text note to start from.",
        ),
    )

    fun byFileName(name: String): FileTemplate? = all.firstOrNull { it.fileName == name }
}
