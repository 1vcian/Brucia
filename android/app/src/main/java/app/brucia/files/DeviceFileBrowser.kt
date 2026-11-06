package app.brucia.files

import app.brucia.session.CommandRunner
import app.brucia.session.DeviceOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Device file manager over the storage CLI, three independent lines:
 * - listing (`storage list <dir>`): owns [entries]; [loading] is true while it waits; a newer
 *   listing supersedes a pending one and switching directory clears stale entries at once;
 *   [listStatus] says when the device's reply limit cut the listing or the path cannot be sent;
 * - changes (`storage remove|mkdir <path>`): the device's answer shows on [changeStatus]; when
 *   answered (success or not) the directory last listed is listed again, as the screen refreshed before;
 * - capture listing (`storage list /BrucePCAP/handshakes`) shown as text on [captureList].
 * Superseded or stopped replies never update entries or trigger a relist.
 *
 * Paths go to Bruce's SimpleCLI, which splits words at spaces and lines at ";;": see [cliPath].
 */
class DeviceFileBrowser(
    scope: CoroutineScope,
    runner: CommandRunner,
    isConnected: () -> Boolean,
) {
    private val listing = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    private val changes = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)
    private val captures = DeviceOperation(scope, runner, isConnected, TIMEOUT_MS)

    private val _entries = MutableStateFlow<List<FileEntry>>(emptyList())
    val entries: StateFlow<List<FileEntry>> = _entries.asStateFlow()
    val loading: StateFlow<Boolean> = listing.busy
    /** Empty, or why the listing is incomplete / could not be requested. */
    val listStatus: StateFlow<String> = listing.status
    /** The device's answer to the last remove / mkdir (or why it was not sent). */
    val changeStatus: StateFlow<String> = changes.status
    val captureList: StateFlow<String> = captures.status

    @Volatile private var dir: String? = null

    fun list(path: String) {
        if (path != dir) _entries.value = emptyList()
        dir = path
        val cmd = listCommand(path)
        listing.start(cmd ?: "", "", { if (cmd == null) unsupported(path) else null }) { ok, out ->
            val parsed = FileList.parseListing(out)
            _entries.value = parsed.entries
            if (ok && parsed.truncated) "The device's reply limit (${FileList.REPLY_LIMIT_BYTES} bytes) cut this listing: " +
                "${parsed.entries.size} entries shown, more may exist." else ""
        }
    }

    fun remove(path: String) = change(cliPath(path)?.let { "storage remove $it" }, path)

    fun mkdir(path: String) = change(cliPath(path)?.let { "storage mkdir $it" }, path)

    fun listCaptures() = captures.start("storage list $CAPTURE_DIR", null) { _, out -> out }

    /** Stop all lines; late replies are ignored and no relist follows. */
    fun stop() {
        listing.stop()
        changes.stop()
        captures.stop()
    }

    private fun change(cmd: String?, path: String) =
        changes.start(cmd ?: "", null, { if (cmd == null) unsupported(path) else null }) { ok, out ->
            dir?.let { list(it) }
            out.trim().ifBlank { if (ok) "Done." else "The device reported a failure." }
        }

    companion object {
        const val CAPTURE_DIR = "/BrucePCAP/handshakes"
        /** Kept at the legacy 650 s: listing, removal and mkdir walk SD/LittleFS without a firmware bound. */
        const val TIMEOUT_MS = 650_000L

        fun listCommand(path: String): String? = cliPath(path)?.let { "storage list $it" }

        /**
         * [path] as one SimpleCLI argument (src/c/parser.c parse_lines / parse_words, arg.c
         * arg_set_value), or null when it cannot be sent safely. Unquoted, a space splits it into
         * extra words ("unknown argument": nothing listed or removed) and ";;" starts a second CLI
         * command; a leading '-' reads as a flag. Those are double-quoted, which SimpleCLI strips.
         * '"' and '\' are refused: parse_lines treats a quote after an escaped backslash as escaped
         * while parse_words does not, so no escaping is reliable. Control characters and leading or
         * trailing spaces (Bruce trims the value) are refused too.
         */
        fun cliPath(path: String): String? {
            if (path.isEmpty() || path != path.trim() ||
                path.any { it < ' ' || it == '\u007f' || it == '"' || it == '\\' }) return null
            return if (path.startsWith('-') || path.any { it == ' ' || it == ';' }) "\"$path\"" else path
        }

        private fun unsupported(path: String) =
            "\"$path\" cannot be sent to the device CLI (quote, backslash, control character or edge space)."
    }
}
