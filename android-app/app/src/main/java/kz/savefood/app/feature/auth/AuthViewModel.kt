package kz.savefood.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kz.savefood.app.core.common.ApiResult
import kz.savefood.app.feature.auth.data.AuthRepository
import javax.inject.Inject

data class AuthUiState(
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun login() {
        val s = _state.value
        if (s.username.isBlank() || s.password.isBlank() || s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // On success, AppViewModel's session flow reacts and routes to the
            // role shell — no explicit navigation needed here.
            when (val res = authRepository.login(s.username, s.password)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false) }
                is ApiResult.Error -> _state.update { it.copy(loading = false, error = res.message) }
            }
        }
    }
}
