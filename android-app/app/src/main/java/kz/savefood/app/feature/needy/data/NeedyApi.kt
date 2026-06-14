package kz.savefood.app.feature.needy.data

import okhttp3.MultipartBody
import okhttp3.ResponseBody
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
 * Retrofit interface for the "needy" (recipient) role. Paths are relative to
 * BuildConfig.API_BASE_URL; the AuthInterceptor adds the bearer token.
 */
interface NeedyApi {

    // --- Tracking / tickets ---
    @GET("needy/{id}/tickets")
    suspend fun getTickets(@Path("id") needyId: Int): List<TicketDto>

    @GET("needy/{id}/history")
    suspend fun getHistory(
        @Path("id") needyId: Int,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): List<TicketDto>

    @POST("needy/{id}/ticket")
    suspend fun createTicket(
        @Path("id") needyId: Int,
        @Body body: TicketCreateDto,
    ): CreatedIdDto

    @DELETE("needy/{id}/ticket/{ticketId}")
    suspend fun deleteTicket(
        @Path("id") needyId: Int,
        @Path("ticketId") ticketId: Int,
    ): ResponseBody

    // rating/comment are query params on the backend, not a JSON body.
    @POST("needy/{id}/ticket/{ticketId}/rate")
    suspend fun rateTicket(
        @Path("id") needyId: Int,
        @Path("ticketId") ticketId: Int,
        @Query("rating") rating: Int,
        @Query("comment") comment: String? = null,
    ): ResponseBody

    @Multipart
    @POST("needy/{id}/ticket/{ticketId}/photo")
    suspend fun uploadImpactPhoto(
        @Path("id") needyId: Int,
        @Path("ticketId") ticketId: Int,
        @Part file: MultipartBody.Part,
    ): ResponseBody

    // --- Find food ---
    @GET("lots")
    suspend fun getLots(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("category") category: String? = null,
        @Query("search") search: String? = null,
    ): List<LotDto>

    // --- Volunteer live location (delivery tracking) ---
    @GET("volunteers/{volunteerId}/location")
    suspend fun getVolunteerLocation(
        @Path("volunteerId") volunteerId: Int,
    ): VolunteerLocationDto

    // --- Profile ---
    @GET("needy/{id}/profile")
    suspend fun getProfile(@Path("id") needyId: Int): NeedyProfileDto

    @POST("needy/{id}/profile")
    suspend fun createProfile(
        @Path("id") needyId: Int,
        @Body body: NeedyProfileUpdateDto,
    ): NeedyProfileDto

    @PATCH("needy/{id}/profile")
    suspend fun patchProfile(
        @Path("id") needyId: Int,
        @Body body: NeedyProfileUpdateDto,
    ): NeedyProfileDto

    @Multipart
    @POST("needy/{id}/profile/upload")
    suspend fun uploadDocument(
        @Path("id") needyId: Int,
        @Part file: MultipartBody.Part,
    ): NeedyProfileDto

    @PATCH("needy/{id}/geo_push")
    suspend fun setGeoPush(
        @Path("id") needyId: Int,
        @Body body: GeoPushUpdateDto,
    ): ResponseBody

    @GET("needy/{id}/export")
    suspend fun exportAccount(@Path("id") needyId: Int): ResponseBody

    @DELETE("needy/{id}/account")
    suspend fun deleteAccount(@Path("id") needyId: Int): ResponseBody
}
