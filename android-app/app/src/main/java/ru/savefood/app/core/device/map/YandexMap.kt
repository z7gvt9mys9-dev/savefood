package ru.savefood.app.core.device.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.InputListener
import com.yandex.mapkit.map.Map as YMap
import com.yandex.mapkit.map.MapObjectTapListener
import com.yandex.mapkit.map.PlacemarkMapObject
import com.yandex.mapkit.mapview.MapView

/** A single marker rendered on the map. [id] is echoed back on tap. */
data class MapMarker(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val title: String? = null,
)

private const val DEFAULT_ZOOM = 13f

/**
 * Compose wrapper around a Yandex [MapView].
 *
 * @param markers placemarks to render; tapping one invokes [onMarkerClick] with its id.
 * @param center initial camera focus. Falls back to the first marker, then Moscow.
 * @param onMarkerClick invoked with [MapMarker.id] when a placemark is tapped.
 * @param onMapClick invoked with the tapped [Point] for empty-map taps.
 *
 * Lifecycle is bound to the host: MapKit + MapView are started/stopped with
 * the composable's [Lifecycle], matching Yandex's required onStart/onStop calls.
 */
@Composable
fun YandexMap(
    markers: List<MapMarker>,
    modifier: Modifier = Modifier,
    center: Point? = null,
    initialZoom: Float = DEFAULT_ZOOM,
    onMarkerClick: (String) -> Unit = {},
    onMapClick: (Point) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentMarkerClick = rememberUpdatedState(onMarkerClick)
    val currentMapClick = rememberUpdatedState(onMapClick)

    val mapView = remember { MapView(context) }

    // Tap listener kept as a field so MapKit holds a strong reference (it uses weak refs).
    val tapListener = remember {
        MapObjectTapListener { mapObject, _ ->
            (mapObject.userData as? String)?.let { currentMarkerClick.value(it) }
            true
        }
    }
    val inputListener = remember {
        object : InputListener {
            override fun onMapTap(map: YMap, point: Point) = currentMapClick.value(point)
            override fun onMapLongTap(map: YMap, point: Point) = Unit
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    MapKitFactory.getInstance().onStart()
                    mapView.onStart()
                }
                Lifecycle.Event.ON_STOP -> {
                    mapView.onStop()
                    MapKitFactory.getInstance().onStop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = {
            mapView.mapWindow.map.addInputListener(inputListener)
            mapView
        },
        modifier = modifier,
        update = { view ->
            val map = view.mapWindow.map
            val focus = center
                ?: markers.firstOrNull()?.let { Point(it.latitude, it.longitude) }
                ?: MOSCOW
            map.move(CameraPosition(focus, initialZoom, 0f, 0f))

            val collection = map.mapObjects
            collection.clear()
            markers.forEach { marker ->
                // Default-pin overload: feature screens supply custom icons via
                // a later style pass; a plain placemark is sufficient here.
                @Suppress("DEPRECATION")
                val placemark: PlacemarkMapObject = collection.addPlacemark(
                    Point(marker.latitude, marker.longitude),
                )
                placemark.userData = marker.id
                placemark.addTapListener(tapListener)
            }
        },
    )
}

private val MOSCOW = Point(55.7558, 37.6173)
