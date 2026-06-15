package kz.savefood.app.feature.shop.lots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kz.savefood.app.core.common.ApiResult
import kz.savefood.app.feature.shop.data.LotDto
import kz.savefood.app.feature.shop.data.LotUpdateDto
import kz.savefood.app.feature.shop.data.ShopRepository
import javax.inject.Inject

data class LotsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val lots: List<LotDto> = emptyList(),
    val history: List<LotDto> = emptyList(),
    val historyLoading: Boolean = false,
    val busyLotId: Int? = null,
    val message: String? = null,
)

@HiltViewModel
class LotsViewModel @Inject constructor(
    private val repo: ShopRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LotsUiState())
    val state: StateFlow<LotsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val shopId = repo.currentShopId() ?: run {
                _state.update { it.copy(loading = false, error = "Нет сессии") }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            when (val res = repo.getLots(shopId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, lots = res.data) }
                is ApiResult.Error -> _state.update { it.copy(loading = false, error = res.message) }
            }
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            val shopId = repo.currentShopId() ?: return@launch
            _state.update { it.copy(historyLoading = true) }
            when (val res = repo.getHistory(shopId)) {
                is ApiResult.Success -> _state.update { it.copy(historyLoading = false, history = res.data) }
                is ApiResult.Error -> _state.update { it.copy(historyLoading = false, message = res.message) }
            }
        }
    }

    fun saveEdit(lotId: Int, body: LotUpdateDto, savedMessage: String) {
        viewModelScope.launch {
            _state.update { it.copy(busyLotId = lotId) }
            when (val res = repo.patchLot(lotId, body)) {
                is ApiResult.Success -> {
                    _state.update { st ->
                        st.copy(
                            busyLotId = null,
                            message = savedMessage,
                            lots = st.lots.map { if (it.id == lotId) res.data else it },
                        )
                    }
                }
                is ApiResult.Error -> _state.update { it.copy(busyLotId = null, message = res.message) }
            }
        }
    }

    fun deleteLot(lotId: Int, deletedMessage: String) {
        viewModelScope.launch {
            _state.update { it.copy(busyLotId = lotId) }
            when (val res = repo.deleteLot(lotId)) {
                is ApiResult.Success -> _state.update { st ->
                    st.copy(busyLotId = null, message = deletedMessage, lots = st.lots.filterNot { it.id == lotId })
                }
                is ApiResult.Error -> _state.update { it.copy(busyLotId = null, message = res.message) }
            }
        }
    }

    fun confirmTransfer(lotId: Int, confirmedMessage: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busyLotId = lotId) }
            when (val res = repo.confirmTransfer(lotId)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(busyLotId = null, message = confirmedMessage) }
                    onDone()
                    load()
                }
                is ApiResult.Error -> _state.update { it.copy(busyLotId = null, message = res.message) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
