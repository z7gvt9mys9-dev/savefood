package ru.savefood.app.feature.shop.receipts

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.savefood.app.core.common.ApiResult
import ru.savefood.app.feature.shop.data.EsgReportDto
import ru.savefood.app.feature.shop.data.ReceiptConfirmDto
import ru.savefood.app.feature.shop.data.ReceiptDto
import ru.savefood.app.feature.shop.data.ReceiptLotDraftDto
import ru.savefood.app.feature.shop.data.ShopRepository
import javax.inject.Inject
import javax.inject.Named

/** The ESG section is exactly one of these — encodes the four mutually exclusive
 *  outcomes as a type so no contradictory flag combination is representable. */
sealed interface EsgState {
    data object Loading : EsgState
    data object Locked : EsgState               // plan does not include ESG (403)
    data object Error : EsgState                // failed to load (network / 5xx)
    data class Loaded(val report: EsgReportDto) : EsgState
}

data class ReceiptsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val shopId: Int? = null,
    val receipts: List<ReceiptDto> = emptyList(),
    val esg: EsgState = EsgState.Loading,
    val uploading: Boolean = false,
    val busyReceiptId: Int? = null,
    val exporting: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ReceiptsViewModel @Inject constructor(
    private val repo: ShopRepository,
    @ApplicationContext private val context: Context,
    @Named("authImageLoader") val imageLoader: ImageLoader,
) : ViewModel() {

    private val _state = MutableStateFlow(ReceiptsUiState())
    val state: StateFlow<ReceiptsUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val shopId = repo.currentShopId() ?: run {
                _state.update { it.copy(loading = false, error = "Нет сессии") }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null, shopId = shopId, esg = EsgState.Loading) }
            when (val res = repo.getReceipts(shopId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, receipts = res.data) }
                is ApiResult.Error -> _state.update { it.copy(loading = false, error = res.message) }
            }
            when (val esg = repo.getEsg(shopId)) {
                is ApiResult.Success -> _state.update { it.copy(esg = EsgState.Loaded(esg.data)) }
                // 403 = not in plan (a real, expected state). Anything else is a load
                // failure and must read differently than "ESG unavailable on your plan".
                is ApiResult.Error -> {
                    if (esg.code != 403) Log.w(TAG, "getEsg failed: ${esg.message}")
                    _state.update { it.copy(esg = if (esg.code == 403) EsgState.Locked else EsgState.Error) }
                }
            }
        }
    }

    fun uploadReceipt(uri: Uri) {
        viewModelScope.launch {
            val shopId = repo.currentShopId() ?: return@launch
            _state.update { it.copy(uploading = true) }
            when (val res = repo.uploadReceipt(shopId, uri)) {
                is ApiResult.Success -> _state.update { st ->
                    st.copy(uploading = false, receipts = listOf(res.data) + st.receipts)
                }
                is ApiResult.Error -> _state.update { it.copy(uploading = false, message = res.message) }
            }
        }
    }

    fun confirmReceipt(
        receiptId: Int,
        lots: List<ReceiptLotDraftDto>,
        expiryDate: String?,
        address: String?,
        timeSlot: String?,
        confirmedMessage: String,
        onDone: () -> Unit,
    ) {
        viewModelScope.launch {
            val shopId = repo.currentShopId() ?: return@launch
            _state.update { it.copy(busyReceiptId = receiptId) }
            val body = ReceiptConfirmDto(
                lots = lots,
                expiryDate = expiryDate?.ifBlank { null },
                address = address?.ifBlank { null },
                timeSlot = timeSlot?.ifBlank { null },
            )
            when (val res = repo.confirmReceipt(shopId, receiptId, body)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(busyReceiptId = null, message = confirmedMessage) }
                    onDone()
                    load()
                }
                is ApiResult.Error -> _state.update { it.copy(busyReceiptId = null, message = res.message) }
            }
        }
    }

    /** Downloads the ESG CSV and writes it to the user-picked [target] document. */
    fun exportCsv(target: Uri, savedMessage: String, failedMessage: String) {
        viewModelScope.launch {
            val shopId = repo.currentShopId() ?: return@launch
            _state.update { it.copy(exporting = true) }
            when (val res = repo.downloadEsgCsv(shopId)) {
                is ApiResult.Success -> {
                    val writeError = runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(target)?.use { it.write(res.data) }
                                ?: error("no output stream for $target")
                        }
                    }.exceptionOrNull()
                    if (writeError != null) Log.w(TAG, "ESG CSV write failed", writeError)
                    _state.update {
                        it.copy(exporting = false, message = if (writeError == null) savedMessage else failedMessage)
                    }
                }
                is ApiResult.Error -> _state.update { it.copy(exporting = false, message = res.message) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        private const val TAG = "ReceiptsVM"
    }
}
