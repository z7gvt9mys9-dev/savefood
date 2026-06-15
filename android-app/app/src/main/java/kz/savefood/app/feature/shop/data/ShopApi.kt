package kz.savefood.app.feature.shop.data

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Streaming
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the "shop" role. Paths are relative to
 * BuildConfig.API_BASE_URL; the AuthInterceptor adds the bearer token.
 *
 * The forecast endpoint (GET /shops/{id}/forecast) is intentionally omitted —
 * the «прогноз списаний» module is out of scope for this app.
 */
interface ShopApi {

    // --- Lots ---
    @GET("shops/{id}/lots")
    suspend fun getLots(@Path("id") shopId: Int): List<LotDto>

    @POST("shops/{id}/lots")
    suspend fun createLot(@Path("id") shopId: Int, @Body body: LotCreateDto): IdDto

    @Multipart
    @POST("shops/{id}/lots/upload")
    suspend fun createLotUpload(
        @Path("id") shopId: Int,
        @Part("description") description: RequestBody,
        @Part("quantity") quantity: RequestBody,
        @Part("unit") unit: RequestBody,
        @Part("unit_weight_kg") unitWeightKg: RequestBody,
        @Part("expiry_date") expiryDate: RequestBody?,
        @Part("address") address: RequestBody?,
        @Part("time_slot") timeSlot: RequestBody?,
        @Part("category") category: RequestBody?,
        @Part("comment") comment: RequestBody?,
        @Part("requires_cold") requiresCold: RequestBody,
        @Part file: MultipartBody.Part?,
    ): IdDto

    @PATCH("lots/{lotId}")
    suspend fun patchLot(@Path("lotId") lotId: Int, @Body body: LotUpdateDto): LotDto

    @DELETE("lots/{lotId}")
    suspend fun deleteLot(@Path("lotId") lotId: Int): OkDto

    @POST("lots/{lotId}/confirm_transfer")
    suspend fun confirmTransfer(@Path("lotId") lotId: Int): OkDto

    @GET("shops/{id}/history")
    suspend fun getHistory(
        @Path("id") shopId: Int,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): List<LotDto>

    // --- Receipts / OCR / ESG ---
    @GET("shops/{id}/receipts")
    suspend fun getReceipts(
        @Path("id") shopId: Int,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): List<ReceiptDto>

    @Multipart
    @POST("shops/{id}/receipts")
    suspend fun uploadReceipt(
        @Path("id") shopId: Int,
        @Part file: MultipartBody.Part,
    ): ReceiptDto

    @POST("shops/{id}/receipts/{receiptId}/confirm")
    suspend fun confirmReceipt(
        @Path("id") shopId: Int,
        @Path("receiptId") receiptId: Int,
        @Body body: ReceiptConfirmDto,
    ): OkDto

    @GET("shops/{id}/esg")
    suspend fun getEsg(
        @Path("id") shopId: Int,
        @Query("months") months: Int = 12,
    ): EsgReportDto

    @Streaming
    @GET("shops/{id}/esg/report.csv")
    suspend fun getEsgCsv(
        @Path("id") shopId: Int,
        @Query("months") months: Int = 12,
    ): ResponseBody

    // --- Profile / plan / notifications / self-pickup ---
    @GET("shops/{id}")
    suspend fun getShop(@Path("id") shopId: Int): ShopDto

    @PATCH("shops/{id}")
    suspend fun patchShop(@Path("id") shopId: Int, @Body body: ShopUpdateDto): ShopDto

    @GET("shops/{id}/plan")
    suspend fun getPlan(@Path("id") shopId: Int): PlanDto

    @GET("shops/{id}/notifications")
    suspend fun getNotifications(@Path("id") shopId: Int): List<NotificationDto>

    @PATCH("shops/notifications/{notificationId}/read")
    suspend fun markNotificationRead(@Path("notificationId") notificationId: Int): OkDto

    @POST("shops/{id}/self_pickup/confirm")
    suspend fun confirmSelfPickup(
        @Path("id") shopId: Int,
        @Body body: SelfPickupConfirmDto,
    ): SelfPickupResultDto
}
