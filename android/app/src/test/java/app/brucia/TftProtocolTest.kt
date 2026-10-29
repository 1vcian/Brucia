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
}
