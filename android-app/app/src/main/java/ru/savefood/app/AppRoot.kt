package ru.savefood.app
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.savefood.app.core.datastore.UserRole
import ru.savefood.app.core.designsystem.component.EmberBackground
import ru.savefood.app.core.designsystem.component.SaveFoodOutlinedButton
import ru.savefood.app.core.push.PushDeepLink
import ru.savefood.app.core.push.RequestNotificationPermission
import ru.savefood.app.feature.auth.LoginScreen
import ru.savefood.app.feature.needy.NeedyShell
import ru.savefood.app.feature.onboarding.OnboardingScreen
import ru.savefood.app.feature.onboarding.OnboardingViewModel
import ru.savefood.app.feature.shop.ShopShell
import ru.savefood.app.feature.volunteer.VolunteerShell
@Composable
fun AppRoot(
    deepLinkUrl: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    viewModel: AppViewModel = hiltViewModel(),
) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    EmberBackground(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            is SessionState.Loading -> LoadingSplash()
            is SessionState.LoggedOut -> LoginScreen()
            is SessionState.LoggedIn -> {
                RequestNotificationPermission()
                when (s.role) {
                    UserRole.ADMIN, UserRole.UNKNOWN -> AdminNotSupported(onLogout = viewModel::logout)
                    else -> {
                        val onboardingVm: OnboardingViewModel = hiltViewModel()
                        val onboardingDone by onboardingVm.completed.collectAsStateWithLifecycle()
                        when (onboardingDone) {
                            null -> LoadingSplash()
                            false -> OnboardingScreen(role = s.role, onFinish = onboardingVm::complete)
                            else -> {
                                val initialRoute = PushDeepLink.tabRoute(s.role, deepLinkUrl)
                                when (s.role) {
                                    UserRole.SHOP -> ShopShell(initialRoute, onDeepLinkConsumed)
                                    UserRole.VOLUNTEER -> VolunteerShell(initialRoute, onDeepLinkConsumed)
                                    else -> NeedyShell(initialRoute, onDeepLinkConsumed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun LoadingSplash() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
@Composable
private fun AdminNotSupported(onLogout: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.admin_web_only),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        SaveFoodOutlinedButton(
            text = stringResource(R.string.common_logout),
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
        )
    }
}
