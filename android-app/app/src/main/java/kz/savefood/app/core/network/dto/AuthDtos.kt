package kz.savefood.app.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    val role: String,
    @SerialName("related_id") val relatedId: Int? = null,
)

@Serializable
data class RefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
)

@Serializable
data class MeResponse(
    val sub: String? = null,
    val role: String? = null,
    @SerialName("related_id") val relatedId: Int? = null,
)
