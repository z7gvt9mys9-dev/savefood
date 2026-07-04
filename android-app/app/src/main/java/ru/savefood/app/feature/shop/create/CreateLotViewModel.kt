package ru.savefood.app.feature.shop.create

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
) {
    /** True when the kg-unit weight rule is satisfied and the basics are present. */
    val canContinue: Boolean
        get() = form.description.isNotBlank() &&
            form.quantity.toDoubleOrNull()?.let { it > 0 } == true &&
            (form.unit == "кг" || (form.unitWeightKg.toDoubleOrNull()?.let { it > 0 } == true))
}

@HiltViewModel
class CreateLotViewModel @Inject constructor(
    private val repo: ShopRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CreateLotUiState())
    val state: StateFlow<CreateLotUiState> = _state.asStateFlow()

    fun updateForm(transform: (CreateLotForm) -> CreateLotForm) =
        _state.update { it.copy(form = transform(it.form)) }

    fun setStep(step: Int) = _state.update { it.copy(step = step) }

    fun setPhoto(uri: Uri?) = _state.update { it.copy(photoUri = uri) }

    fun reset() = _state.update { CreateLotUiState() }

    fun clearError() = _state.update { it.copy(error = null) }

    fun submit() {
        val s = _state.value
        if (!s.canContinue || s.submitting) return
        viewModelScope.launch {
            val shopId = repo.currentShopId() ?: run {
                _state.update { it.copy(error = "Нет сессии") }
                return@launch
            }
            _state.update { it.copy(submitting = true, error = null) }
            val unit = s.form.unit.ifBlank { "кг" }
            val body = LotCreateDto(
                description = s.form.description.trim(),
                quantity = s.form.quantity.toDouble(),
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
