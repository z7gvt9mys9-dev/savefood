package kz.savefood.app.core.device.qr

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.google.zxing.BarcodeFormat
import kz.savefood.app.core.device.isPermissionGranted

/**
 * Full-screen QR scanner backed by zxing-android-embedded.
 *
 * Requests CAMERA permission on first composition; if denied, [onCancel] is
 * invoked so the caller can navigate away. [onResult] fires once with the
 * decoded text (scanning then pauses to avoid duplicate callbacks).
 */
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val currentResult = rememberUpdatedState(onResult)
    val currentCancel = rememberUpdatedState(onCancel)

    var hasPermission by rememberSaveable {
        mutableStateOf(context.isPermissionGranted(Manifest.permission.CAMERA))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) currentCancel.value()
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        Box(modifier.fillMaxSize())
        return
    }

    val barcodeView = remember {
        DecoratedBarcodeView(context).apply {
            barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        }
    }

    DisposableEffect(barcodeView) {
        val callback = object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                barcodeView.pause()
                result.text?.let { currentResult.value(it) }
            }
        }
        barcodeView.decodeContinuous(callback)
        barcodeView.resume()
        onDispose { barcodeView.pause() }
    }

    AndroidView(
        factory = { barcodeView },
        modifier = modifier.fillMaxSize(),
    )
}
