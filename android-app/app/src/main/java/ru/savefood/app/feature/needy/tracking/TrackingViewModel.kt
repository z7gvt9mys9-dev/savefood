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
import ru.savefood.app.feature.needy.data.NeedyRepository
import ru.savefood.app.feature.needy.data.TicketDto
import ru.savefood.app.feature.needy.data.VolunteerLocationDto
import javax.inject.Inject

data class TrackingUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** A background refresh (tickets or courier location) failed while data was
     *  already on screen; the shown data may be outdated. */
    val stale: Boolean = false,
    val tickets: List<TicketDto> = emptyList(),
    val volunteerLocation: VolunteerLocationDto? = null,
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
            _state.update { it.copy(loading = false, error = "Нет сессии") }
            return
        }
        if (showSpinner) _state.update { it.copy(loading = true) }
        when (val res = repo.getTickets(needyId)) {
            is ApiResult.Success -> {
                val active = res.data.activeOnly()
                _state.update { it.copy(loading = false, error = null, stale = false, tickets = active) }
                // Poll the live location of the first assigned ticket's courier.
                val assigned = active.firstOrNull { it.assignedVolunteerId != null }
                val volId = assigned?.assignedVolunteerId
                if (volId != null) {
                    when (val loc = repo.getVolunteerLocation(volId)) {
                        is ApiResult.Success -> _state.update { it.copy(volunteerLocation = loc.data, stale = false) }
                        // Keep the last known courier position rather than dropping it off
                        // the map (which reads as "courier disappeared"); flag the data as
                        // possibly outdated so the UI can say so.
                        is ApiResult.Error -> _state.update { it.copy(stale = true) }
                    }
                } else {
                    // No assigned courier: clear the pin AND the stale flag, otherwise a
                    // prior location-poll failure leaves the banner stuck on forever.
                    _state.update { it.copy(volunteerLocation = null, stale = false) }
                }
            }
            // First load with nothing on screen → surface the error state. A failed
            // background poll while tickets are shown keeps the data but marks it stale,
            // so the recipient is never silently looking at frozen delivery status.
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
