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
import ru.savefood.app.feature.volunteer.data.VolunteerMapDto
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
                        is ApiResult.Success -> {
                            val map = mapRes.data
                            _state.update {
                                it.copy(
                                    lots = mergeReservedLots(active, map),
                                    openTickets = map.tickets,
                                )
                            }
                        }
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

    /**
     * Reserving the final unit sets its public quantity to zero, so /lots quite
     * correctly omits it.  The authenticated volunteer map carries a minimal
     * ticket/lot card for that case; merge it into the actionable list rather
     * than leaving a passive marker that cannot start a route.
     */
    private fun mergeReservedLots(active: List<LotDto>, map: VolunteerMapDto): List<LotDto> {
        val existingIds = active.mapTo(mutableSetOf()) { it.id }
        val query = _state.value.search.trim().lowercase()
        val reserved = map.tickets.mapNotNull { ticket ->
            val lotId = ticket.lotId ?: return@mapNotNull null
            if (lotId in existingIds || ticket.routeAvailable == false) return@mapNotNull null
            val title = ticket.lotDescription ?: ticket.items ?: return@mapNotNull null
            val matchesSearch = query.isBlank() || title.lowercase().contains(query) ||
                (ticket.shopName?.lowercase()?.contains(query) == true)
            if (!matchesSearch) return@mapNotNull null
            existingIds += lotId
            LotDto(
                id = lotId,
                shopId = ticket.shopId ?: 0,
                description = title,
                quantity = ticket.lotQuantity ?: 0.0,
                photo = ticket.lotPhoto,
                address = ticket.shopName,
                status = "reserved",
                category = ticket.lotCategory,
                shopName = ticket.shopName,
                shopLat = ticket.shopLat,
                shopLon = ticket.shopLon,
            )
        }
        return active + reserved
    }
}
