package kz.savefood.app.feature.shop.receipts

import android.content.Context
import android.net.Uri
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
import kz.savefood.app.core.common.ApiResult
import kz.savefood.app.feature.shop.data.EsgReportDto
import kz.savefood.app.feature.shop.data.ReceiptConfirmDto
import kz.savefood.app.feature.shop.data.ReceiptDto
import kz.savefood.app.feature.shop.data.ReceiptLotDraftDto
import kz.savefood.app.feature.shop.data.ShopRepository
import javax.inject.Inject
import javax.inject.Named

data class ReceiptsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val shopId: Int? = null,
    val receipts: List<ReceiptDto> = emptyList(),
    val esg: EsgReportDto? = null,
    val esgLocked: Boolean = false, // plan does not include ESG (403)
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
            _state.update { it.copy(loading = true, error = null, shopId = shopId) }
            when (val res = repo.getReceipts(shopId)) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, receipts = res.data) }
                is ApiResult.Error -> _state.update { it.copy(loading = false, error = res.message) }
            }
            when (val esg = repo.getEsg(shopId)) {
                is ApiResult.Success -> _state.update { it.copy(esg = esg.data, esgLocked = false) }
                is ApiResult.Error -> _state.update { it.copy(esgLocked = esg.code == 403, esg = null) }
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
    fun exportCsv(target: Uri, savedMessage: String) {
        viewModelScope.launch {
            val shopId = repo.currentShopId() ?: return@launch
            _state.update { it.copy(exporting = true) }
            when (val res = repo.downloadEsgCsv(shopId)) {
                is ApiResult.Success -> {
                    val ok = runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(target)?.use { it.write(res.data) }
                                ?: error("no stream")
                        }
                    }.isSuccess
                    _state.update { it.copy(exporting = false, message = if (ok) savedMessage else "Не удалось сохранить файл") }
                }
                is ApiResult.Error -> _state.update { it.copy(exporting = false, message = res.message) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
