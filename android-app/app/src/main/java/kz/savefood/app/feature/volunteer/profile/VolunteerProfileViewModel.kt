package kz.savefood.app.feature.volunteer.profile

import android.net.Uri
import android.util.Log
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
import kz.savefood.app.feature.volunteer.data.NotificationDto
import kz.savefood.app.feature.volunteer.data.RouteHistoryDto
import kz.savefood.app.feature.volunteer.data.VolunteerDto
import kz.savefood.app.feature.volunteer.data.VolunteerRepository
import kz.savefood.app.feature.volunteer.data.VolunteerUpdateDto
import javax.inject.Inject

data class VolunteerProfileUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** A secondary section (history / notifications) failed to load while the core
     *  profile loaded fine — shown as a non-blocking banner, not an empty state. */
    val partialError: Boolean = false,
    val profile: VolunteerDto? = null,
    val history: List<RouteHistoryDto> = emptyList(),
    val notifications: List<NotificationDto> = emptyList(),
    val saving: Boolean = false,
    val uploadingDoc: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class VolunteerProfileViewModel @Inject constructor(
    private val repo: VolunteerRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(VolunteerProfileUiState())
    val state: StateFlow<VolunteerProfileUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val volunteerId = repo.currentVolunteerId() ?: run {
                _state.update { it.copy(loading = false, error = "Нет сессии") }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null, partialError = false) }
            when (val res = repo.getVolunteer(volunteerId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, profile = res.data) }
                is ApiResult.Error -> {
                    _state.update { it.copy(loading = false, error = res.message) }
                    return@launch
                }
            }
            var partial = false
            when (val res = repo.getHistory(volunteerId)) {
                is ApiResult.Success -> _state.update { it.copy(history = res.data) }
                is ApiResult.Error -> { partial = true; Log.w(TAG, "getHistory failed: ${res.message}") }
            }
            when (val res = repo.getNotifications(volunteerId)) {
                is ApiResult.Success -> _state.update { it.copy(notifications = res.data) }
                is ApiResult.Error -> { partial = true; Log.w(TAG, "getNotifications failed: ${res.message}") }
            }
            if (partial) _state.update { it.copy(partialError = true) }
        }
    }

    fun save(name: String?, contact: String?, city: String?, hasThermalBag: Boolean, savedMessage: String) {
        viewModelScope.launch {
            val volunteerId = repo.currentVolunteerId() ?: return@launch
            _state.update { it.copy(saving = true) }
            val body = VolunteerUpdateDto(
                name = name?.takeIf { it.isNotBlank() },
                contact = contact?.takeIf { it.isNotBlank() },
                city = city?.takeIf { it.isNotBlank() },
                hasThermalBag = hasThermalBag,
            )
            val res = repo.patchVolunteer(volunteerId, body)
            _state.update { it.copy(saving = false) }
            when (res) {
                is ApiResult.Success -> _state.update { it.copy(profile = res.data, message = savedMessage) }
                is ApiResult.Error -> _state.update { it.copy(message = res.message) }
            }
        }
    }

    fun uploadDocument(uri: Uri, uploadedMessage: String) {
        viewModelScope.launch {
            val volunteerId = repo.currentVolunteerId() ?: return@launch
            _state.update { it.copy(uploadingDoc = true) }
            val res = repo.uploadDocument(volunteerId, uri)
            _state.update { it.copy(uploadingDoc = false) }
            when (res) {
                is ApiResult.Success -> {
                    _state.update { it.copy(message = uploadedMessage) }
                    load() // refresh status (pending)
                }
                is ApiResult.Error -> _state.update { it.copy(message = res.message) }
            }
        }
    }

    fun markNotificationRead(notificationId: Int, failureMessage: String) {
        viewModelScope.launch {
            when (val res = repo.markNotificationRead(notificationId)) {
                is ApiResult.Success -> _state.update { st ->
                    st.copy(notifications = st.notifications.map {
                        if (it.id == notificationId) it.copy(read = 1) else it
                    })
                }
                // Don't flip the row to "read" if the server rejected it; tell the user.
                is ApiResult.Error -> {
                    Log.w(TAG, "markNotificationRead($notificationId) failed: ${res.message}")
                    _state.update { it.copy(message = failureMessage) }
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        private const val TAG = "VolunteerProfileVM"
    }
}
