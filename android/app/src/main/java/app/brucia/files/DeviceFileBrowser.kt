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
 * - changes (`storage remove|mkdir <path>`): when answered (success or not) the directory last
 *   listed is listed again, as the screen refreshed before;
 * - capture listing (`storage list /BrucePCAP/handshakes`) shown as text on [captureList].
 * Superseded or stopped replies never update entries or trigger a relist.
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
    val captureList: StateFlow<String> = captures.status

    @Volatile private var dir: String? = null

    fun list(path: String) {
        if (path != dir) _entries.value = emptyList()
        dir = path
        listing.start("storage list $path", null) { _, out ->
            _entries.value = FileList.parse(out)
            null
        }
    }

    fun remove(path: String) = change("storage remove $path")

    fun mkdir(path: String) = change("storage mkdir $path")

    fun listCaptures() = captures.start("storage list $CAPTURE_DIR", null) { _, out -> out }

    /** Stop all lines; late replies are ignored and no relist follows. */
    fun stop() {
        listing.stop()
        changes.stop()
        captures.stop()
    }

    private fun change(cmd: String) = changes.start(cmd, null) { _, _ ->
        dir?.let { list(it) }
        null
    }

    companion object {
        const val CAPTURE_DIR = "/BrucePCAP/handshakes"
        /** Kept at the legacy 650 s: listing, removal and mkdir walk SD/LittleFS without a firmware bound. */
        const val TIMEOUT_MS = 650_000L
    }
}
