package app.brucia

import app.brucia.remote.DrawOp
import app.brucia.remote.TftProtocol
import app.brucia.remote.rgb565ToArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TftProtocolTest {

    private fun packet(fn: Int, args: List<Int>, tail: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteArrayOutputStream()
        for (a in args) { body.write((a shr 8) and 0xFF); body.write(a and 0xFF) }
        body.write(tail)
        val size = 3 + body.size()
        val out = ByteArrayOutputStream()
        out.write(0xAA); out.write(size); out.write(fn); out.write(body.toByteArray())
        return out.toByteArray()
    }

    @Test fun rgb565Conversions() {
        assertEquals(0xFFFF0000.toInt(), rgb565ToArgb(0xF800)) // red
        assertEquals(0xFF00FF00.toInt(), rgb565ToArgb(0x07E0)) // green (6-bit all set)
        assertEquals(0xFFFFFFFF.toInt(), rgb565ToArgb(0xFFFF)) // white
        assertEquals(0xFF000000.toInt(), rgb565ToArgb(0x0000)) // black
    }

    @Test fun parsesFillRectAndFillScreen() {
        val stream = packet(0, listOf(0xF800)) + // FILLSCREEN red
            packet(2, listOf(10, 20, 30, 40, 0xFFFF)) // FILLRECT white
        val (ops, consumed) = TftProtocol.parse(stream)
        assertEquals(stream.size, consumed)
        assertEquals(2, ops.size)
        assertTrue(ops[0] is DrawOp.FillScreen)
        val r = ops[1] as DrawOp.Rect
        assertEquals(10, r.x); assertEquals(20, r.y); assertEquals(30, r.w); assertEquals(40, r.h)
        assertTrue(r.fill)
    }

    @Test fun parsesStringPacket() {
        val text = "Brucia"
        val stream = packet(16, listOf(5, 7, 1, 0xFFFF, 0x0000), text.toByteArray(Charsets.UTF_8))
        val (ops, _) = TftProtocol.parse(stream)
        val t = ops.single() as DrawOp.Text
        assertEquals(5, t.x); assertEquals(7, t.y)
        assertEquals("Brucia", t.text)
    }

    @Test fun waitsForIncompletePacket() {
        val full = packet(2, listOf(1, 2, 3, 4, 0xF800))
        val partial = full.copyOf(full.size - 3) // cut mid-packet
        val (ops, consumed) = TftProtocol.parse(partial)
        assertEquals(0, ops.size)       // nothing complete yet
        assertEquals(0, consumed)       // consume nothing; caller keeps the tail
    }

    @Test fun resyncsPastGarbage() {
        val stream = byteArrayOf(0x00, 0x13, 0x37) + packet(6, listOf(50, 60, 10, 0x07E0)) // circle
        val (ops, _) = TftProtocol.parse(stream)
        val c = ops.single() as DrawOp.Circle
        assertEquals(50, c.x); assertEquals(60, c.y); assertEquals(10, c.r); assertTrue(c.fill)
    }

    /**
     * Argument order of every op as include/tftLogger.h checkAndLog writes it (uint16 big-endian
     * per argument, header AA size fn). A swapped index here mirrors shapes in the wrong place.
     */
    @Test fun everyDrawOpFollowsTheFirmwareArgumentOrder() {
        val c = 0xF800; val red = rgb565ToArgb(c)
        val cases = listOf(
            packet(1, listOf(1, 2, 3, 4, c)) to DrawOp.Rect(1, 2, 3, 4, red, false),
            packet(3, listOf(1, 2, 3, 4, 5, c)) to DrawOp.RoundRect(1, 2, 3, 4, 5, red, false),
            packet(4, listOf(1, 2, 3, 4, 5, c)) to DrawOp.RoundRect(1, 2, 3, 4, 5, red, true),
            packet(5, listOf(1, 2, 3, c)) to DrawOp.Circle(1, 2, 3, red, false),
            packet(6, listOf(1, 2, 3, c)) to DrawOp.Circle(1, 2, 3, red, true),
            packet(7, listOf(1, 2, 3, 4, 5, 6, c)) to DrawOp.Triangle(1, 2, 3, 4, 5, 6, red, false),
            packet(8, listOf(1, 2, 3, 4, 5, 6, c)) to DrawOp.Triangle(1, 2, 3, 4, 5, 6, red, true),
            packet(9, listOf(1, 2, 3, 4, c)) to DrawOp.Ellipse(1, 2, 3, 4, red, false),
            packet(10, listOf(1, 2, 3, 4, c)) to DrawOp.Ellipse(1, 2, 3, 4, red, true),
            packet(11, listOf(1, 2, 3, 4, c)) to DrawOp.Line(1, 2, 3, 4, red),
            // DRAWARC: x, y, r, ir, start, end, fg, bg (8 args; bg is not drawn).
            packet(12, listOf(1, 2, 3, 4, 90, 270, c, 0x001F)) to DrawOp.Arc(1, 2, 3, 4, 90, 270, red),
            // DRAWWIDELINE: ax, ay, bx, by, width, fg, bg.
            packet(13, listOf(1, 2, 3, 4, 5, c, 0x001F)) to DrawOp.WideLine(1, 2, 3, 4, 5, red),
            packet(19, listOf(7, 8, c)) to DrawOp.Pixel(7, 8, red),
            packet(20, listOf(1, 2, 30, c)) to DrawOp.FastLine(1, 2, 30, red, true),
            packet(21, listOf(1, 2, 30, c)) to DrawOp.FastLine(1, 2, 30, red, false),
            // DRAWIMAGE: x, y, center, Ms, fs (1 byte), slot (1 byte), path bytes.
            packet(18, listOf(5, 6, 1, 0), byteArrayOf(2, 0) + "/img.png".toByteArray()) to DrawOp.Image(5, 6),
            packet(42, listOf(1)) to DrawOp.Unknown(42),
        )
        val (ops, used) = TftProtocol.parse(cases.map { it.first }.reduce(ByteArray::plus))
        assertEquals(cases.sumOf { it.first.size }, used)
        assertEquals(cases.map { it.second }, ops)
    }

    @Test fun screenInfoCarriesSizeAndOneByteRotation() {
        // getTftInfo(): header, width u16, height u16, rotation as a single byte (packet size 8).
        val pkt = byteArrayOf(0xAA.toByte(), 8, 99, 0x01, 0x40, 0x00, 0xAA.toByte(), 3)
        val (ops, used) = TftProtocol.parse(pkt)
        assertEquals(8, used)
        assertEquals(listOf(DrawOp.ScreenInfo(320, 170, 3)), ops)
    }

    @Test fun shortArgumentsReadAsZeroAndBadSizesAreSkipped() {
        // A packet shorter than its op's argument list leaves the missing args 0, not stale bytes.
        val (ops, _) = TftProtocol.parse(packet(2, listOf(9, 9)) + packet(16, listOf(1, 2, 1, 0xFFFF, 0)))
        assertEquals(DrawOp.Rect(9, 9, 0, 0, rgb565ToArgb(0), true), ops[0])
        assertEquals("", (ops[1] as DrawOp.Text).text)
        // Size byte below the 3-byte header is invalid: resync past it to the next packet.
        val (resynced, used) = TftProtocol.parse(byteArrayOf(0xAA.toByte(), 2, 0) + packet(0, listOf(0)))
        assertEquals(listOf<DrawOp>(DrawOp.FillScreen(rgb565ToArgb(0))), resynced)
        assertEquals(8, used)
        // Honors an explicit length shorter than the buffer.
        val two = packet(0, listOf(0)) + packet(0, listOf(0))
        assertEquals(5, TftProtocol.parse(two, length = 7).second)
    }
}
