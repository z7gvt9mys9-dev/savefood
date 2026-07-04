package ru.savefood.app.core.push.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Body for `POST /push/fcm/register`. The backend re-checks role/related_id
 *  against the authenticated account, so these must mirror the saved session. */
@Serializable
data class FcmRegisterRequest(
    val token: String,
    val role: String,
    @SerialName("related_id") val relatedId: Int? = null,
)

/** Body for `POST /push/fcm/unregister`. */
@Serializable
data class FcmUnregisterRequest(
    val token: String,
)
