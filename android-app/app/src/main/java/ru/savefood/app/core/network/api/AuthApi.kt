package ru.savefood.app.core.network.api
import ru.savefood.app.core.network.dto.LoginResponse
import ru.savefood.app.core.network.dto.MeResponse
import ru.savefood.app.core.network.dto.LogoutResponse
import ru.savefood.app.core.network.dto.RefreshRequest
import ru.savefood.app.core.network.dto.RefreshResponse
import ru.savefood.app.core.network.dto.RegistrationResponse
import ru.savefood.app.core.network.dto.ShopRegistrationRequest
import ru.savefood.app.core.network.dto.VolunteerRegistrationRequest
import ru.savefood.app.core.network.dto.NeedyRegistrationRequest
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
interface AuthApi {
    @Headers("X-No-Auth: true")
    @FormUrlEncoded
    @POST("auth/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
        @Field("role") role: String? = null,
    ): LoginResponse

    @Headers("X-No-Auth: true")
    @POST("shops/register")
    suspend fun registerShop(@Body request: ShopRegistrationRequest): RegistrationResponse

    @Headers("X-No-Auth: true")
    @POST("volunteers/register")
    suspend fun registerVolunteer(@Body request: VolunteerRegistrationRequest): RegistrationResponse

    @Headers("X-No-Auth: true")
    @POST("needy/register")
    suspend fun registerNeedy(@Body request: NeedyRegistrationRequest): RegistrationResponse
    @Headers("X-No-Auth: true")
    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): RefreshResponse
    @Headers("X-No-Auth: true")
    @POST("auth/logout")
    suspend fun logout(@Body request: RefreshRequest): LogoutResponse
    @GET("auth/me")
    suspend fun me(): MeResponse
}
