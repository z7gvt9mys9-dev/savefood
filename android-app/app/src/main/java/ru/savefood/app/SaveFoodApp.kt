package ru.savefood.app

import android.app.Application
import android.util.Log
import com.yandex.mapkit.MapKitFactory
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SaveFoodApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initYandexMapKit()
    }

    /**
     * Initialises Yandex MapKit with the build-time API key.
     *
     * Guarded: an empty key (e.g. local builds without the secret configured)
     * only logs a warning instead of crashing. Map screens degrade gracefully —
     * MapKit views simply won't render tiles until a valid key is supplied.
     */
    private fun initYandexMapKit() {
        val key = BuildConfig.YANDEX_MAPKIT_API_KEY
        if (key.isBlank()) {
            Log.w(
                TAG,
                "YANDEX_MAPKIT_API_KEY is empty — map features disabled. " +
                    "Set it via -PYANDEX_MAPKIT_API_KEY or gradle.properties.",
            )
            return
        }
        runCatching {
            MapKitFactory.setApiKey(key)
            MapKitFactory.initialize(this)
        }.onFailure { e ->
            Log.w(TAG, "Yandex MapKit initialization failed", e)
        }
    }

    private companion object {
        const val TAG = "SaveFoodApp"
    }
}
