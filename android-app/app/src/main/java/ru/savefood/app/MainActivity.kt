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
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
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
