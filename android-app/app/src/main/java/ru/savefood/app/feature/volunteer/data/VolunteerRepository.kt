package ru.savefood.app.feature.volunteer.data
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import ru.savefood.app.BuildConfig
import ru.savefood.app.core.common.ApiResult
import ru.savefood.app.core.common.safeApiCall
import ru.savefood.app.core.datastore.SessionStore
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class VolunteerRepository @Inject constructor(
    private val api: VolunteerApi,
    private val sessionStore: SessionStore,
    @ApplicationContext private val context: Context,
) {
    /** Current volunteer id (== session.relatedId), or null when not a volunteer session. */
    suspend fun currentVolunteerId(): Int? = sessionStore.sessionFlow.first()?.relatedId
    suspend fun getMap(city: String): ApiResult<VolunteerMapDto> = safeApiCall { api.getMap(city) }
    suspend fun getLots(search: String? = null): ApiResult<List<LotDto>> =
        safeApiCall { api.getLots(search = search) }
    suspend fun startRoute(volunteerId: Int, lotId: Int, maxStops: Int? = null): ApiResult<StartRouteResponseDto> =
        safeApiCall { api.startRoute(volunteerId, StartRouteRequestDto(lotId = lotId, maxStops = maxStops)) }
    suspend fun getActiveRoute(volunteerId: Int): ApiResult<RouteDto> =
        safeApiCall { api.getActiveRoute(volunteerId) }
    suspend fun completePoint(
        routeId: Int,
        volunteerId: Int,
        ticketId: Int?,
        lat: Double?,
        lon: Double?,
        qrCode: String?,
    ): ApiResult<Unit> = safeApiCall {
        api.completePoint(
            routeId,
            CompletePointRequestDto(volunteerId, ticketId, lat, lon, qrCode),
        )
        Unit
    }
    suspend fun attemptDelivery(
        routeId: Int,
        volunteerId: Int,
        ticketId: Int,
        lat: Double,
        lon: Double,
    ): ApiResult<AttemptDeliveryResponseDto> = safeApiCall {
        api.attemptDelivery(routeId, CompletePointRequestDto(volunteerId, ticketId, lat, lon))
    }
    /** Uploads the courier's proof into private server storage before completion. */
    suspend fun uploadDeliveryPhoto(
        routeId: Int,
        ticketId: Int,
        uri: Uri,
        lat: Double,
        lon: Double,
    ): ApiResult<Unit> =
        safeApiCall {
            api.uploadDeliveryPhoto(
                routeId,
                ticketId,
                uri.toFilePart("file"),
                lat.toString().toRequestBody("text/plain".toMediaType()),
                lon.toString().toRequestBody("text/plain".toMediaType()),
            )
            Unit
        }
    suspend fun finishRoute(routeId: Int, volunteerId: Int): ApiResult<Unit> =
        safeApiCall { api.finishRoute(routeId, FinishRouteRequestDto(volunteerId)); Unit }
    suspend fun updateLocation(volunteerId: Int, lat: Double, lon: Double): ApiResult<Unit> =
        safeApiCall { api.updateLocation(volunteerId, LocationUpdateDto(lat, lon)); Unit }
    suspend fun getRating(volunteerId: Int): ApiResult<RatingDto> =
        safeApiCall { api.getRating(volunteerId) }
    suspend fun getStats(volunteerId: Int): ApiResult<StatsDto> =
        safeApiCall { api.getStats(volunteerId) }
    suspend fun getThanks(volunteerId: Int): ApiResult<List<ThanksDto>> =
        safeApiCall { api.getThanks(volunteerId) }
    suspend fun getTeam(volunteerId: Int): ApiResult<TeamDto?> =
        safeApiCall { api.getTeam(volunteerId).team }
    suspend fun createTeam(volunteerId: Int, name: String): ApiResult<TeamDto?> =
        safeApiCall { api.createTeam(volunteerId, TeamCreateDto(name)).team }
    suspend fun joinTeam(volunteerId: Int, code: String): ApiResult<TeamDto?> =
        safeApiCall { api.joinTeam(volunteerId, TeamJoinDto(code)).team }
    suspend fun leaveTeam(volunteerId: Int): ApiResult<Unit> =
        safeApiCall { api.leaveTeam(volunteerId); Unit }
    suspend fun getVolunteer(volunteerId: Int): ApiResult<VolunteerDto> =
        safeApiCall { api.getVolunteer(volunteerId) }
    suspend fun patchVolunteer(volunteerId: Int, body: VolunteerUpdateDto): ApiResult<VolunteerDto> =
        safeApiCall { api.patchVolunteer(volunteerId, body) }
    suspend fun uploadDocument(volunteerId: Int, uri: Uri): ApiResult<KycUploadResponseDto> =
        safeApiCall { api.uploadDocument(volunteerId, uri.toFilePart("file")) }
    suspend fun getHistory(volunteerId: Int, limit: Int = 20, offset: Int = 0): ApiResult<List<RouteHistoryDto>> =
        safeApiCall { api.getHistory(volunteerId, limit, offset) }
    suspend fun getNotifications(volunteerId: Int): ApiResult<List<NotificationDto>> =
        safeApiCall { api.getNotifications(volunteerId) }
    suspend fun markNotificationRead(notificationId: Int): ApiResult<Unit> =
        safeApiCall { api.markNotificationRead(notificationId); Unit }
    /** Copies the content [Uri] into a cache file and builds a multipart file part. */
    private suspend fun Uri.toFilePart(name: String): MultipartBody.Part = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = (resolver.getType(this@toFilePart) ?: "image/jpeg").lowercase()
        val ext = when (mime) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "application/pdf" -> "pdf"
            else -> error("Поддерживаются только файлы JPG, PNG или PDF")
        }
        val tmp = File.createTempFile("vol_upload_", ".$ext", context.cacheDir)
        resolver.openInputStream(this@toFilePart)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: error("Не удалось прочитать файл")
        val reqBody = tmp.asRequestBody(mime.toMediaTypeOrNull())
        MultipartBody.Part.createFormData(name, tmp.name, reqBody)
    }
    companion object {
        /** Prefix a server-relative media path with the API base URL. */
        fun absoluteUrl(path: String?): String? {
            if (path.isNullOrBlank()) return null
            if (path.startsWith("http://") || path.startsWith("https://")) return path
            val base = BuildConfig.API_BASE_URL.trimEnd('/')
            return base + (if (path.startsWith("/")) path else "/$path")
        }
    }
}
