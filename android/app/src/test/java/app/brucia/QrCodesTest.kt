package app.brucia

import app.brucia.utils.PhoneUtils
import app.brucia.utils.QrCodes
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Encodes with the production QR path and decodes the pixels back with ZXing's reader. */
class QrCodesTest {

    private fun decode(p: QrCodes.Pixels): com.google.zxing.Result {
        val source = RGBLuminanceSource(p.width, p.height, p.argb)
        return QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.PURE_BARCODE to true),
        )
    }

    private fun roundTrip(content: String): com.google.zxing.Result {
        val p = QrCodes.pixels(content, 512)
        assertNotNull("encoding failed", p)
        val result = decode(p!!)
        assertEquals(content, result.text)
        return result
    }

    @Test fun wifiPayloadWithEscapedCharactersRoundTrips() {
        val payload = PhoneUtils.wifiQrPayload("Cafe;Bar,\"x\":1", "p:a\\ss;w,d", "WPA")
        assertEquals("""WIFI:T:WPA;S:Cafe\;Bar\,\"x\"\:1;P:p\:a\\ss\;w\,d;;""", payload)
        roundTrip(payload)
        roundTrip(PhoneUtils.wifiQrPayload("Open net", "", "nopass"))
    }

    @Test fun nonLatin1TextIsEncodedAsUtf8NotQuestionMarks() {
        // Before the UTF-8 hint ZXing wrote these as '?' (ISO-8859-1 byte mode).
        val payload = PhoneUtils.wifiQrPayload("Дом 家 Wi-Fi", "пароль", "WPA")
        val result = roundTrip(payload)
        @Suppress("UNCHECKED_CAST")
        val segments = result.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<ByteArray>
        assertArrayEquals(payload.toByteArray(Charsets.UTF_8), segments.reduce { a, b -> a + b })
    }

    @Test fun latin1TextKeepsLatin1Bytes() {
        val payload = PhoneUtils.wifiQrPayload("Caffè", "ñandú", "WPA")
        val result = roundTrip(payload)
        @Suppress("UNCHECKED_CAST")
        val segments = result.resultMetadata[ResultMetadataType.BYTE_SEGMENTS] as List<ByteArray>
        assertArrayEquals(payload.toByteArray(Charsets.ISO_8859_1), segments.reduce { a, b -> a + b })
    }

    @Test fun donationAddressRoundTripsAtRequestedSize() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
        val p = QrCodes.pixels(address, 256)!!
        assertEquals(256, p.width)
        assertEquals(256, p.height)
        assertEquals(256 * 256, p.argb.size)
        assertTrue(p.argb.all { it == 0xFF000000.toInt() || it == 0xFFFFFFFF.toInt() })
        assertEquals(address, decode(p).text)
    }

    @Test fun emptyOrOversizedContentGivesNull() {
        assertNull(QrCodes.pixels(""))
        // Beyond QR version 40 byte capacity at level M (2331 bytes): encoder fails, no throw.
        assertNull(QrCodes.pixels("a".repeat(3000)))
    }
}
