package ru.savefood.app.core.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands a route off to a maps app instead of drawing turn-by-turn navigation
 * ourselves. This is also where a traffic-aware ETA comes from for free — our own
 * estimate is a straight line at 30 km/h.
 *
 * <p>Two targets, tried in order:
 * 1. Yandex Navigator via `yandexnavi://build_route_on_map`. It accepts a single
 *    destination only, so the driver reopens it at each stop.
 * 2. Web Yandex Maps, which does take the whole itinerary (`rtext=` with
 *    `~`-separated waypoints) — used when Navigator is not installed.
 */
object Navigation {

    /**
     * Open [stops] (nearest first) for driving. Entries without coordinates are
     * ignored; no usable stop means nothing happens.
     *
     * @return true when some app accepted the intent
     */
    fun openRoute(context: Context, stops: List<Pair<Double, Double>>): Boolean {
        val usable = stops.filter { it.first != 0.0 || it.second != 0.0 }
        if (usable.isEmpty()) {
            return false
        }
        val (lat, lon) = usable.first()
        val naviUri = Uri.parse("yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon")
        if (startIfResolvable(context, naviUri)) {
            return true
        }
        val waypoints = usable.joinToString("~") { "${it.first},${it.second}" }
        return startIfResolvable(context, Uri.parse("https://yandex.ru/maps/?rtext=~$waypoints&rtt=auto"))
    }

    private fun startIfResolvable(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
