package app.brucia.wardrive

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Lifecycle of a single device command on the single-threaded companion link. */
enum class CmdStatus { RUNNING, DONE, FAILED }

/**
 * One entry in the command log shown by the Cmds viewer. [id] is a monotonic local id (not the
 * protocol frame id). RUNNING when dispatched; DONE/FAILED once the ACK/result arrives.
 */
data class CommandLogEntry(
    val id: Long,
    val text: String,
    val status: CmdStatus,
    val sentAtMs: Long,
    val doneAtMs: Long? = null,
    val result: String? = null,
    val ok: Boolean? = null,
)

/**
 * Bounded command history, independent of Android and transport/session ownership.
 * Callers decide which operations to log. IDs remain unique after clear/eviction, so a late
 * completion cannot create a row again or resolve a newer command. Atomic state updates allow
 * the UI and command workers to update different rows without losing each other's changes.
 */
class CommandHistory(private val nowMs: () -> Long = System::currentTimeMillis) {
    private val ids = AtomicLong(0L)
    private val _entries = MutableStateFlow<List<CommandLogEntry>>(emptyList())
    val entries: StateFlow<List<CommandLogEntry>> = _entries.asStateFlow()

    fun start(command: String): Long {
        val id = ids.incrementAndGet()
        val entry = CommandLogEntry(id, command, CmdStatus.RUNNING, nowMs())
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
        return id
    }

    fun complete(id: Long, ok: Boolean, result: String) {
        val now = nowMs()
        val short = result.trim().let {
            if (it.length > MAX_RESULT_LENGTH) it.take(MAX_RESULT_LENGTH) + "..." else it
        }
        _entries.update { list ->
            list.map { entry ->
                if (entry.id == id) {
                    entry.copy(
                        status = if (ok) CmdStatus.DONE else CmdStatus.FAILED,
                        doneAtMs = now,
                        result = short,
                        ok = ok,
                    )
                } else entry
            }
        }
    }

    fun clear() { _entries.value = emptyList() }

    private companion object {
        const val MAX_ENTRIES = 60
        const val MAX_RESULT_LENGTH = 600
    }
}
