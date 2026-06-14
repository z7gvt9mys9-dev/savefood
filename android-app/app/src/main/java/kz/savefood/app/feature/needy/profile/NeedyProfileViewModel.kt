package kz.savefood.app.feature.needy.profile

import android.net.Uri
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
import kz.savefood.app.feature.needy.data.NeedyProfileDto
import kz.savefood.app.feature.needy.data.NeedyProfileUpdateDto
import kz.savefood.app.feature.needy.data.NeedyRepository
import javax.inject.Inject

data class NeedyProfileUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val profile: NeedyProfileDto? = null,
    val profileExists: Boolean = false,
    val saving: Boolean = false,
    val uploadingDoc: Boolean = false,
    val message: String? = null,
    val exportedJson: String? = null,
)

@HiltViewModel
class NeedyProfileViewModel @Inject constructor(
    private val repo: NeedyRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NeedyProfileUiState())
    val state: StateFlow<NeedyProfileUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val needyId = repo.currentNeedyId() ?: run {
                _state.update { it.copy(loading = false, error = "Нет сессии") }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            when (val res = repo.getProfile(needyId)) {
                is ApiResult.Success -> _state.update {
                    it.copy(loading = false, profile = res.data, profileExists = true)
                }
                is ApiResult.Error -> {
                    // 404 = no profile yet; start with a blank editable form.
                    if (res.code == 404) {
                        _state.update { it.copy(loading = false, profile = NeedyProfileDto(), profileExists = false) }
                    } else {
                        _state.update { it.copy(loading = false, error = res.message) }
                    }
                }
            }
        }
    }

    fun save(body: NeedyProfileUpdateDto, savedMessage: String) {
        viewModelScope.launch {
            val needyId = repo.currentNeedyId() ?: return@launch
            _state.update { it.copy(saving = true) }
            val res = repo.saveProfile(needyId, body, _state.value.profileExists)
            _state.update { it.copy(saving = false) }
            when (res) {
                is ApiResult.Success -> _state.update {
                    it.copy(profile = res.data, profileExists = true, message = savedMessage)
                }
                is ApiResult.Error -> _state.update { it.copy(message = res.message) }
            }
        }
    }

    fun uploadDocument(uri: Uri, uploadedMessage: String) {
        viewModelScope.launch {
            val needyId = repo.currentNeedyId() ?: return@launch
            _state.update { it.copy(uploadingDoc = true) }
            val res = repo.uploadDocument(needyId, uri)
            _state.update { it.copy(uploadingDoc = false) }
            when (res) {
                is ApiResult.Success -> _state.update {
                    it.copy(profile = res.data, profileExists = true, message = uploadedMessage)
                }
                is ApiResult.Error -> _state.update { it.copy(message = res.message) }
            }
        }
    }

    fun setGeoPush(enabled: Boolean) {
        viewModelScope.launch {
            val needyId = repo.currentNeedyId() ?: return@launch
            // Optimistic toggle.
            _state.update { it.copy(profile = it.profile?.copy(geoPushEnabled = enabled)) }
            val res = repo.setGeoPush(needyId, enabled)
            if (res is ApiResult.Error) {
                _state.update {
                    it.copy(profile = it.profile?.copy(geoPushEnabled = !enabled), message = res.message)
                }
            }
        }
    }

    fun export(doneMessage: String) {
        viewModelScope.launch {
            val needyId = repo.currentNeedyId() ?: return@launch
            when (val res = repo.exportAccount(needyId)) {
                is ApiResult.Success -> _state.update { it.copy(exportedJson = res.data, message = doneMessage) }
                is ApiResult.Error -> _state.update { it.copy(message = res.message) }
            }
        }
    }

    /** Deletes the account, then clears the session (routes back to login). */
    fun deleteAccount() {
        viewModelScope.launch {
            val needyId = repo.currentNeedyId() ?: return@launch
            when (val res = repo.deleteAccount(needyId)) {
                is ApiResult.Success -> authRepository.logout()
                is ApiResult.Error -> _state.update { it.copy(message = res.message) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
