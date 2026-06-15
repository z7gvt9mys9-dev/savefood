package kz.savefood.app.core.network.api

import kz.savefood.app.core.network.dto.LoginResponse
import kz.savefood.app.core.network.dto.MeResponse
import kz.savefood.app.core.network.dto.RefreshResponse
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthApi {
    // Backend uses FastAPI's OAuth2PasswordRequestForm → form-urlencoded body.
    @FormUrlEncoded
    @POST("auth/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String,
    ): LoginResponse

    @POST("auth/refresh")
    suspend fun refresh(): RefreshResponse

    @GET("auth/me")
    suspend fun me(): MeResponse
}
