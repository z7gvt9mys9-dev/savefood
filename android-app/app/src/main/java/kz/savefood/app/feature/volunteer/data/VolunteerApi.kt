package kz.savefood.app.feature.volunteer.data

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the "volunteer" role. Paths are relative to
 * BuildConfig.API_BASE_URL; the AuthInterceptor adds the bearer token.
 */
interface VolunteerApi {

    // --- Available (map + lots) ---
    @GET("volunteers/map")
    suspend fun getMap(): VolunteerMapDto

    @GET("lots")
    suspend fun getLots(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
        @Query("category") category: String? = null,
        @Query("search") search: String? = null,
    ): List<LotDto>

    @POST("volunteers/{id}/start_route")
    suspend fun startRoute(
        @Path("id") volunteerId: Int,
        @Body body: StartRouteRequestDto,
    ): StartRouteResponseDto

    // --- Active route ---
    @GET("volunteers/{id}/active_route")
    suspend fun getActiveRoute(@Path("id") volunteerId: Int): RouteDto

    @POST("volunteers/route/{routeId}/complete_point")
    suspend fun completePoint(
        @Path("routeId") routeId: Int,
        @Body body: CompletePointRequestDto,
    ): OkDto

    @POST("volunteers/route/{routeId}/attempt_delivery")
    suspend fun attemptDelivery(
        @Path("routeId") routeId: Int,
        @Body body: CompletePointRequestDto,
    ): AttemptDeliveryResponseDto

    @POST("volunteers/route/{routeId}/finish")
    suspend fun finishRoute(
        @Path("routeId") routeId: Int,
        @Body body: FinishRouteRequestDto,
    ): OkDto

    @PATCH("volunteers/{id}/location")
    suspend fun updateLocation(
        @Path("id") volunteerId: Int,
        @Body body: LocationUpdateDto,
    ): OkDto

    // --- Rating / stats / thanks / team ---
    @GET("volunteers/{id}/rating")
    suspend fun getRating(@Path("id") volunteerId: Int): RatingDto

    @GET("volunteers/{id}/stats")
    suspend fun getStats(@Path("id") volunteerId: Int): StatsDto

    @GET("volunteers/{id}/thanks")
    suspend fun getThanks(
        @Path("id") volunteerId: Int,
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
    ): List<ThanksDto>

    @GET("volunteers/{id}/team")
    suspend fun getTeam(@Path("id") volunteerId: Int): TeamEnvelopeDto

    @POST("volunteers/{id}/team/create")
    suspend fun createTeam(
        @Path("id") volunteerId: Int,
        @Body body: TeamCreateDto,
    ): TeamEnvelopeDto

    @POST("volunteers/{id}/team/join")
    suspend fun joinTeam(
        @Path("id") volunteerId: Int,
        @Body body: TeamJoinDto,
    ): TeamEnvelopeDto

    @POST("volunteers/{id}/team/leave")
    suspend fun leaveTeam(@Path("id") volunteerId: Int): OkDto

    // --- Profile / KYC / history / notifications ---
    @GET("volunteers/{id}")
    suspend fun getVolunteer(@Path("id") volunteerId: Int): VolunteerDto

    @PATCH("volunteers/{id}")
    suspend fun patchVolunteer(
        @Path("id") volunteerId: Int,
        @Body body: VolunteerUpdateDto,
    ): VolunteerDto

    @Multipart
    @POST("volunteers/{id}/document/upload")
    suspend fun uploadDocument(
        @Path("id") volunteerId: Int,
        @Part file: MultipartBody.Part,
    ): KycUploadResponseDto

    @GET("volunteers/{id}/history")
    suspend fun getHistory(
        @Path("id") volunteerId: Int,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): List<RouteHistoryDto>

    @GET("volunteers/{id}/notifications")
    suspend fun getNotifications(@Path("id") volunteerId: Int): List<NotificationDto>

    @PATCH("volunteers/notifications/{notificationId}/read")
    suspend fun markNotificationRead(@Path("notificationId") notificationId: Int): OkDto
}
