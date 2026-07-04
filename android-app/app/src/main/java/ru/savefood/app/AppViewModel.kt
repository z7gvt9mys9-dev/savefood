package ru.savefood.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.savefood.app.core.datastore.UserRole
import ru.savefood.app.core.push.PushTokenManager
import ru.savefood.app.feature.auth.data.AuthRepository
import javax.inject.Inject

/** Top-level session state that drives whether we show login or a role shell. */
sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val role: UserRole, val relatedId: Int?) : SessionState
}

@HiltViewModel
class AppViewModel @Inject constructor(
    authRepository: AuthRepository,
    private val pushTokenManager: PushTokenManager,
) : ViewModel() {
    val sessionState: StateFlow<SessionState> = authRepository.sessionFlow
        .map { session ->
            if (session == null) SessionState.LoggedOut
            else SessionState.LoggedIn(session.role, session.relatedId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SessionState.Loading,
        )

    init {
        // Register the FCM token whenever a session becomes active — covers both
        // a fresh login and relaunching with a persisted session. Idempotent
        // server-side (ON CONFLICT), so re-emits are harmless. Unregister-on-
        // logout lives in AuthRepository, where it can run before the token clears.
        viewModelScope.launch {
            authRepository.sessionFlow
                .map { it != null }
                .distinctUntilChanged()
                .collect { loggedIn -> if (loggedIn) pushTokenManager.registerCurrentToken() }
        }
    }
}
