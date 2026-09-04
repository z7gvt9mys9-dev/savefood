package ru.savefood.app.feature.needy.data
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
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class NeedyRepository @Inject constructor(
    private val api: NeedyApi,
    private val sessionStore: SessionStore,
    @ApplicationContext private val context: Context,
) {
    /** Current recipient id (== session.relatedId), or null when not a needy session. */
    suspend fun currentNeedyId(): Int? = sessionStore.sessionFlow.first()?.relatedId
    suspend fun getTickets(needyId: Int): ApiResult<List<TicketDto>> =
        safeApiCall { api.getTickets(needyId) }
    suspend fun getHistory(needyId: Int, limit: Int = 20, offset: Int = 0): ApiResult<List<TicketDto>> =
        safeApiCall { api.getHistory(needyId, limit, offset) }
    suspend fun createTicket(needyId: Int, body: TicketCreateDto): ApiResult<Int> =
        safeApiCall { api.createTicket(needyId, body).id }
    suspend fun deleteTicket(needyId: Int, ticketId: Int): ApiResult<Unit> =
        safeApiCall { api.deleteTicket(needyId, ticketId); Unit }
    suspend fun rateTicket(needyId: Int, ticketId: Int, rating: Int, comment: String?): ApiResult<Unit> =
        safeApiCall { api.rateTicket(needyId, ticketId, rating, comment?.takeIf { it.isNotBlank() }); Unit }
    suspend fun uploadImpactPhoto(needyId: Int, ticketId: Int, uri: Uri): ApiResult<Unit> =
        safeApiCall {
            val part = uri.toImagePart("file")
            api.uploadImpactPhoto(needyId, ticketId, part)
            Unit
        }
    suspend fun getLots(
        limit: Int = 50,
        offset: Int = 0,
        category: String? = null,
        search: String? = null,
    ): ApiResult<List<LotDto>> = safeApiCall { api.getLots(limit, offset, category, search) }
    suspend fun getVolunteerLocation(volunteerId: Int): ApiResult<VolunteerLocationDto> =
        safeApiCall { api.getVolunteerLocation(volunteerId) }
    suspend fun getProfile(needyId: Int): ApiResult<NeedyProfileDto> =
        safeApiCall { api.getProfile(needyId) }
    suspend fun saveProfile(needyId: Int, body: NeedyProfileUpdateDto, exists: Boolean): ApiResult<NeedyProfileDto> =
        safeApiCall { if (exists) api.patchProfile(needyId, body) else api.createProfile(needyId, body) }
    suspend fun setGeoPush(needyId: Int, enabled: Boolean): ApiResult<Unit> =
        safeApiCall { api.setGeoPush(needyId, GeoPushUpdateDto(enabled)); Unit }
    suspend fun exportAccount(needyId: Int): ApiResult<String> =
        safeApiCall { api.exportAccount(needyId).string() }
    suspend fun deleteAccount(needyId: Int): ApiResult<Unit> =
        safeApiCall { api.deleteAccount(needyId).also { it.close() }; Unit }
    /** Copies the content [Uri] into a cache file and builds a multipart image part. */
    private suspend fun Uri.toImagePart(name: String): MultipartBody.Part = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = (resolver.getType(this@toImagePart) ?: "image/jpeg").lowercase()
        val ext = when (mime) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            else -> error("Поддерживаются только файлы JPG или PNG")
        }
        val tmp = File.createTempFile("needy_upload_", ".$ext", context.cacheDir)
        resolver.openInputStream(this@toImagePart)?.use { input ->
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
