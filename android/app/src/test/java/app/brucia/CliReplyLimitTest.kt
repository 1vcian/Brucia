package app.brucia

import app.brucia.files.FileEntry
import app.brucia.files.FileList
import app.brucia.protocol.CliReplyLimit
import app.brucia.protocol.Kind
import app.brucia.protocol.routeCommandReply
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** A routed CLI reply cut at the companion's 3500-byte capture limit says so wherever it is shown. */
class CliReplyLimitTest {
    private fun route(kind: Int, d: JSONObject): Pair<Boolean, String> {
        var got: Pair<Boolean, String>? = null
        val pending = mutableMapOf<Int, (Boolean, String) -> Unit>(7 to { ok, out -> got = ok to out })
        val shown = routeCommandReply(JSONObject().put("v", 0).put("t", kind).put("id", 7).put("d", d), pending, mutableMapOf())
        assertEquals(got!!.second, shown)
        return got!!
    }

    @Test fun anAckAtTheCaptureLimitIsMarked() {
        val cut = "x".repeat(3500)
        assertEquals(true to cut + "\n" + CliReplyLimit.MARK, route(Kind.ACK, JSONObject().put("ok", true).put("out", cut)))
        // A multi-byte character split by the cut decodes to U+FFFD (3 bytes): still at the limit.
        val split = "y".repeat(3499) + "�"
        assertTrue(route(Kind.ACK, JSONObject().put("ok", false).put("out", split)).second.endsWith(CliReplyLimit.MARK))
    }

    @Test fun shorterRepliesAndErrorsAreUnchanged() {
        val short = "a".repeat(3000)
        assertEquals(true to short, route(Kind.ACK, JSONObject().put("ok", true).put("out", short)))
        assertEquals(false to "x".repeat(3500), route(Kind.ERR, JSONObject().put("msg", "x".repeat(3500))))
        assertEquals("", CliReplyLimit.mark(""))
    }

    @Test fun parsersGetTheExactDeviceTextBack() {
        val lines = (0 until 400).joinToString("") { "f%03d.bin\t%d\n".format(it, it) }
        val cut = String(lines.toByteArray().copyOf(3500))
        val marked = CliReplyLimit.mark(cut)
        assertEquals(cut to true, CliReplyLimit.unmark(marked))
        val listing = FileList.parseListing(marked)
        assertTrue(listing.truncated)
        // The cut last line (marked text would otherwise make it look complete) is still dropped.
        val partial = cut.substring(cut.lastIndexOf('\n') + 1)
        assertTrue(partial.isNotEmpty())
        assertEquals(cut.count { it == '\n' }, listing.entries.size) // complete lines only
        assertTrue(listing.entries.none { it.name == partial.substringBefore('\t') })
        assertEquals(FileEntry("f000.bin", false, 0), listing.entries.first())
    }
}
