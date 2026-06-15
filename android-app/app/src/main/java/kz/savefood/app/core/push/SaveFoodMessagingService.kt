package kz.savefood.app.core.push

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import kz.savefood.app.MainActivity
import kz.savefood.app.R
import javax.inject.Inject

/**
 * Receives FCM pushes while the app is in the foreground (system tray handles
 * them when backgrounded) and handles token rotation.
 *
 * Hilt-injected; only ever instantiated when Firebase is configured, since the
 * service is unreachable without google-services.json driving auto-init.
 */
@AndroidEntryPoint
class SaveFoodMessagingService : FirebaseMessagingService() {

    @Inject lateinit var pushTokenManager: PushTokenManager

    override fun onNewToken(token: String) {
        // Runs on a background thread; block briefly to push the rotated token.
        runCatching { runBlocking { pushTokenManager.registerNewToken(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification
        val title = notification?.title ?: getString(R.string.app_name)
        val body = notification?.body ?: message.data["body"].orEmpty()
        val url = message.data[PushDeepLink.EXTRA_URL]
        showNotification(title, body, url)
    }

    private fun showNotification(title: String, body: String, url: String?) {
        // Android 13+ requires the runtime POST_NOTIFICATIONS grant to post.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureDefaultChannel(this)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (url != null) putExtra(PushDeepLink.EXTRA_URL, url)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            url.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, DEFAULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        // A single id: pushes for the same account collapse rather than stack.
        const val NOTIFICATION_ID = 1001
    }
}
