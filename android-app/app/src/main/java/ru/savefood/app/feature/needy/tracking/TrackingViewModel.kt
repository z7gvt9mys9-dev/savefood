package ru.savefood.app.feature.needy.tracking
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.savefood.app.core.common.ApiResult
import ru.savefood.app.core.common.AppStrings
import ru.savefood.app.R
import ru.savefood.app.feature.needy.data.NeedyRepository
import ru.savefood.app.feature.needy.data.TicketDto
import ru.savefood.app.feature.needy.data.VolunteerLocationDto
import ru.savefood.app.feature.needy.data.DeliveryAvailabilityDto
import javax.inject.Inject
data class TrackingUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val stale: Boolean = false,
    val tickets: List<TicketDto> = emptyList(),
    val volunteerLocation: VolunteerLocationDto? = null,
    val deliveryAvailability: DeliveryAvailabilityDto? = null,
    val cancellingTicketId: Int? = null,
)
@HiltViewModel
class TrackingViewModel @Inject constructor(
    private val repo: NeedyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TrackingUiState())
    val state: StateFlow<TrackingUiState> = _state.asStateFlow()
    private var pollJob: Job? = null
    /** Active (non-terminal) tickets are the ones we track. */
    private fun List<TicketDto>.activeOnly() =
        filter { it.status == "open" || it.status == "assigned" }
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            var first = true
            while (isActive) {
                refreshOnce(showSpinner = first)
                first = false
                delay(POLL_INTERVAL_MS)
            }
        }
    }
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }
    fun retry() {
        viewModelScope.launch { refreshOnce(showSpinner = true) }
    }
    private suspend fun refreshOnce(showSpinner: Boolean) {
        val needyId = repo.currentNeedyId()
        if (needyId == null) {
            _state.update { it.copy(loading = false, error = AppStrings.get(R.string.common_error_no_session)) }
            return
        }
        if (showSpinner) _state.update { it.copy(loading = true) }
        when (val res = repo.getTickets(needyId)) {
            is ApiResult.Success -> {
                val active = res.data.activeOnly()
                _state.update { it.copy(loading = false, error = null, stale = false, tickets = active) }
                val assigned = active.firstOrNull { it.assignedVolunteerId != null }
                val volId = assigned?.assignedVolunteerId
                if (volId != null) {
                    _state.update { it.copy(deliveryAvailability = null) }
                    when (val loc = repo.getVolunteerLocation(volId)) {
                        is ApiResult.Success -> _state.update {
                            it.copy(volunteerLocation = loc.data, deliveryAvailability = null, stale = false)
                        }
                        is ApiResult.Error -> _state.update { it.copy(stale = true) }
                    }
                } else {
                    _state.update {
                        it.copy(volunteerLocation = null, deliveryAvailability = null, stale = false)
                    }
                    val waiting = active.firstOrNull {
                        it.status == "open" && it.selfPickup != true && it.assignedVolunteerId == null
                    }
                    if (waiting != null) {
                        when (val capacity = repo.getDeliveryAvailability(needyId, waiting.id)) {
                            is ApiResult.Success -> _state.update {
                                it.copy(deliveryAvailability = capacity.data)
                            }
                            is ApiResult.Error -> Unit
                        }
                    } else {
                        _state.update { it.copy(deliveryAvailability = null) }
                    }
                }
            }
            is ApiResult.Error -> _state.update {
                if (it.tickets.isEmpty()) it.copy(loading = false, error = res.message)
                else it.copy(loading = false, stale = true)
            }
        }
    }
    fun cancelTicket(ticketId: Int, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val needyId = repo.currentNeedyId() ?: return@launch
            _state.update { it.copy(cancellingTicketId = ticketId) }
            val res = repo.deleteTicket(needyId, ticketId)
            _state.update { it.copy(cancellingTicketId = null) }
            if (res is ApiResult.Success) {
                refreshOnce(showSpinner = false)
                onDone()
            } else if (res is ApiResult.Error) {
                _state.update { it.copy(error = res.message) }
            }
        }
    }
    fun rateTicket(ticketId: Int, rating: Int, comment: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val needyId = repo.currentNeedyId() ?: return@launch
            val res = repo.rateTicket(needyId, ticketId, rating, comment)
            if (res is ApiResult.Success) {
                refreshOnce(showSpinner = false)
                onDone()
            } else if (res is ApiResult.Error) {
                _state.update { it.copy(error = res.message) }
            }
        }
    }
    override fun onCleared() {
        stop()
        super.onCleared()
    }
    companion object {
        private const val POLL_INTERVAL_MS = 8_000L
    }
}
