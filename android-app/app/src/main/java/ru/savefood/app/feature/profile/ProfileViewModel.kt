package ru.savefood.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import ru.savefood.app.feature.auth.data.AuthRepository
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {
    fun logout() {
        // Clearing the session makes AppViewModel route back to login.
        viewModelScope.launch { authRepository.logout() }
    }
}
