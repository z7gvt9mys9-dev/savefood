package ru.savefood.app.feature.needy.history

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.savefood.app.core.common.ApiResult
import ru.savefood.app.feature.needy.data.NeedyRepository
import ru.savefood.app.feature.needy.data.TicketDto
import javax.inject.Inject

data class HistoryUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<TicketDto> = emptyList(),
    val busyTicketId: Int? = null,
    val message: String? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repo: NeedyRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val needyId = repo.currentNeedyId() ?: run {
                _state.update { it.copy(loading = false, error = "Нет сессии") }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            when (val res = repo.getHistory(needyId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, items = res.data) }
                is ApiResult.Error -> _state.update { it.copy(loading = false, error = res.message) }
            }
        }
    }

    fun rate(ticketId: Int, rating: Int, comment: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val needyId = repo.currentNeedyId() ?: return@launch
            _state.update { it.copy(busyTicketId = ticketId) }
            val res = repo.rateTicket(needyId, ticketId, rating, comment)
            _state.update { it.copy(busyTicketId = null) }
            handleResult(res) { load(); onDone() }
        }
    }

    fun uploadPhoto(ticketId: Int, uri: Uri) {
        viewModelScope.launch {
            val needyId = repo.currentNeedyId() ?: return@launch
            _state.update { it.copy(busyTicketId = ticketId) }
            val res = repo.uploadImpactPhoto(needyId, ticketId, uri)
            _state.update { it.copy(busyTicketId = null) }
            handleResult(res) { load() }
        }
    }

    fun delete(ticketId: Int, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val needyId = repo.currentNeedyId() ?: return@launch
            _state.update { it.copy(busyTicketId = ticketId) }
            val res = repo.deleteTicket(needyId, ticketId)
            _state.update { it.copy(busyTicketId = null) }
            handleResult(res) { load(); onDone() }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun <T> handleResult(res: ApiResult<T>, onSuccess: () -> Unit) {
        when (res) {
            is ApiResult.Success -> onSuccess()
            is ApiResult.Error -> _state.update { it.copy(message = res.message) }
        }
    }
}
