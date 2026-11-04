package app.brucia

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Observes the app from outside through shell dumps (no production hooks): registered broadcast
 * receivers, wake locks and the ConnectionService record.
 */
internal object DeviceState {
    private const val PROCESS = "app.brucia"
    private const val SERVICE = "app.brucia/.service.ConnectionService"

    fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    /** Filters for [action] currently registered at runtime by this app's process. */
    fun registeredReceivers(action: String): Int {
        val dump = shell("dumpsys activity broadcasts")
        val section = dump.substringAfter("Registered Receivers:", "").substringBefore("Receiver Resolver Table:")
        var count = 0
        var ours = false
        for (line in section.lines()) {
            val t = line.trim()
            if (t.startsWith("* ReceiverList{")) ours = t.contains(" $PROCESS/")
            else if (ours && t == "Action: \"$action\"") count++
        }
        return count
    }

    /** True while a wake lock with [tag] is held (current list only, not the wake lock history log). */
    fun wakeLockHeld(tag: String): Boolean {
        val lines = shell("dumpsys power").lines()
        val start = lines.indexOfFirst { it.trim().startsWith("Wake Locks: size=") }
        if (start < 0) return false
        return lines.drop(start + 1).takeWhile { it.isNotBlank() }.any { it.contains("'$tag'") }
    }

    /** ConnectionService record dump, or "" when the service is not running. */
    fun serviceRecord(): String =
        shell("dumpsys activity services $SERVICE").split("* ServiceRecord{")
            .drop(1).firstOrNull { it.lineSequence().first().contains(SERVICE) }.orEmpty()

    fun lastStartId(record: String): Int =
        Regex("lastStartId=(\\d+)").find(record)?.groupValues?.get(1)?.toInt() ?: error("no lastStartId in: $record")

    fun awaitStartsHandled() = waitUntil("starts handled", detail = { serviceRecord() }) { startsHandled() }

    /** True once every start sent so far has been delivered and returned (or the service is gone). */
    fun startsHandled(): Boolean {
        val r = serviceRecord()
        // ServiceRecord.dump prints these only while non-empty / non-zero.
        val executing = Regex("executeNesting=([1-9])").containsMatchIn(r)
        return r.isEmpty() || (!r.contains("Pending Starts:") && !r.contains("Delivered Starts:") && !executing)
    }

    fun waitUntil(what: String, timeoutMs: Long = 15_000, detail: () -> String = { "" }, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(100)
        }
        if (!cond()) throw AssertionError("timed out after ${timeoutMs}ms waiting for: $what ${detail()}")
    }
}
