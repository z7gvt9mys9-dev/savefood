package ru.savefood.app.core.device.map

/** Whether Yandex MapKit was successfully configured during application startup. */
object MapKitStatus {
    @Volatile
    var isReady: Boolean = false
}
