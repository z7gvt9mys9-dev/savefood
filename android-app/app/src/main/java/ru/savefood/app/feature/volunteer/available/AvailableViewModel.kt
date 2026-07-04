package ru.savefood.app.feature.volunteer.available

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.savefood.app.core.common.ApiResult
import ru.savefood.app.feature.volunteer.data.LotDto
import ru.savefood.app.feature.volunteer.data.MapTicketDto
import ru.savefood.app.feature.volunteer.data.VolunteerRepository
import javax.inject.Inject

data class AvailableUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val lots: List<LotDto> = emptyList(),
    val openTickets: List<MapTicketDto> = emptyList(),
    val search: String = "",
    val startingLotId: Int? = null,
    val startError: String? = null,
)

@HiltViewModel
class AvailableViewModel @Inject constructor(
    private val repo: VolunteerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AvailableUiState())
    val state: StateFlow<AvailableUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val search = _state.value.search.takeIf { it.isNotBlank() }
            // Lots carry the rich card data; map adds the open delivery requests overlay.
            when (val lotsRes = repo.getLots(search = search)) {
                is ApiResult.Success -> {
                    val active = lotsRes.data.filter { it.status == "active" }
                    _state.update { it.copy(loading = false, lots = active) }
                    when (val mapRes = repo.getMap()) {
                        is ApiResult.Success -> _state.update { it.copy(openTickets = mapRes.data.tickets) }
                        is ApiResult.Error -> Unit // map overlay is best-effort; lots already shown
                    }
                }
                is ApiResult.Error -> _state.update { it.copy(loading = false, error = lotsRes.message) }
            }
        }
    }

    fun onSearchChange(value: String) = _state.update { it.copy(search = value) }

    fun lotById(id: Int?): LotDto? = _state.value.lots.firstOrNull { it.id == id }

    /** Claims [lotId] and starts the route. [onStarted] fires on success. */
    fun startRoute(lotId: Int, onStarted: (routeId: Int) -> Unit) {
        viewModelScope.launch {
            val volunteerId = repo.currentVolunteerId()
            if (volunteerId == null) {
                _state.update { it.copy(startError = "Нет сессии") }
                return@launch
            }
            _state.update { it.copy(startingLotId = lotId, startError = null) }
            when (val res = repo.startRoute(volunteerId, lotId)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(startingLotId = null) }
                    onStarted(res.data.routeId)
                }
                is ApiResult.Error -> _state.update {
                    it.copy(startingLotId = null, startError = res.message)
                }
            }
        }
    }

    fun clearStartError() = _state.update { it.copy(startError = null) }
}
