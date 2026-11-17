package app.brucia

import app.brucia.wardrive.WifiTargetImportService
import app.brucia.wardrive.WifiTargetStore
import app.brucia.wifi.CapturedTarget
import app.brucia.wifi.Handshake
import app.brucia.wifi.Hc22000
import app.brucia.wifi.PcapDissector
import app.brucia.wifi.PcapWriter
import app.brucia.wifi.PmkidTarget
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

/** Runs real document reading, parsers and shared target store; no Android or mocked parser. */
class WifiTargetImportServiceTest {
    private val ap = byteArrayOf(0, 17, 34, 51, 68, 85)
    private val sta = byteArrayOf(102, 119, 43, 17, 34, 51)
    private fun pm(ssid: String) = PmkidTarget(ByteArray(16), ap, sta, ssid)
    private fun paste(service: WifiTargetImportService, ssid: String) = service.importText(Hc22000.export(pm(ssid)))

    private class Document(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }

    /** Synthetic beacon + M1/M2 with a zero MIC: parser fixture, no real capture or cracking. */
    private fun pcap(ssid: String): ByteArray {
        val name = ssid.toByteArray()
        val beacon = ByteArray(36).apply {
            this[0] = 0x80.toByte()
            ap.copyInto(this, 10); ap.copyInto(this, 16)
        } + byteArrayOf(0, name.size.toByte()) + name
        fun frame(fromAp: Boolean) = ByteArray(32 + 99).apply {
            this[0] = 8; this[1] = if (fromAp) 2 else 1
            (if (fromAp) sta else ap).copyInto(this, 4)
            (if (fromAp) ap else sta).copyInto(this, 10)
            ap.copyInto(this, 16)
            byteArrayOf(0xaa.toByte(), 0xaa.toByte(), 3, 0, 0, 0, 0x88.toByte(), 0x8e.toByte()).copyInto(this, 24)
            this[32] = 2; this[33] = 3; this[35] = 95; this[36] = 2
            this[37] = if (fromAp) 0 else 1
            this[38] = if (fromAp) 0x8a.toByte() else 0x0a
            ByteArray(32) { (if (fromAp) it + 1 else 32 - it).toByte() }.copyInto(this, 49)
        }
        return PcapWriter.globalHeader() + listOf(beacon, frame(true), frame(false))
            .fold(ByteArray(0)) { bytes, frame -> bytes + PcapWriter.packetRecord(0, frame) }
    }

    @Test fun textFormatsKeepExistingLabelsAndReplaceTheSelectedType() = runVirtual {
        val store = WifiTargetStore(); val service = WifiTargetImportService(this, store, clock)
        service.importText("00".repeat(16) + "*001122334455*66772b112233*4e6574")
        assertEquals("Net (PMKID)", store.info.value)
        assertEquals("Net", store.current()?.pmkid?.ssid)
        paste(service, "second")
        assertEquals("second (PMKID) [22000]", store.info.value)
        val hs = Handshake("handshake", ap, sta, ByteArray(32), ByteArray(32),
            ByteArray(99).apply { this[6] = 2 }, ByteArray(16), 2)
        service.importText(Hc22000.export(hs))
        assertEquals("handshake (WPA2) [22000]", store.info.value)
        assertNull(store.current()?.pmkid)
        assertEquals(Hc22000.export(hs), store.export())
        for ((line, label) in listOf("bad" to "invalid PMKID line", "WPA*02*bad" to "invalid 22000 line")) {
            paste(service, "previous")
            service.importText(line)
            assertNull(store.current()); assertNull(store.export()); assertEquals(label, store.info.value)
        }
    }

    @Test fun documentIsReadOffCallerAndClosedBeforePublishingTheParsedTarget() = runVirtual {
        val store = WifiTargetStore(); val service = WifiTargetImportService(this, store, clock)
        paste(service, "previous")
        val input = Document(pcap("from-file")); var opened = false
        val job = service.importPcap { opened = true; input }
        assertFalse("provider IO must not run on the initiating call", opened)
        assertEquals("previous", store.current()?.pmkid?.ssid)
        job.join()
        assertTrue(input.closed)
        assertEquals("from-file (WPA2)", store.info.value)
        assertEquals("from-file", store.current()?.handshake?.ssid)
        assertNull(store.current()?.pmkid)
        assertTrue(store.export()!!.startsWith("WPA*02*"))
    }

