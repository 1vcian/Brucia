package app.brucia

import app.brucia.wifi.CaptureDescriptor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32

class CaptureDescriptorTest {
    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value
    private fun wrap(desc: JSONObject) = JSONObject().put("desc", desc)
    private fun valid(path: String = "/BrucePCAP/handshakes/HS_AABBCCDDEEFF.pcap") = JSONObject()
        .put("v", 1).put("path", path).put("size", 3).put("crc32", crc(byteArrayOf(1, 2, 3)))
        .put("complete", true).put("stop", "complete").put("time_src", "device_uptime_ms")

    @Test fun parsesValidDescriptorWithOptionalFields() {
        val d = CaptureDescriptor.fromJson(wrap(valid().put("frames", 4).put("handshake", true).put("uptime_ms", 1234)))
        assertNotNull(d); d!!
        assertEquals(1, d.version)
        assertEquals("HS_AABBCCDDEEFF.pcap", d.fileName)
        assertEquals(3L, d.size)
        assertEquals(4L, d.frames)
        assertEquals(true, d.handshake)
        assertTrue(d.complete)
        assertEquals("device_uptime_ms", d.timeSource)
        assertEquals(1234L, d.uptimeMs)
    }

    @Test fun missingOrUnsupportedVersionOrNoDescIsNull() {
        assertNull(CaptureDescriptor.fromJson(null))
        assertNull(CaptureDescriptor.fromJson(JSONObject().put("ok", true)))     // no desc object
        assertNull(CaptureDescriptor.fromJson(wrap(valid().put("v", 2))))        // unknown version
        assertNull(CaptureDescriptor.fromJson(wrap(valid().put("v", 0))))        // version 0
    }

    @Test fun rejectsBadPathSizeOrCrc() {
        assertNull(CaptureDescriptor.fromJson(wrap(valid("relative/x.pcap"))))        // not absolute
        assertNull(CaptureDescriptor.fromJson(wrap(valid("/BrucePCAP/../etc/x"))))    // traversal
        assertNull(CaptureDescriptor.fromJson(wrap(valid("/BrucePCAP/dir/"))))        // trailing slash
        assertNull(CaptureDescriptor.fromJson(wrap(valid().put("size", -1))))         // negative size
        assertNull(CaptureDescriptor.fromJson(wrap(valid().put("size", 17L * 1024 * 1024))) ) // over 16 MiB
        assertNull(CaptureDescriptor.fromJson(wrap(valid().put("crc32", 0x1_0000_0000L)))) // over u32
        assertNull(CaptureDescriptor.fromJson(wrap(valid().put("stop", "")))) // empty stop
    }

    @Test fun verifyAcceptsMatchAndRejectsSizeOrCrcMismatch() {
        val bytes = byteArrayOf(10, 20, 30)
        val d = CaptureDescriptor.fromJson(wrap(valid().put("size", bytes.size).put("crc32", crc(bytes))))!!
        assertNull(d.verify(bytes))
        assertTrue(d.verify(byteArrayOf(10, 20)).orEmpty().contains("size"))
        assertTrue(d.verify(byteArrayOf(10, 20, 99)).orEmpty().contains("CRC"))
    }

    @Test fun partialReasonMapsStopCodes() {
        fun reason(stop: String, pmkid: Boolean) =
            CaptureDescriptor.fromJson(wrap(valid().put("complete", false).put("stop", stop)))!!.partialReason(pmkid)
        assertTrue(reason("timeout", false).contains("time ran out"))
        assertTrue(reason("aborted", false).contains("stopped on the device"))
        assertTrue(reason("no_capture", true).contains("PMKID"))
        assertTrue(reason("not_written", false).contains("stale"))  // stale-file case is not imported
    }

    @Test fun parsesAppendedAndPriorSize() {
        val plain = CaptureDescriptor.fromJson(wrap(valid()))!!
        assertFalse(plain.appended); assertNull(plain.priorSize)              // defaults when absent
        val app = CaptureDescriptor.fromJson(wrap(valid().put("appended", true).put("prior_size", 512)))!!
        assertTrue(app.appended); assertEquals(512L, app.priorSize)
    }
}
