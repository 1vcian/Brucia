package app.brucia.remote

/**
 * Parser for Bruce's TFT mirror stream (`display start`, src/core/tftLogger). Each draw
 * operation is a binary packet: 0xAA [size] [fn] [uint16 big-endian args...]. String ops
 * carry x,y,textSize,fgColor,bgColor then the UTF-8 string bytes. Colors are RGB565.
 *
 * This file is pure logic (no Android graphics) so it is unit-testable; TftRenderer
 * applies the ops to a bitmap.
 */

const val TFT_HEADER = 0xAA

// tftFuncs enum (keep in sync with include/tftLogger.h - DO NOT reorder).
object TftFn {
    const val FILLSCREEN = 0
    const val DRAWRECT = 1
    const val FILLRECT = 2
    const val DRAWROUNDRECT = 3
    const val FILLROUNDRECT = 4
    const val DRAWCIRCLE = 5
    const val FILLCIRCLE = 6
    const val DRAWTRIANGLE = 7
    const val FILLTRIANGLE = 8
    const val DRAWELLIPSE = 9
    const val FILLELLIPSE = 10
    const val DRAWLINE = 11
    const val DRAWARC = 12
    const val DRAWWIDELINE = 13
    const val DRAWCENTRESTRING = 14
    const val DRAWRIGHTSTRING = 15
    const val DRAWSTRING = 16
    const val PRINT = 17
    const val DRAWIMAGE = 18
    const val DRAWPIXEL = 19
    const val DRAWFASTVLINE = 20
    const val DRAWFASTHLINE = 21
    const val SCREEN_INFO = 99
}

sealed interface DrawOp {
    data class FillScreen(val color: Int) : DrawOp
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int, val color: Int, val fill: Boolean) : DrawOp
    data class RoundRect(val x: Int, val y: Int, val w: Int, val h: Int, val r: Int, val color: Int, val fill: Boolean) : DrawOp
    data class Circle(val x: Int, val y: Int, val r: Int, val color: Int, val fill: Boolean) : DrawOp
    data class Triangle(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val x3: Int, val y3: Int, val color: Int, val fill: Boolean) : DrawOp
    data class Line(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val color: Int) : DrawOp
    data class FastLine(val x: Int, val y: Int, val len: Int, val color: Int, val vertical: Boolean) : DrawOp
    data class Pixel(val x: Int, val y: Int, val color: Int) : DrawOp
    data class Ellipse(val x: Int, val y: Int, val rx: Int, val ry: Int, val color: Int, val fill: Boolean) : DrawOp
    data class Image(val x: Int, val y: Int) : DrawOp
    data class WideLine(val ax: Int, val ay: Int, val bx: Int, val by: Int, val width: Int, val color: Int) : DrawOp
    data class Arc(val x: Int, val y: Int, val rOuter: Int, val rInner: Int, val startAngle: Int, val endAngle: Int, val color: Int) : DrawOp
    data class Text(val x: Int, val y: Int, val size: Int, val fg: Int, val bg: Int, val text: String, val align: Int) : DrawOp
    data class ScreenInfo(val width: Int, val height: Int, val rotation: Int) : DrawOp
    data class Unknown(val fn: Int) : DrawOp
}

/** Convert an RGB565 value to 0xAARRGGBB (opaque). */
fun rgb565ToArgb(c: Int): Int {
    val r = (c shr 11 and 0x1F)
    val g = (c shr 5 and 0x3F)
    val b = (c and 0x1F)
    val r8 = (r * 255 + 15) / 31
    val g8 = (g * 255 + 31) / 63
    val b8 = (b * 255 + 15) / 31
    return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
}

object TftProtocol {
    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    /**
     * Parse as many complete packets as possible from [buf] starting at 0. Returns the
     * ops and the number of bytes consumed (the caller keeps the remaining tail).
     */
    fun parse(buf: ByteArray, length: Int = buf.size): Pair<List<DrawOp>, Int> {
        val ops = ArrayList<DrawOp>()
        var i = 0
        while (i < length) {
            // Resync to a header byte.
            if (buf[i].toInt() and 0xFF != TFT_HEADER) { i++; continue }
            if (i + 2 >= length) break // need header+size+fn
            val size = buf[i + 1].toInt() and 0xFF
            if (size < 3) { i++; continue } // invalid, skip
            if (i + size > length) break // incomplete packet, wait for more
            val fn = buf[i + 2].toInt() and 0xFF
            val a = IntArray(8) { k -> if (i + 3 + k * 2 + 1 < i + size) u16(buf, i + 3 + k * 2) else 0 }
            ops.add(decode(fn, a, buf, i, size))
            i += size
        }
        return ops to i
    }

