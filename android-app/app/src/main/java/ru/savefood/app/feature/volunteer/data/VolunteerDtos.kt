package ru.savefood.app.feature.volunteer.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Map (GET /volunteers/map) ────────────────────────────────────────────────

/** Top-level payload of GET /volunteers/map. */
@Serializable
data class VolunteerMapDto(
    val shops: List<MapShopDto> = emptyList(),
    val tickets: List<MapTicketDto> = emptyList(),
)

/** A shop pin with its active lots. */
@Serializable
data class MapShopDto(
    @SerialName("shop_id") val shopId: Int,
    val name: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val kind: String? = null,
    val lots: List<MapLotDto> = emptyList(),
)

/** A single active lot attached to a shop pin. */
@Serializable
data class MapLotDto(
    @SerialName("lot_id") val lotId: Int,
    val description: String? = null,
    val quantity: Double? = null,
    val photo: String? = null,
    val category: String? = null,
)

/** An open delivery request (coordinates coarsened ~500m on the server). */
@Serializable
data class MapTicketDto(
    @SerialName("ticket_id") val ticketId: Int,
    @SerialName("needy_id") val needyId: Int? = null,
    val items: String? = null,
    @SerialName("available_time") val availableTime: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val approx: Boolean? = null,
)

// ── Lots (GET /lots) — shape mirrors backend LotOut ─────────────────────────

@Serializable
data class LotDto(
    val id: Int,
    @SerialName("shop_id") val shopId: Int,
    val description: String? = null,
    val quantity: Double? = null,
    val unit: String? = "кг",
    @SerialName("expiry_date") val expiryDate: String? = null,
    val photo: String? = null,
    val address: String? = null,
    @SerialName("time_slot") val timeSlot: String? = null,
    val status: String,
    val category: String? = null,
    val comment: String? = null,
    @SerialName("requires_cold") val requiresCold: Boolean? = false,
    @SerialName("shop_name") val shopName: String? = null,
    @SerialName("shop_lat") val shopLat: Double? = null,
    @SerialName("shop_lon") val shopLon: Double? = null,
)

// ── Routes ───────────────────────────────────────────────────────────────────

/** Body for POST /volunteers/{id}/start_route. */
@Serializable
data class StartRouteRequestDto(
    @SerialName("lot_id") val lotId: Int,
    @SerialName("max_stops") val maxStops: Int = 10,
)

/** Response of POST /volunteers/{id}/start_route. */
@Serializable
data class StartRouteResponseDto(
    @SerialName("route_id") val routeId: Int,
    val points: List<RoutePointDto> = emptyList(),
)

/** A point on the active route. Shop point has kind="shop", ticket_id=null. */
@Serializable
data class RoutePointDto(
    val kind: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val description: String? = null,
    @SerialName("ticket_id") val ticketId: Int? = null,
    val done: Boolean? = false,
    @SerialName("attempt_count") val attemptCount: Int? = null,
    val released: Boolean? = null,
    val apartment: String? = null,
    @SerialName("floor_num") val floorNum: String? = null,
    val entrance: String? = null,
    @SerialName("addr_detail") val addrDetail: String? = null,
)

/** The active route (GET /volunteers/{id}/active_route — {} when none). */
@Serializable
data class RouteDto(
    val id: Int? = null,
    @SerialName("volunteer_id") val volunteerId: Int? = null,
    @SerialName("lot_id") val lotId: Int? = null,
    val points: List<RoutePointDto> = emptyList(),
    val status: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
)

/** Body for complete_point / attempt_delivery (CompletePointRequest). */
@Serializable
data class CompletePointRequestDto(
    @SerialName("volunteer_id") val volunteerId: Int,
    @SerialName("ticket_id") val ticketId: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("qr_code") val qrCode: String? = null,
)

/** Body for POST /volunteers/route/{id}/finish (FinishRouteRequest). */
@Serializable
data class FinishRouteRequestDto(
    @SerialName("volunteer_id") val volunteerId: Int,
)

