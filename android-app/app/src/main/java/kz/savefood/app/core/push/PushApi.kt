package kz.savefood.app.core.push

import kz.savefood.app.core.push.dto.FcmRegisterRequest
import kz.savefood.app.core.push.dto.FcmUnregisterRequest
import retrofit2.http.Body
import retrofit2.http.POST

/** Native Android (FCM) device-token registration. Auth is supplied by the
 *  shared OkHttp [AuthInterceptor]; both calls require a logged-in session. */
interface PushApi {
    @POST("push/fcm/register")
    suspend fun register(@Body body: FcmRegisterRequest)

    @POST("push/fcm/unregister")
    suspend fun unregister(@Body body: FcmUnregisterRequest)
}
