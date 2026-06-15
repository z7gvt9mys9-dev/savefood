package kz.savefood.app.feature.shop.profile

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
import kz.savefood.app.feature.shop.data.NotificationDto
import kz.savefood.app.feature.shop.data.PlanDto
import kz.savefood.app.feature.shop.data.ShopDto
import kz.savefood.app.feature.shop.data.ShopRepository
import kz.savefood.app.feature.shop.data.ShopUpdateDto
import javax.inject.Inject

data class ShopProfileUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val shop: ShopDto? = null,
    val plan: PlanDto? = null,
    val notifications: List<NotificationDto> = emptyList(),
    val saving: Boolean = false,
    val confirmingPickup: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ShopProfileViewModel @Inject constructor(
    private val repo: ShopRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ShopProfileUiState())
    val state: StateFlow<ShopProfileUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val shopId = repo.currentShopId() ?: run {
                _state.update { it.copy(loading = false, error = "Нет сессии") }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            when (val res = repo.getShop(shopId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, shop = res.data) }
                is ApiResult.Error -> {
                    _state.update { it.copy(loading = false, error = res.message) }
                    return@launch
                }
            }
            (repo.getPlan(shopId) as? ApiResult.Success)?.let { res ->
                _state.update { it.copy(plan = res.data) }
            }
            (repo.getNotifications(shopId) as? ApiResult.Success)?.let { res ->
                _state.update { it.copy(notifications = res.data) }
            }
        }
    }

    fun save(name: String, contact: String, city: String, savedMessage: String) {
        viewModelScope.launch {
            val shopId = repo.currentShopId() ?: return@launch
            _state.update { it.copy(saving = true) }
            val body = ShopUpdateDto(
                name = name.takeIf { it.isNotBlank() },
                contact = contact.takeIf { it.isNotBlank() },
                city = city.takeIf { it.isNotBlank() },
            )
            when (val res = repo.patchShop(shopId, body)) {
                is ApiResult.Success -> _state.update { it.copy(saving = false, shop = res.data, message = savedMessage) }
                is ApiResult.Error -> _state.update { it.copy(saving = false, message = res.message) }
            }
        }
    }

    fun markNotificationRead(notificationId: Int) {
        viewModelScope.launch {
            when (repo.markNotificationRead(notificationId)) {
                is ApiResult.Success -> _state.update { st ->
                    st.copy(notifications = st.notifications.map {
                        if (it.id == notificationId) it.copy(read = 1) else it
                    })
                }
                is ApiResult.Error -> Unit
            }
        }
    }

    fun confirmSelfPickup(code: String, successMessage: String) {
        viewModelScope.launch {
            val shopId = repo.currentShopId() ?: return@launch
            _state.update { it.copy(confirmingPickup = true) }
            when (val res = repo.confirmSelfPickup(shopId, code)) {
                is ApiResult.Success -> _state.update {
                    it.copy(confirmingPickup = false, message = "$successMessage #${res.data.ticketId ?: ""}".trim())
                }
                is ApiResult.Error -> _state.update { it.copy(confirmingPickup = false, message = res.message) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
