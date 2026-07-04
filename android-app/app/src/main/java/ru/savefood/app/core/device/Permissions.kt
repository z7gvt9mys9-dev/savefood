package ru.savefood.app.core.device

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Returns true when [permission] is currently granted to the app. */
internal fun Context.isPermissionGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) ==
        PackageManager.PERMISSION_GRANTED
