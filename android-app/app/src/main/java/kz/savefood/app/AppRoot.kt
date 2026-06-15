package kz.savefood.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.savefood.app.core.datastore.UserRole
import kz.savefood.app.core.push.PushDeepLink
import kz.savefood.app.core.push.RequestNotificationPermission
import kz.savefood.app.feature.auth.LoginScreen
import kz.savefood.app.feature.needy.NeedyShell
import kz.savefood.app.feature.onboarding.OnboardingScreen
import kz.savefood.app.feature.onboarding.OnboardingViewModel
import kz.savefood.app.feature.shop.ShopShell
import kz.savefood.app.feature.volunteer.VolunteerShell

/**
 * Top-level router. Watches the persisted session and shows login, a role shell,
 * or a brief loading splash. Admin has no mobile UI → routed to a notice.
 *
 * [deepLinkUrl] is a notification's `data.url` (or null); it's resolved to a tab
 * route for the active role and handed to the shell, which navigates once and
 * calls [onDeepLinkConsumed] to clear it.
 */
@Composable
fun AppRoot(
    deepLinkUrl: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    viewModel: AppViewModel = hiltViewModel(),
) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val s = state) {
            is SessionState.Loading -> LoadingSplash()
            is SessionState.LoggedOut -> LoginScreen()
            is SessionState.LoggedIn -> {
                // Prompt for notification permission once, in an authed context.
                RequestNotificationPermission()
                when (s.role) {
                    UserRole.ADMIN, UserRole.UNKNOWN -> AdminNotSupported()
                    else -> {
                        val onboardingVm: OnboardingViewModel = hiltViewModel()
                        val onboardingDone by onboardingVm.completed.collectAsStateWithLifecycle()
                        when (onboardingDone) {
                            // null = flag not yet resolved; avoid flashing onboarding.
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
private fun AdminNotSupported() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Администрирование доступно только в веб-версии.",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
