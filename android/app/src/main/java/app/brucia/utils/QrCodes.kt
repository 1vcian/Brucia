package app.brucia.utils

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Minimal offline QR rendering built on the bundled ZXing core encoder. Used by the Wi-Fi QR
 * generator in Utilities; needs no network and no device connection.
 */
object QrCodes {
    /**
     * Encode [content] as a square QR [Bitmap] of [size] px, or null if encoding fails. Black
     * modules on white so any camera app can read it regardless of the app theme.
     */
    fun encode(content: String, size: Int = 512): Bitmap? {
        if (content.isEmpty()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    pixels[row + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
        } catch (_: Exception) {
            null
        }
    }
}
