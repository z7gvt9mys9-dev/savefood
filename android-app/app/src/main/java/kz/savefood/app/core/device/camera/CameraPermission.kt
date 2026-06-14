package kz.savefood.app.core.device.camera

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kz.savefood.app.core.device.isPermissionGranted

/**
 * Requests CAMERA permission on first composition and exposes the current grant
 * state. [onResult] (optional) is invoked once with the outcome.
 *
 * @return a [State] that is true once CAMERA is granted.
 */
@Composable
fun rememberCameraPermissionState(
    onResult: (Boolean) -> Unit = {},
): State<Boolean> {
    val context = LocalContext.current
    val currentResult = rememberUpdatedState(onResult)
    val granted = rememberSaveable {
        mutableStateOf(context.isPermissionGranted(CAMERA_PERMISSION))
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted.value = result
        currentResult.value(result)
    }
    LaunchedEffect(Unit) {
        if (granted.value) currentResult.value(true) else launcher.launch(CAMERA_PERMISSION)
    }
    return granted
}
