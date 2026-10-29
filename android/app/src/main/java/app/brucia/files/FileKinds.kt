package app.brucia.files

/**
 * Pure helpers that classify a file by its name/extension so the file manager can decide what
 * actions to offer (edit, view as HTML, open a pcap externally) and which MIME type to hand to a
 * share / ACTION_VIEW intent. No Android dependencies, so it is unit-testable.
 */
object FileKinds {
    // Text-like files the in-app editor can open and edit.
    private val EDITABLE = setOf(
        "txt", "text", "log", "md", "markdown",
        "js", "json", "html", "htm", "css", "xml", "svg",
        "sub", "ducky", "dky", "bad", "badusb",
        "conf", "cfg", "ini", "cnf", "env",
        "csv", "tsv", "sh", "py", "yaml", "yml",
    )
    private val HTML = setOf("html", "htm")
    private val PCAP = setOf("pcap", "cap", "pcapng")

    /** Lower-cased extension without the dot, or "" if the name has none. */
    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** True when the file is small text the editor can safely open. */
    fun isEditable(name: String): Boolean = ext(name) in EDITABLE

    /** True for HTML files, which additionally get a rendered WebView preview. */
    fun isHtml(name: String): Boolean = ext(name) in HTML

    /** True for packet captures, which are typically opened in Wireshark / a pcap viewer. */
    fun isPcap(name: String): Boolean = ext(name) in PCAP

    /** Best-effort MIME type for a share / open-with intent; octet-stream when unknown. */
    fun mime(name: String): String = when (ext(name)) {
        "pcap", "cap", "pcapng" -> "application/vnd.tcpdump.pcap"
        "txt", "text", "log", "sub", "ducky", "dky", "bad", "badusb",
        "conf", "cfg", "ini", "cnf", "env" -> "text/plain"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "text/javascript"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "tsv" -> "text/tab-separated-values"
        "xml", "svg" -> "text/xml"
        "md", "markdown" -> "text/markdown"
        "sh" -> "application/x-sh"
        "py" -> "text/x-python"
        "yaml", "yml" -> "application/x-yaml"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "bin", "" -> "application/octet-stream"
        else -> "application/octet-stream"
    }
}
