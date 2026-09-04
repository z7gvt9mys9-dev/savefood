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
