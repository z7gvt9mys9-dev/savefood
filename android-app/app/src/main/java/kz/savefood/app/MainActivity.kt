package kz.savefood.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.AndroidEntryPoint
import kz.savefood.app.core.designsystem.theme.SaveFoodTheme
import kz.savefood.app.core.push.PushDeepLink

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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
