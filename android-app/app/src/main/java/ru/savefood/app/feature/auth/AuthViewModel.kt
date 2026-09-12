package ru.savefood.app.feature.auth
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.savefood.app.core.common.ApiResult
import ru.savefood.app.feature.auth.data.AuthRepository
import javax.inject.Inject

enum class AuthMode { LOGIN, REGISTER }
enum class AuthRole(val apiValue: String) {
    SHOP("shop"),
    VOLUNTEER("volunteer"),
    NEEDY("needy"),
}

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val role: AuthRole = AuthRole.SHOP,
    val username: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val name: String = "",
    val contact: String = "",
    val city: String = "",
    val donorKind: String = "business",
    val agreed: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val registrationValid: Boolean
        get() = name.isNotBlank() && username.isNotBlank() && password.length in 8..128 &&
            password == confirmPassword && agreed && (role != AuthRole.SHOP || contact.isNotBlank())
}
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()
    fun setMode(mode: AuthMode) = _state.update { it.copy(mode = mode, error = null) }
    fun selectRole(role: AuthRole) = _state.update { it.copy(role = role, error = null) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onConfirmPasswordChange(value: String) = _state.update { it.copy(confirmPassword = value, error = null) }
    fun onNameChange(value: String) = _state.update { it.copy(name = value, error = null) }
    fun onContactChange(value: String) = _state.update { it.copy(contact = value, error = null) }
    fun onCityChange(value: String) = _state.update { it.copy(city = value, error = null) }
    fun onDonorKindChange(value: String) = _state.update { it.copy(donorKind = value, error = null) }
    fun onAgreedChange(value: Boolean) = _state.update { it.copy(agreed = value, error = null) }
    fun login() {
        val s = _state.value
        if (s.username.isBlank() || s.password.isBlank() || s.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val res = authRepository.login(s.username, s.password)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false) }
                is ApiResult.Error -> _state.update { it.copy(loading = false, error = res.message) }
            }
        }
    }

    fun register() {
        val s = _state.value
        if (!s.registrationValid || s.loading) return
        val contact = if (s.role == AuthRole.SHOP) s.contact else s.username
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (
                val res = authRepository.register(
                    role = s.role,
                    name = s.name,
                    username = s.username,
                    password = s.password,
                    contact = contact,
                    city = s.city,
                    donorKind = s.donorKind,
                )
            ) {
                is ApiResult.Success -> _state.update { it.copy(loading = false) }
                is ApiResult.Error -> _state.update { it.copy(loading = false, error = res.message) }
            }
        }
    }
}
