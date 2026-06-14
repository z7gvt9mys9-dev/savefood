package kz.savefood.app.core.device.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kz.savefood.app.core.device.isPermissionGranted
import kotlin.coroutines.resume

/**
 * Thin Hilt-injectable wrapper over FusedLocationProvider.
 *
 * Both APIs degrade gracefully when location permission is absent:
 * [lastLocation] returns null and [updates] emits nothing (closes immediately),
 * so callers never crash on a missing grant.
 */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private fun hasLocationPermission(): Boolean =
        context.isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            context.isPermissionGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** One-shot current location, or null if unavailable / permission missing. */
    @SuppressLint("MissingPermission")
    suspend fun lastLocation(): Location? {
        if (!hasLocationPermission()) return null
        return runCatching {
            suspendCancellableCoroutine { cont ->
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
        }.getOrNull()
    }

    /**
     * Stream of location fixes at the requested [intervalMs].
     * Emits nothing and closes immediately when permission is missing.
     */
    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long): Flow<Location> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }
        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            intervalMs,
        ).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it) }
            }
        }
        runCatching {
            client.requestLocationUpdates(request, callback, context.mainLooper)
        }.onFailure { close(it) }
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
