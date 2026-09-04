package ru.savefood.app.core.push
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import com.google.firebase.FirebaseApp
import ru.savefood.app.R
const val DEFAULT_CHANNEL_ID = "savefood_default"
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
fun isFirebaseAvailable(context: Context): Boolean =
    runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
