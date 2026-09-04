package ru.savefood.app.core.network.api
import ru.savefood.app.core.network.dto.LoginResponse
import ru.savefood.app.core.network.dto.MeResponse
import ru.savefood.app.core.network.dto.LogoutResponse
import ru.savefood.app.core.network.dto.RefreshRequest
import ru.savefood.app.core.network.dto.RefreshResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
interface AuthApi {
    @FormUrlEncoded
    @POST("auth/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
    ): LoginResponse
    @Headers("X-No-Auth: true")
    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): RefreshResponse
    @Headers("X-No-Auth: true")
    @POST("auth/logout")
    suspend fun logout(@Body request: RefreshRequest): LogoutResponse
    @GET("auth/me")
    suspend fun me(): MeResponse
}
