package app.brucia.utils

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.core.graphics.createBitmap

/**
 * Minimal offline QR rendering built on the bundled ZXing core encoder. Used by the Wi-Fi QR
 * generator in Utilities; needs no network and no device connection.
 */
object QrCodes {
    /** ARGB pixels of a rendered QR code, row-major, [width] x [height]. */
    class Pixels(val width: Int, val height: Int, val argb: IntArray)

    /**
     * Encode [content] as a square QR [Bitmap] of [size] px, or null if encoding fails. Black
     * modules on white so any camera app can read it regardless of the app theme.
     */
    fun encode(content: String, size: Int = 512): Bitmap? {
        val p = pixels(content, size) ?: return null
        return createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
            .apply { setPixels(p.argb, 0, p.width, 0, 0, p.width, p.height) }
    }

    /** Platform-free part of [encode]: the QR pixels, or null if encoding fails. */
    fun pixels(content: String, size: Int = 512): Pixels? {
        if (content.isEmpty()) return null
        return try {
            val hints = mutableMapOf<EncodeHintType, Any>(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
            // ZXing's byte mode defaults to ISO-8859-1 and silently turns any other character
            // into '?'. Text outside Latin-1 (e.g. a Cyrillic or CJK SSID) is encoded as UTF-8
            // with an ECI header instead; Latin-1 text keeps the previous encoding.
            if (!Charsets.ISO_8859_1.newEncoder().canEncode(content)) {
                hints[EncodeHintType.CHARACTER_SET] = Charsets.UTF_8.name()
            }
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val w = matrix.width
            val h = matrix.height
            val argb = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    argb[row + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            Pixels(w, h, argb)
        } catch (_: Exception) {
            null
        }
    }
}
