package ru.savefood.app.core.push
import ru.savefood.app.core.push.dto.FcmRegisterRequest
import ru.savefood.app.core.push.dto.FcmUnregisterRequest
import retrofit2.http.Body
import retrofit2.http.POST
interface PushApi {
    @POST("push/fcm/register")
    suspend fun register(@Body body: FcmRegisterRequest)
    @POST("push/fcm/unregister")
    suspend fun unregister(@Body body: FcmUnregisterRequest)
}
