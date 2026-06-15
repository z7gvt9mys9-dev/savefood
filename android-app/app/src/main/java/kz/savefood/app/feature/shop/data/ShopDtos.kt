package kz.savefood.app.feature.shop.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Shop profile (GET/PATCH /shops/{id} → ShopOut) ───────────────────────────

@Serializable
data class ShopDto(
    val id: Int,
    val name: String,
    val contact: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val city: String? = null,
    val kind: String? = "business",
)

/** Body for PATCH /shops/{id} (ShopUpdate). */
@Serializable
data class ShopUpdateDto(
    val name: String? = null,
    val contact: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val city: String? = null,
)

// ── Lots (shape mirrors backend LotOut) ──────────────────────────────────────

@Serializable
data class LotDto(
    val id: Int,
    @SerialName("shop_id") val shopId: Int,
    val description: String? = null,
    val quantity: Double? = null,
    @SerialName("initial_quantity") val initialQuantity: Double? = null,
    val unit: String? = "кг",
    @SerialName("unit_weight_kg") val unitWeightKg: Double? = 1.0,
    @SerialName("expiry_date") val expiryDate: String? = null,
    val photo: String? = null,
    val address: String? = null,
    @SerialName("time_slot") val timeSlot: String? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("taken_at") val takenAt: String? = null,
    @SerialName("taken_by") val takenBy: String? = null,
    val category: String? = null,
    val comment: String? = null,
    @SerialName("requires_cold") val requiresCold: Boolean? = false,
)

/** Body for POST /shops/{id}/lots (LotCreate). */
@Serializable
data class LotCreateDto(
    val description: String,
    val quantity: Double,
    val unit: String = "кг",
    @SerialName("unit_weight_kg") val unitWeightKg: Double = 1.0,
    @SerialName("expiry_date") val expiryDate: String? = null,
    val photo: String? = null,
    val address: String? = null,
    @SerialName("time_slot") val timeSlot: String? = null,
    val category: String? = null,
    val comment: String? = null,
    @SerialName("requires_cold") val requiresCold: Boolean? = false,
)

/** Body for PATCH /lots/{lotId} (LotUpdate). All fields optional. */
@Serializable
data class LotUpdateDto(
    val description: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    @SerialName("unit_weight_kg") val unitWeightKg: Double? = null,
    @SerialName("expiry_date") val expiryDate: String? = null,
    val address: String? = null,
    val category: String? = null,
    val comment: String? = null,
    @SerialName("requires_cold") val requiresCold: Boolean? = null,
)

@Serializable
data class IdDto(val id: Int)

@Serializable
data class OkDto(val ok: Boolean = false)

// ── Notifications (GET /shops/{id}/notifications) ────────────────────────────

@Serializable
data class NotificationDto(
    val id: Int,
    @SerialName("shop_id") val shopId: Int,
    @SerialName("lot_id") val lotId: Int? = null,
    val type: String? = null,
    val payload: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val read: Int = 0,
)

// ── Receipts / OCR (GET/POST /shops/{id}/receipts) ───────────────────────────

@Serializable
data class ReceiptItemDto(
    @SerialName("raw_name") val rawName: String,
    val name: String,
    val category: String,
    val quantity: Double? = null,
    val unit: String? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
)

/** One editable lot draft grouped from receipt items (ReceiptLotDraft). */
@Serializable
data class ReceiptLotDraftDto(
    val description: String,
    val quantity: Int,
    val category: String,
)

@Serializable
data class ReceiptDto(
    val id: Int,
    val status: String, // parsed | rejected | confirmed
    val merchant: String? = null,
    @SerialName("receipt_date") val receiptDate: String? = null,
    val total: Double? = null,
    val currency: String? = null,
    val items: List<ReceiptItemDto> = emptyList(),
    @SerialName("fraud_score") val fraudScore: Double? = null,
    @SerialName("fraud_reasons") val fraudReasons: String? = null,
    @SerialName("fraud_flagged") val fraudFlagged: Boolean = false,
    @SerialName("suggested_lots") val suggestedLots: List<ReceiptLotDraftDto> = emptyList(),
    @SerialName("lot_ids") val lotIds: List<Int> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
)

/** Body for POST /shops/{id}/receipts/{receiptId}/confirm (ReceiptConfirm). */
@Serializable
data class ReceiptConfirmDto(
    val lots: List<ReceiptLotDraftDto>,
    @SerialName("expiry_date") val expiryDate: String? = null,
    val address: String? = null,
    @SerialName("time_slot") val timeSlot: String? = null,
)

// ── ESG (GET /shops/{id}/esg) ────────────────────────────────────────────────

@Serializable
data class EsgTotalsDto(
    val kg: Double = 0.0,
    @SerialName("co2_kg") val co2Kg: Double = 0.0,
    val meals: Int = 0,
    val lots: Int = 0,
)

@Serializable
data class EsgCategoryDto(
    val category: String,
    val kg: Double = 0.0,
    @SerialName("co2_kg") val co2Kg: Double = 0.0,
    val lots: Int = 0,
)

@Serializable
data class EsgMonthDto(
    val month: String,
    val kg: Double = 0.0,
    @SerialName("co2_kg") val co2Kg: Double = 0.0,
)

@Serializable
data class EsgReportDto(
    val methodology: String? = null,
    @SerialName("period_months") val periodMonths: Int = 12,
    val totals: EsgTotalsDto = EsgTotalsDto(),
    @SerialName("by_category") val byCategory: List<EsgCategoryDto> = emptyList(),
    @SerialName("by_month") val byMonth: List<EsgMonthDto> = emptyList(),
)

// ── Plan / billing (GET /shops/{id}/plan) ────────────────────────────────────

@Serializable
data class PlanDto(
    val plan: String,
    val label: String,
    val ocr: Boolean = false,
    val esg: Boolean = false,
    val api: Boolean = false,
    @SerialName("monthly_lot_limit") val monthlyLotLimit: Int? = null,
    @SerialName("lots_used_this_month") val lotsUsedThisMonth: Int = 0,
)

// ── Self-pickup (POST /shops/{id}/self_pickup/confirm) ───────────────────────

/** Body for self_pickup/confirm — the recipient's QR payload (SF-{id}-{secret}). */
@Serializable
data class SelfPickupConfirmDto(val code: String)

@Serializable
data class SelfPickupResultDto(
    val ok: Boolean = false,
    @SerialName("ticket_id") val ticketId: Int? = null,
)
