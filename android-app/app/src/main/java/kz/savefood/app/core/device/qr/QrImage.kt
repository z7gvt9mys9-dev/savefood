package kz.savefood.app.core.device.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [content] as a QR code. Returns an empty composable if encoding fails
 * (e.g. content too long) rather than crashing.
 *
 * @param sizePx pixel dimension of the generated square bitmap.
 */
@Composable
fun QrImage(
    content: String,
    modifier: Modifier = Modifier,
    sizePx: Int = 512,
    contentDescription: String? = null,
) {
    val bitmap = remember(content, sizePx) { encodeQr(content, sizePx) } ?: return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/** Encodes [content] into a square QR [Bitmap], or null on failure. */
fun encodeQr(content: String, sizePx: Int = 512): Bitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bmp = createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp[x, y] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    bmp
}.getOrNull()
