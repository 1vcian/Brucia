package app.brucia

import app.brucia.wardrive.RfCaptureCsv
import app.brucia.wardrive.RfCaptureRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RfCaptureCsvTest {
    @Test fun buildsHeaderAndRows() {
        val rows = listOf(
            RfCaptureRow(1000L, 433_920_000L, intArrayOf(312, 480, 310)),
            RfCaptureRow(2000L, 433_920_000L, intArrayOf(500, 520)),
        )
        val csv = RfCaptureCsv.build(rows)
        val lines = csv.trim().split("\n")
        assertEquals("time_ms,freq_hz,num_edges,timings_us", lines[0])
        assertEquals(3, lines.size)
        assertEquals("1000,433920000,3,\"312 480 310\"", lines[1])
        assertEquals("2000,433920000,2,\"500 520\"", lines[2])
    }

    @Test fun emptyIsHeaderOnly() {
        val csv = RfCaptureCsv.build(emptyList())
        assertTrue(csv.startsWith("time_ms,freq_hz,num_edges,timings_us"))
        assertEquals(1, csv.trim().split("\n").size)
    }
}
