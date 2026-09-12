package ru.savefood.app.feature.shop.create
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.savefood.app.core.address.AddressSuggestionService
import ru.savefood.app.core.common.ApiResult
import ru.savefood.app.core.common.AppStrings
import ru.savefood.app.R
import ru.savefood.app.feature.shop.data.LotCreateDto
import ru.savefood.app.feature.shop.data.ShopRepository
import javax.inject.Inject
data class CreateLotForm(
    val description: String = "",
    val quantity: String = "",
    val unit: String = "кг",
    val unitWeightKg: String = "",
    val category: String = "",
    val address: String = "",
    val timeSlot: String = "",
    val expiryDate: String = "",
    val comment: String = "",
    val requiresCold: Boolean = false,
)
data class CreateLotUiState(
    val step: Int = 0,
    val form: CreateLotForm = CreateLotForm(),
    val photoUri: Uri? = null,
    val submitting: Boolean = false,
    val error: String? = null,
    val createdId: Int? = null,
    val addressSuggestions: List<String> = emptyList(),
) {
    /** True when the kg-unit weight rule is satisfied and the basics are present. */
    val canContinue: Boolean
        get() = form.description.isNotBlank() &&
            form.quantity.toIntOrNull()?.let { it >= 1 } == true &&
            form.category.isNotBlank() &&
            (form.unit == "кг" || (form.unitWeightKg.toDoubleOrNull()?.let { it > 0 } == true))
}
@HiltViewModel
class CreateLotViewModel @Inject constructor(
    private val repo: ShopRepository,
    private val addressSuggestionService: AddressSuggestionService,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateLotUiState())
    val state: StateFlow<CreateLotUiState> = _state.asStateFlow()
    private var addressSuggestionJob: Job? = null
    fun updateForm(transform: (CreateLotForm) -> CreateLotForm) =
        _state.update { it.copy(form = transform(it.form)) }
    fun setStep(step: Int) = _state.update { it.copy(step = step) }
    fun setPhoto(uri: Uri?) = _state.update { it.copy(photoUri = uri) }
    fun updateAddress(address: String) {
        _state.update { it.copy(form = it.form.copy(address = address), addressSuggestions = emptyList()) }
        addressSuggestionJob?.cancel()
        if (address.trim().length < 3) return
        addressSuggestionJob = viewModelScope.launch {
            delay(300)
            val requestedAddress = address.trim()
            val suggestions = addressSuggestionService.suggest(requestedAddress)
            _state.update { current ->
                if (current.form.address.trim() == requestedAddress) {
                    current.copy(addressSuggestions = suggestions)
                } else {
                    current
                }
            }
        }
    }
    fun selectAddress(address: String) {
        addressSuggestionJob?.cancel()
        _state.update { it.copy(form = it.form.copy(address = address), addressSuggestions = emptyList()) }
    }
    fun reset() = _state.update { CreateLotUiState() }
    fun clearError() = _state.update { it.copy(error = null) }
    fun submit() {
        val s = _state.value
        if (!s.canContinue || s.submitting) return
        viewModelScope.launch {
            val shopId = repo.currentShopId() ?: run {
                _state.update { it.copy(error = AppStrings.get(R.string.common_error_no_session)) }
                return@launch
            }
            _state.update { it.copy(submitting = true, error = null) }
            val unit = s.form.unit.ifBlank { "кг" }
            val body = LotCreateDto(
                description = s.form.description.trim(),
                quantity = s.form.quantity.toInt(),
                unit = unit,
                unitWeightKg = if (unit == "кг") 1.0 else (s.form.unitWeightKg.toDoubleOrNull() ?: 1.0),
                expiryDate = s.form.expiryDate.trim().ifBlank { null },
                address = s.form.address.trim().ifBlank { null },
                timeSlot = s.form.timeSlot.trim().ifBlank { null },
                category = s.form.category.trim().ifBlank { null },
                comment = s.form.comment.trim().ifBlank { null },
                requiresCold = s.form.requiresCold,
            )
            val res = if (s.photoUri != null) {
                repo.createLotWithPhoto(shopId, body, s.photoUri)
            } else {
                repo.createLot(shopId, body)
            }
            when (res) {
                is ApiResult.Success -> _state.update { it.copy(submitting = false, createdId = res.data) }
                is ApiResult.Error -> _state.update { it.copy(submitting = false, error = res.message) }
            }
        }
    }
}
