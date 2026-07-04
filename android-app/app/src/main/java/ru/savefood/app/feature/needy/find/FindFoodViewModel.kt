package ru.savefood.app.feature.needy.find

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.savefood.app.core.common.ApiResult
import ru.savefood.app.core.device.location.LocationProvider
import ru.savefood.app.feature.needy.data.LotDto
import ru.savefood.app.feature.needy.data.NeedyRepository
import ru.savefood.app.feature.needy.data.TicketCreateDto
import javax.inject.Inject

data class FindFoodUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val lots: List<LotDto> = emptyList(),
    val search: String = "",
    val submitting: Boolean = false,
    val submitError: String? = null,
)

@HiltViewModel
class FindFoodViewModel @Inject constructor(
    private val repo: NeedyRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(FindFoodUiState())
    val state: StateFlow<FindFoodUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val res = repo.getLots(search = _state.value.search.takeIf { it.isNotBlank() })) {
                is ApiResult.Success -> _state.update {
                    it.copy(loading = false, lots = res.data.filter { l -> l.status == "active" })
                }
                is ApiResult.Error -> _state.update { it.copy(loading = false, error = res.message) }
            }
        }
    }

    fun onSearchChange(value: String) {
        _state.update { it.copy(search = value) }
    }

    fun lotById(id: Int?): LotDto? = _state.value.lots.firstOrNull { it.id == id }

    /** Submits a ticket; invokes [onSuccess] on the created ticket id. */
    fun submitTicket(body: TicketCreateDto, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val needyId = repo.currentNeedyId()
            if (needyId == null) {
                _state.update { it.copy(submitError = "Нет сессии") }
                return@launch
            }
            _state.update { it.copy(submitting = true, submitError = null) }
            when (val res = repo.createTicket(needyId, body)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(submitting = false) }
                    onSuccess()
                }
                is ApiResult.Error -> _state.update { it.copy(submitting = false, submitError = res.message) }
            }
        }
    }

    fun clearSubmitError() = _state.update { it.copy(submitError = null) }

    /** One-shot current location for the wizard's "use my location" button. */
    fun fetchMyLocation(onResult: (lat: Double, lon: Double) -> Unit) {
        viewModelScope.launch {
            val loc = locationProvider.lastLocation() ?: return@launch
            onResult(loc.latitude, loc.longitude)
        }
    }
}
