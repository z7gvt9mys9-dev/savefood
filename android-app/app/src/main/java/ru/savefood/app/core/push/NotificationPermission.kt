package ru.savefood.app.core.push

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Requests the Android 13+ POST_NOTIFICATIONS runtime permission once, the first
 * time it enters composition with the permission still ungranted. No-op below
 * Android 13 (permission is install-time) or once already granted.
 *
 * Call from a post-login surface so the prompt appears in an authenticated
 * context, not on the login screen.
 */
@Composable
fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val alreadyGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Result ignored: denial is fine — pushes simply won't show. */ }

    LaunchedEffect(Unit) {
        if (!alreadyGranted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
