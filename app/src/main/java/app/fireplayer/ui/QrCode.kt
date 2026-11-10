package app.fireplayer.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR codes for addresses the phone opens, so it can scan instead of typing. */
object QrCode {

    /** The code's modules, with a quiet zone of [margin] modules all round; one matrix cell per module. */
    fun matrix(text: String, margin: Int = 3): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to margin),
    )

    /** Dark modules on white, each module a whole number of pixels so the edges stay sharp. */
    fun bitmap(text: String, maxSizePx: Int): Bitmap {
        val m = matrix(text)
        val cell = (maxSizePx / m.width).coerceAtLeast(1)
        val size = m.width * cell
        val pixels = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * size + x] = if (m[x / cell, y / cell]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
