package ru.savefood.app.feature.needy.data
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
/** A recipient's request/ticket (GET /needy/{id}/tickets, /history). */
@Serializable
data class TicketDto(
    val id: Int,
    @SerialName("needy_id") val needyId: Int,
    val items: String? = null,
    val address: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("available_time") val availableTime: String? = null,
    @SerialName("lot_id") val lotId: Int? = null,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("assigned_volunteer_id") val assignedVolunteerId: Int? = null,
    @SerialName("fulfilled_at") val fulfilledAt: String? = null,
    val apartment: String? = null,
    @SerialName("floor_num") val floorNum: String? = null,
    val entrance: String? = null,
    @SerialName("self_pickup") val selfPickup: Boolean? = null,
    @SerialName("qr_code") val qrCode: String? = null,
    @SerialName("delivery_photo") val deliveryPhoto: String? = null,
    @SerialName("delivery_photo_status") val deliveryPhotoStatus: String? = null,
    val rating: Int? = null,
    @SerialName("rating_comment") val ratingComment: String? = null,
)
/** Body for POST /needy/{id}/ticket. */
@Serializable
data class TicketCreateDto(
    val items: String? = null,
    val address: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("available_time") val availableTime: String? = null,
    @SerialName("lot_id") val lotId: Int? = null,
    val apartment: String? = null,
    @SerialName("floor_num") val floorNum: String? = null,
    val entrance: String? = null,
    @SerialName("self_pickup") val selfPickup: Boolean = false,
)
@Serializable
data class CreatedIdDto(val id: Int)
/** An active food lot (GET /lots). */
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
/** Recipient profile (GET/POST/PATCH /needy/{id}/profile). */
@Serializable
data class NeedyProfileDto(
    @SerialName("needy_id") val needyId: Int? = null,
    val address: String? = null,
    @SerialName("family_size") val familySize: Int? = null,
    val preferences: String? = null,
    val urgency: String? = null,
    @SerialName("available_time") val availableTime: String? = null,
    @SerialName("last_received_at") val lastReceivedAt: String? = null,
    val apartment: String? = null,
    @SerialName("floor_num") val floorNum: String? = null,
    val entrance: String? = null,
    val city: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("geo_push_enabled") val geoPushEnabled: Boolean? = true,
)
/** Body for POST/PATCH /needy/{id}/profile. */
@Serializable
data class NeedyProfileUpdateDto(
    val address: String? = null,
    @SerialName("family_size") val familySize: Int? = null,
    val preferences: String? = null,
    val urgency: String? = null,
    @SerialName("available_time") val availableTime: String? = null,
    val apartment: String? = null,
    @SerialName("floor_num") val floorNum: String? = null,
    val entrance: String? = null,
    val city: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("clear_coordinates") val clearCoordinates: Boolean? = null,
)
@Serializable
data class GeoPushUpdateDto(val enabled: Boolean)
/** Live volunteer location (GET /volunteers/{id}/location). */
@Serializable
data class VolunteerLocationDto(
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)
