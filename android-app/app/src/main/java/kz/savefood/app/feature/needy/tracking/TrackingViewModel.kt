package kz.savefood.app.feature.needy.tracking

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
import kz.savefood.app.core.common.ApiResult
import kz.savefood.app.feature.needy.data.NeedyRepository
import kz.savefood.app.feature.needy.data.TicketDto
import kz.savefood.app.feature.needy.data.VolunteerLocationDto
import javax.inject.Inject

data class TrackingUiState(
    val loading: Boolean = true,
    val error: String? = null,
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
                _state.update { it.copy(loading = false, error = null, tickets = active) }
                // Poll the live location of the first assigned ticket's courier.
                val assigned = active.firstOrNull { it.assignedVolunteerId != null }
                val volId = assigned?.assignedVolunteerId
                if (volId != null) {
                    when (val loc = repo.getVolunteerLocation(volId)) {
                        is ApiResult.Success -> _state.update { it.copy(volunteerLocation = loc.data) }
                        is ApiResult.Error -> _state.update { it.copy(volunteerLocation = null) }
                    }
                } else {
                    _state.update { it.copy(volunteerLocation = null) }
                }
            }
            is ApiResult.Error -> _state.update {
                it.copy(loading = false, error = if (it.tickets.isEmpty()) res.message else it.error)
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