    private fun decode(fn: Int, a: IntArray, buf: ByteArray, off: Int, size: Int): DrawOp = when (fn) {
        TftFn.FILLSCREEN -> DrawOp.FillScreen(rgb565ToArgb(a[0]))
        TftFn.DRAWRECT -> DrawOp.Rect(a[0], a[1], a[2], a[3], rgb565ToArgb(a[4]), false)
        TftFn.FILLRECT -> DrawOp.Rect(a[0], a[1], a[2], a[3], rgb565ToArgb(a[4]), true)
        TftFn.DRAWROUNDRECT -> DrawOp.RoundRect(a[0], a[1], a[2], a[3], a[4], rgb565ToArgb(a[5]), false)
        TftFn.FILLROUNDRECT -> DrawOp.RoundRect(a[0], a[1], a[2], a[3], a[4], rgb565ToArgb(a[5]), true)
        TftFn.DRAWCIRCLE -> DrawOp.Circle(a[0], a[1], a[2], rgb565ToArgb(a[3]), false)
        TftFn.FILLCIRCLE -> DrawOp.Circle(a[0], a[1], a[2], rgb565ToArgb(a[3]), true)
        TftFn.DRAWTRIANGLE -> DrawOp.Triangle(a[0], a[1], a[2], a[3], a[4], a[5], rgb565ToArgb(a[6]), false)
        TftFn.FILLTRIANGLE -> DrawOp.Triangle(a[0], a[1], a[2], a[3], a[4], a[5], rgb565ToArgb(a[6]), true)
        TftFn.DRAWLINE -> DrawOp.Line(a[0], a[1], a[2], a[3], rgb565ToArgb(a[4]))
        TftFn.DRAWWIDELINE -> DrawOp.WideLine(a[0], a[1], a[2], a[3], a[4], rgb565ToArgb(a[5]))
        TftFn.DRAWARC -> DrawOp.Arc(a[0], a[1], a[2], a[3], a[4], a[5], rgb565ToArgb(a[6]))
        TftFn.DRAWFASTVLINE -> DrawOp.FastLine(a[0], a[1], a[2], rgb565ToArgb(a[3]), true)
        TftFn.DRAWFASTHLINE -> DrawOp.FastLine(a[0], a[1], a[2], rgb565ToArgb(a[3]), false)
        TftFn.DRAWPIXEL -> DrawOp.Pixel(a[0], a[1], rgb565ToArgb(a[2]))
        TftFn.DRAWELLIPSE -> DrawOp.Ellipse(a[0], a[1], a[2], a[3], rgb565ToArgb(a[4]), false)
        TftFn.FILLELLIPSE -> DrawOp.Ellipse(a[0], a[1], a[2], a[3], rgb565ToArgb(a[4]), true)
        TftFn.DRAWIMAGE -> DrawOp.Image(a[0], a[1])
        // getTftInfo() sends rotation as one byte after width/height (packet size 8), not a uint16.
        TftFn.SCREEN_INFO -> DrawOp.ScreenInfo(a[0], a[1], if (size >= 8) buf[off + 7].toInt() and 0xFF else 0)
        TftFn.DRAWSTRING, TftFn.PRINT, TftFn.DRAWCENTRESTRING, TftFn.DRAWRIGHTSTRING -> {
            // x,y,size,fg,bg then string bytes (5 uint16 = 10 bytes after fn)
            val textStart = off + 3 + 10
            val textLen = (off + size) - textStart
            val s = if (textLen > 0) String(buf, textStart, textLen, Charsets.UTF_8) else ""
            DrawOp.Text(a[0], a[1], a[2], rgb565ToArgb(a[3]), rgb565ToArgb(a[4]), s, fn)
        }
        else -> DrawOp.Unknown(fn)
    }
}
