package ru.savefood.app.core.device.map
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
@Composable
fun YandexMap(
    markers: List<MapMarker>,
    modifier: Modifier = Modifier,
    center: Point? = null,
    initialZoom: Float = DEFAULT_ZOOM,
    onMarkerClick: (String) -> Unit = {},
    onMapClick: (Point) -> Unit = {},
    onMapError: () -> Unit = {},
) {
    // MapView cannot safely be created until MapKit has an API key and has
    // completed initialization. This keeps map tabs responsive in builds where
    // the optional key was intentionally omitted.
    if (!MapKitStatus.isReady) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentMarkerClick = rememberUpdatedState(onMarkerClick)
    val currentMapClick = rememberUpdatedState(onMapClick)
    val currentMapError = rememberUpdatedState(onMapError)
    val mapViewResult = remember(context) { runCatching { MapView(context) } }
    val mapView = mapViewResult.getOrNull()
    if (mapView == null) {
        LaunchedEffect(mapViewResult.exceptionOrNull()) {
            currentMapError.value()
        }
        return
    }
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
        var started = false
        fun start() {
            if (started) return
            runCatching {
                MapKitFactory.getInstance().onStart()
                mapView.onStart()
                started = true
            }.onFailure { currentMapError.value() }
        }
        fun stop() {
            if (!started) return
            runCatching {
                mapView.onStop()
                MapKitFactory.getInstance().onStop()
            }.onFailure { currentMapError.value() }
            started = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose {
            stop()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    AndroidView(
        factory = {
            runCatching {
                mapView.mapWindow.map.addInputListener(inputListener)
            }.onFailure { currentMapError.value() }
            mapView
        },
        modifier = modifier,
        update = { view ->
            runCatching {
                val map = view.mapWindow.map
                map.isNightModeEnabled = true
                val focus = center
                    ?: markers.firstOrNull()?.let { Point(it.latitude, it.longitude) }
                    ?: MOSCOW
                map.move(CameraPosition(focus, initialZoom, 0f, 0f))
                val collection = map.mapObjects
                collection.clear()
                markers.forEach { marker ->
                    @Suppress("DEPRECATION")
                    val placemark: PlacemarkMapObject = collection.addPlacemark(
                        Point(marker.latitude, marker.longitude),
                    )
                    placemark.userData = marker.id
                    placemark.addTapListener(tapListener)
                }
            }.onFailure { currentMapError.value() }
        },
    )
}
private val MOSCOW = Point(55.7558, 37.6173)
