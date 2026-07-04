package ru.savefood.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.AndroidEntryPoint
import ru.savefood.app.core.designsystem.theme.SaveFoodTheme
import ru.savefood.app.core.push.PushDeepLink

// AppCompatActivity (not ComponentActivity) so the runtime per-app language switch
// in Profile — AppCompatDelegate.setApplicationLocales — applies and persists on
// API < 33 too. Hilt (@AndroidEntryPoint) and Compose setContent work unchanged;
// the host theme is already an AppCompat DayNight theme.
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // Deep-link target from a tapped notification (the backend's data.url). Read
    // from the launch intent and from onNewIntent when already running; consumed
    // once by AppRoot, which routes to the matching role tab.
    private val deepLinkUrl = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        deepLinkUrl.value = intent?.getStringExtra(PushDeepLink.EXTRA_URL)
        setContent {
            SaveFoodTheme {
                AppRoot(
                    deepLinkUrl = deepLinkUrl.value,
                    onDeepLinkConsumed = { deepLinkUrl.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(PushDeepLink.EXTRA_URL)?.let { deepLinkUrl.value = it }
    }
}