@Serializable
data class AttemptDeliveryResponseDto(
    val ok: Boolean = false,
    @SerialName("attempt_count") val attemptCount: Int? = null,
    val released: Boolean? = null,
)

@Serializable
data class OkDto(val ok: Boolean = false)

// ── Location ──────────────────────────────────────────────────────────────────

/** Body for PATCH /volunteers/{id}/location (LocationUpdate). */
@Serializable
data class LocationUpdateDto(
    val lat: Double,
    val lon: Double,
)

// ── Profile / KYC ─────────────────────────────────────────────────────────────

@Serializable
data class AvailabilityWindowDto(
    val day: Int,
    val start: String,
    val end: String,
)

/** Volunteer profile (GET /volunteers/{id} → VolunteerOut). */
@Serializable
data class VolunteerDto(
    val id: Int,
    val name: String,
    val contact: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val city: String? = null,
    @SerialName("has_thermal_bag") val hasThermalBag: Boolean? = false,
    val availability: List<AvailabilityWindowDto>? = null,
    val status: String? = "approved",
    @SerialName("kyc_score") val kycScore: Double? = null,
    @SerialName("kyc_verdict") val kycVerdict: String? = null,
    @SerialName("kyc_notes") val kycNotes: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Body for PATCH /volunteers/{id} (VolunteerUpdate). */
@Serializable
data class VolunteerUpdateDto(
    val name: String? = null,
    val contact: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val city: String? = null,
    @SerialName("has_thermal_bag") val hasThermalBag: Boolean? = null,
    val availability: List<AvailabilityWindowDto>? = null,
)

@Serializable
data class KycUploadResponseDto(
    val ok: Boolean = false,
    val status: String? = null,
)

// ── Rating / stats / thanks ───────────────────────────────────────────────────

/** GET /volunteers/{id}/rating. */
@Serializable
data class RatingDto(
    val average: Double? = null,
    val count: Int = 0,
)

/** GET /volunteers/{id}/stats. */
@Serializable
data class StatsDto(
    @SerialName("total_routes") val totalRoutes: Int = 0,
    @SerialName("total_deliveries") val totalDeliveries: Int = 0,
    @SerialName("total_kg") val totalKg: Double = 0.0,
    @SerialName("avg_rating") val avgRating: Double? = null,
    @SerialName("rating_count") val ratingCount: Int = 0,
    val achievements: List<String> = emptyList(),
    val level: Int = 0,
)

/** A thank-you note (GET /volunteers/{id}/thanks). */
@Serializable
data class ThanksDto(
    val rating: Int? = null,
    val comment: String? = null,
    val category: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

// ── Team ──────────────────────────────────────────────────────────────────────

/** GET /volunteers/{id}/team and team mutations → {"team": TeamDto|null}. */
@Serializable
data class TeamEnvelopeDto(
    val team: TeamDto? = null,
)

@Serializable
data class TeamDto(
    val id: Int,
    val name: String,
    @SerialName("join_code") val joinCode: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val members: Int? = null,
    val deliveries: Int? = null,
    val kg: Double? = null,
)

@Serializable
data class TeamCreateDto(val name: String)

@Serializable
data class TeamJoinDto(val code: String)

// ── History ───────────────────────────────────────────────────────────────────

/** A past route (GET /volunteers/{id}/history). */
@Serializable
data class RouteHistoryDto(
    val id: Int,
    @SerialName("volunteer_id") val volunteerId: Int? = null,
    @SerialName("lot_id") val lotId: Int? = null,
    val points: List<RoutePointDto> = emptyList(),
    val status: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
)

// ── Notifications ─────────────────────────────────────────────────────────────

/** A volunteer notification (GET /volunteers/{id}/notifications). */
@Serializable
data class NotificationDto(
    val id: Int,
    val type: String? = null,
    val payload: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val read: Int? = 0,
)
