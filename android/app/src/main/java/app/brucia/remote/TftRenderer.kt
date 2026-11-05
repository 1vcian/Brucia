package app.brucia.remote

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import androidx.core.graphics.createBitmap

/**
 * Applies [DrawOp]s from the device's TFT mirror onto a Bitmap, reproducing the Bruce
 * screen. Default size matches the T-Embed CC1101 panel until a SCREEN_INFO op arrives.
 * Rendering mirrors Adafruit-GFX semantics (string origin = top-left).
 */
class TftRenderer(width: Int = 320, height: Int = 170) {
    @Volatile var bitmap: Bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        private set
    private var canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { bitmap.eraseColor(0xFF000000.toInt()) }

    @Synchronized
    fun apply(op: DrawOp) {
        when (op) {
            is DrawOp.ScreenInfo -> if (op.width in 1..2048 && op.height in 1..2048 &&
                (op.width != bitmap.width || op.height != bitmap.height)) {
                bitmap = createBitmap(op.width, op.height, Bitmap.Config.ARGB_8888)
                canvas = Canvas(bitmap)
                bitmap.eraseColor(0xFF000000.toInt())
            }
            is DrawOp.FillScreen -> canvas.drawColor(op.color)
            is DrawOp.Rect -> {
                paint.color = op.color
                paint.style = if (op.fill) Paint.Style.FILL else Paint.Style.STROKE
                canvas.drawRect(op.x.toFloat(), op.y.toFloat(), (op.x + op.w).toFloat(), (op.y + op.h).toFloat(), paint)
            }
            is DrawOp.RoundRect -> {
                paint.color = op.color
                paint.style = if (op.fill) Paint.Style.FILL else Paint.Style.STROKE
                canvas.drawRoundRect(op.x.toFloat(), op.y.toFloat(), (op.x + op.w).toFloat(), (op.y + op.h).toFloat(), op.r.toFloat(), op.r.toFloat(), paint)
            }
            is DrawOp.Circle -> {
                paint.color = op.color
                paint.style = if (op.fill) Paint.Style.FILL else Paint.Style.STROKE
                canvas.drawCircle(op.x.toFloat(), op.y.toFloat(), op.r.toFloat(), paint)
            }
            is DrawOp.Triangle -> {
                paint.color = op.color
                paint.style = if (op.fill) Paint.Style.FILL else Paint.Style.STROKE
                val p = Path().apply {
                    moveTo(op.x1.toFloat(), op.y1.toFloat())
                    lineTo(op.x2.toFloat(), op.y2.toFloat())
                    lineTo(op.x3.toFloat(), op.y3.toFloat())
                    close()
                }
                canvas.drawPath(p, paint)
            }
            is DrawOp.Line -> {
                paint.color = op.color; paint.style = Paint.Style.STROKE
                canvas.drawLine(op.x1.toFloat(), op.y1.toFloat(), op.x2.toFloat(), op.y2.toFloat(), paint)
            }
            is DrawOp.FastLine -> {
                paint.color = op.color; paint.style = Paint.Style.STROKE
                if (op.vertical) canvas.drawLine(op.x.toFloat(), op.y.toFloat(), op.x.toFloat(), (op.y + op.len).toFloat(), paint)
                else canvas.drawLine(op.x.toFloat(), op.y.toFloat(), (op.x + op.len).toFloat(), op.y.toFloat(), paint)
            }
            is DrawOp.Pixel -> {
                paint.color = op.color; paint.style = Paint.Style.FILL
                canvas.drawPoint(op.x.toFloat(), op.y.toFloat(), paint)
            }
            is DrawOp.Ellipse -> {
                paint.color = op.color
                paint.style = if (op.fill) Paint.Style.FILL else Paint.Style.STROKE
                canvas.drawOval((op.x - op.rx).toFloat(), (op.y - op.ry).toFloat(), (op.x + op.rx).toFloat(), (op.y + op.ry).toFloat(), paint)
            }
            is DrawOp.WideLine -> {
                paint.color = op.color
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = op.width.coerceAtLeast(1).toFloat()
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(op.ax.toFloat(), op.ay.toFloat(), op.bx.toFloat(), op.by.toFloat(), paint)
                paint.strokeWidth = 0f
                paint.strokeCap = Paint.Cap.BUTT
            }
            is DrawOp.Arc -> {
                paint.color = op.color
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (op.rOuter - op.rInner).coerceAtLeast(1).toFloat()
                val r = ((op.rOuter + op.rInner) / 2f).coerceAtLeast(1f)
                val sweep = (op.endAngle - op.startAngle).toFloat()
                canvas.drawArc(op.x - r, op.y - r, op.x + r, op.y + r, op.startAngle.toFloat() - 90f, sweep, false, paint)
                paint.strokeWidth = 0f
            }
            is DrawOp.Image -> {
                // The image lives on the device SD; show a placeholder so layout is intact.
                paint.color = 0xFF333338.toInt(); paint.style = Paint.Style.FILL
                canvas.drawRect(op.x.toFloat(), op.y.toFloat(), (op.x + 24).toFloat(), (op.y + 24).toFloat(), paint)
            }
            is DrawOp.Text -> {
                val size = (if (op.size < 1) 1 else op.size)
                val px = 8f * size // GFX base font is ~6x8; 8px/size is a close match
                textPaint.textSize = px
                // background cell (GFX fills bg behind text when bg != fg)
                if (op.bg != op.fg) {
                    textPaint.color = op.bg
                    val w = op.text.length * 6f * size
                    canvas.drawRect(op.x.toFloat(), op.y.toFloat(), op.x + w, op.y + px, textPaint)
                }
                textPaint.color = op.fg
                var x = op.x.toFloat()
                if (op.align == TftFn.DRAWCENTRESTRING) x -= op.text.length * 3f * size
                if (op.align == TftFn.DRAWRIGHTSTRING) x -= op.text.length * 6f * size
                canvas.drawText(op.text, x, op.y + px - 1.5f * size, textPaint) // GFX origin is top-left
            }
            is DrawOp.Unknown -> {}
        }
    }

    @Synchronized fun applyAll(ops: List<DrawOp>) { for (o in ops) apply(o) }
}