    @Test fun emptyMalformedAndFailedDocumentsClearThePreviousTarget() = runVirtual {
        val store = WifiTargetStore(); val service = WifiTargetImportService(this, store, clock)
        for (bytes in listOf(ByteArray(0), byteArrayOf(1, 2, 3), PcapWriter.globalHeader())) {
            paste(service, "old")
            val input = Document(bytes)
            service.importPcap { input }.join()
            assertTrue(input.closed); assertNull(store.current()); assertNull(store.export())
            assertEquals("no handshake found in file", store.info.value)
        }
        for (open in listOf<() -> InputStream?>({ null }, { throw IOException("provider failed") })) {
            paste(service, "old")
            service.importPcap(open).join()
            assertNull(store.current()); assertNull(store.export()); assertEquals("read error", store.info.value)
        }
    }

    @Test fun readFailureClosesTheDocumentAndDoesNotLeaveAStaleTarget() = runVirtual {
        val store = WifiTargetStore(); val service = WifiTargetImportService(this, store, clock)
        paste(service, "old")
        var closed = false
        service.importPcap { object : InputStream() {
            override fun read(): Int = throw IOException("read failed")
            override fun close() { closed = true }
        } }.join()
        assertTrue(closed); assertNull(store.export()); assertEquals("read error", store.info.value)
    }

    @Test fun oversizedStreamStopsAtTheReal32MiBLimitAndCloses() = runVirtual {
        val store = WifiTargetStore(); val service = WifiTargetImportService(this, store, clock)
        paste(service, "old")
        var consumed = 0; var closed = false
        service.importPcap { object : InputStream() {
            override fun read(): Int = error("bulk read expected")
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                consumed += len
                return len // infinite zero data; allocation must still be bounded
            }
            override fun close() { closed = true }
        } }.join()
        assertTrue(closed)
        assertEquals(PcapDissector.MAX_CAPTURE_BYTES + 64 * 1024, consumed)
        assertNull(store.export()); assertEquals("file too large (max 32 MiB)", store.info.value)
    }

    @Test fun queuedDocumentCannotOverwriteTextSelectedBeforeItsIoStarts() = runVirtual {
        val store = WifiTargetStore(); val service = WifiTargetImportService(this, store, clock)
        val job = service.importPcap { Document(pcap("old-file")) }
        paste(service, "new-text")
        job.join()
        assertEquals("new-text (PMKID) [22000]", store.info.value)
        assertEquals("new-text", store.current()?.pmkid?.ssid)
    }

    @Test fun failedOlderDocumentCannotClearANewerDevicePull() = runVirtual {
        val store = WifiTargetStore(); val service = WifiTargetImportService(this, store, clock)
        val job = service.importPcap { throw IOException("old failure") }
        assertTrue(store.beginPull("/new.pcap")(WifiTargetStore.Target(pmkid = pm("device")), "device [pulled]"))
        job.join()
        assertEquals("device [pulled]", store.info.value)
        assertEquals("device", store.current()?.pmkid?.ssid)
    }

    @Test fun documentReservationSupersedesAnOlderCaptureAndIgnoresUnrelatedPulls() = runVirtual {
        val store = WifiTargetStore(); val service = WifiTargetImportService(this, store, clock)
        val capture = store.beginCapture()
        val job = service.importPcap { Document(pcap("selected")) }
        assertFalse(capture(CapturedTarget(null, pm("stale"), "/old.pcap", ByteArray(0))))
        assertFalse(store.beginPull("/notes.txt")(null, "downloaded notes"))
        job.join()
        assertEquals("selected", store.current()?.handshake?.ssid)
    }

    @Test fun scopeCancellationDuringReadClosesWithoutPublishingAndAllowsNewOwner() = runVirtual {
        val store = WifiTargetStore()
        val owner = Job(coroutineContext[Job])
        val service = WifiTargetImportService(CoroutineScope(coroutineContext + owner), store, clock)
        paste(service, "previous")
        var closed = false
        val job = service.importPcap { object : ByteArrayInputStream(pcap("cancelled")) {
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                owner.cancel()
                return super.read(buffer, off, len)
            }
            override fun close() { closed = true; super.close() }
        } }
        job.join()
        assertTrue(job.isCancelled); assertTrue(closed)
        assertEquals("previous (PMKID) [22000]", store.info.value)
        val retry = WifiTargetImportService(this, store, clock)
        retry.importPcap { Document(pcap("retry")) }.join()
        assertEquals("retry", store.current()?.handshake?.ssid)
    }
}
