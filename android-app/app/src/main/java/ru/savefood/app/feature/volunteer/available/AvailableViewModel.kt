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
            // The volunteer map is deliberately narrower than the public /lots
            // catalogue: it contains only lots for which a recipient requested
            // delivery.  Do not fetch /lots here, or unrequested lots reappear.
            when (val mapRes = repo.getMap()) {
                is ApiResult.Success -> {
                    val map = mapRes.data
                    _state.update {
                        it.copy(
                            loading = false,
                            lots = mapToDeliveryLots(map),
                            openTickets = map.tickets,
                        )
                    }
                }
                is ApiResult.Error -> _state.update { it.copy(loading = false, error = mapRes.message) }
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

    /** Convert delivery-only map cards into the list displayed to volunteers. */
    private fun mapToDeliveryLots(map: VolunteerMapDto): List<LotDto> {
        val query = _state.value.search.trim().lowercase()
        return map.shops.flatMap { shop ->
            shop.lots.mapNotNull { lot ->
                if (lot.routeAvailable == false) return@mapNotNull null
                val title = lot.description ?: return@mapNotNull null
                val matchesSearch = query.isBlank() || title.lowercase().contains(query) ||
                    (shop.name?.lowercase()?.contains(query) == true)
                if (!matchesSearch) return@mapNotNull null
                LotDto(
                    id = lot.lotId,
                    shopId = shop.shopId,
                    description = title,
                    quantity = lot.quantity,
                    photo = lot.photo,
                    address = shop.name,
                    status = lot.status ?: "reserved",
                    category = lot.category,
                    shopName = shop.name,
                    shopLat = shop.lat,
                    shopLon = shop.lon,
                )
            }
        }
    }
}
