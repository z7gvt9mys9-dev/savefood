package kz.savefood.app.core.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.google.firebase.FirebaseApp
import kz.savefood.app.R

/** Notification channel id referenced by the manifest's FCM default-channel
 *  meta-data; both must stay in sync. */
const val DEFAULT_CHANNEL_ID = "savefood_default"

/**
 * Idempotently create the default notification channel (no-op below Android 8,
 * where channels don't exist). Safe to call repeatedly.
 */
fun ensureDefaultChannel(context: Context) {
    val manager = context.getSystemService<NotificationManager>() ?: return
    if (manager.getNotificationChannel(DEFAULT_CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        DEFAULT_CHANNEL_ID,
        context.getString(R.string.push_channel_name),
        NotificationManager.IMPORTANCE_HIGH,
    ).apply {
        description = context.getString(R.string.push_channel_description)
    }
    manager.createNotificationChannel(channel)
}

/**
 * Whether Firebase is initialised. The google-services plugin (and thus
 * FirebaseApp auto-init) only runs when `app/google-services.json` is present,
 * so every FCM call site must guard on this to keep credential-less builds
 * working — see app/build.gradle.kts.
 */
fun isFirebaseAvailable(context: Context): Boolean =
    runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
