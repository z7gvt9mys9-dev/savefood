package ru.savefood.app.feature.shop.data

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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data access for the shop role. Wraps [ShopApi] in [ApiResult] and pulls the
 * current `shop_id` from the session (it equals the JWT related_id).
 */
@Singleton
class ShopRepository @Inject constructor(
    private val api: ShopApi,
    private val sessionStore: SessionStore,
    @ApplicationContext private val context: Context,
) {
    /** Current shop id (== session.relatedId), or null when not a shop session. */
    suspend fun currentShopId(): Int? = sessionStore.sessionFlow.first()?.relatedId

    // --- Lots ---
    suspend fun getLots(shopId: Int): ApiResult<List<LotDto>> =
        safeApiCall { api.getLots(shopId) }

    suspend fun createLot(shopId: Int, body: LotCreateDto): ApiResult<Int> =
        safeApiCall { api.createLot(shopId, body).id }

    suspend fun createLotWithPhoto(
        shopId: Int,
        body: LotCreateDto,
        photoUri: Uri?,
    ): ApiResult<Int> = safeApiCall {
        api.createLotUpload(
            shopId = shopId,
            description = body.description.toTextPart(),
            quantity = body.quantity.toString().toTextPart(),
            unit = body.unit.toTextPart(),
            unitWeightKg = body.unitWeightKg.toString().toTextPart(),
            expiryDate = body.expiryDate?.toTextPart(),
            address = body.address?.toTextPart(),
            timeSlot = body.timeSlot?.toTextPart(),
            category = body.category?.toTextPart(),
            comment = body.comment?.toTextPart(),
            requiresCold = (body.requiresCold == true).toString().toTextPart(),
            file = photoUri?.toFilePart("file"),
        ).id
    }

    suspend fun patchLot(lotId: Int, body: LotUpdateDto): ApiResult<LotDto> =
        safeApiCall { api.patchLot(lotId, body) }

    suspend fun deleteLot(lotId: Int): ApiResult<Unit> =
        safeApiCall { api.deleteLot(lotId); Unit }

    suspend fun confirmTransfer(lotId: Int): ApiResult<Unit> =
        safeApiCall { api.confirmTransfer(lotId); Unit }

    suspend fun getHistory(shopId: Int, limit: Int = 20, offset: Int = 0): ApiResult<List<LotDto>> =
        safeApiCall { api.getHistory(shopId, limit, offset) }

    // --- Receipts / OCR / ESG ---
    suspend fun getReceipts(shopId: Int): ApiResult<List<ReceiptDto>> =
        safeApiCall { api.getReceipts(shopId) }

    suspend fun uploadReceipt(shopId: Int, uri: Uri): ApiResult<ReceiptDto> =
        safeApiCall { api.uploadReceipt(shopId, uri.toFilePart("file")) }

    suspend fun confirmReceipt(shopId: Int, receiptId: Int, body: ReceiptConfirmDto): ApiResult<Unit> =
        safeApiCall { api.confirmReceipt(shopId, receiptId, body); Unit }

    suspend fun getEsg(shopId: Int, months: Int = 12): ApiResult<EsgReportDto> =
        safeApiCall { api.getEsg(shopId, months) }

    /** Downloads the ESG CSV (auth-gated) as raw bytes for the caller to persist. */
    suspend fun downloadEsgCsv(shopId: Int, months: Int = 12): ApiResult<ByteArray> =
        safeApiCall { withContext(Dispatchers.IO) { api.getEsgCsv(shopId, months).bytes() } }

    // --- Profile / plan / notifications / self-pickup ---
    suspend fun getShop(shopId: Int): ApiResult<ShopDto> =
        safeApiCall { api.getShop(shopId) }

    suspend fun patchShop(shopId: Int, body: ShopUpdateDto): ApiResult<ShopDto> =
        safeApiCall { api.patchShop(shopId, body) }

    suspend fun getPlan(shopId: Int): ApiResult<PlanDto> =
        safeApiCall { api.getPlan(shopId) }

    suspend fun getNotifications(shopId: Int): ApiResult<List<NotificationDto>> =
        safeApiCall { api.getNotifications(shopId) }

    suspend fun markNotificationRead(notificationId: Int): ApiResult<Unit> =
        safeApiCall { api.markNotificationRead(notificationId); Unit }

    suspend fun confirmSelfPickup(shopId: Int, code: String): ApiResult<SelfPickupResultDto> =
        safeApiCall { api.confirmSelfPickup(shopId, SelfPickupConfirmDto(code.trim())) }

    private fun String.toTextPart(): RequestBody =
        toRequestBody("text/plain".toMediaTypeOrNull())

    /** Copies the content [Uri] into a cache file and builds a multipart file part. */
    private suspend fun Uri.toFilePart(name: String): MultipartBody.Part = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = (resolver.getType(this@toFilePart) ?: "image/jpeg").lowercase()
        val ext = when (mime) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            else -> error("Поддерживаются только изображения JPG или PNG")
        }
        val tmp = File.createTempFile("shop_upload_", ".$ext", context.cacheDir)
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
