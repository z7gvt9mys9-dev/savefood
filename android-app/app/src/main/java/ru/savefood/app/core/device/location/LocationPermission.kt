package ru.savefood.app.core.device.location
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import ru.savefood.app.core.device.isPermissionGranted
/** Current location-permission state and an explicit request action. */
class LocationPermissionState internal constructor(
    private val grantedState: State<Boolean>,
    private val requestPermissions: () -> Unit,
) {
    val isGranted: Boolean get() = grantedState.value
    fun request() = requestPermissions()
}
@Composable
fun rememberLocationPermissionState(
    onResult: (Boolean) -> Unit = {},
): LocationPermissionState {
    val context = LocalContext.current
    val currentResult by rememberUpdatedState(onResult)
    val granted = rememberSaveable {
        mutableStateOf(hasLocationPermission(context))
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val allowed = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        granted.value = allowed
        currentResult(allowed)
    }
    return remember(launcher) {
        LocationPermissionState(granted) {
            launcher.launch(LOCATION_PERMISSIONS)
        }
    }
}
private fun hasLocationPermission(context: android.content.Context): Boolean =
    context.isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        context.isPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)
