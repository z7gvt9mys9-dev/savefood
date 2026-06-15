package kz.savefood.app.core.network

import kotlinx.coroutines.runBlocking
import kz.savefood.app.core.datastore.SessionStore
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Attaches the bearer token to every outgoing request. OkHttp interceptors run
 * off the main thread, so a short runBlocking read of DataStore is safe here.
 */
class AuthInterceptor @Inject constructor(
    private val sessionStore: SessionStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // Don't attach a token to the login call itself.
        if (original.header(NO_AUTH_HEADER) != null) {
            return chain.proceed(original.newBuilder().removeHeader(NO_AUTH_HEADER).build())
        }
        val token = runBlocking { sessionStore.currentToken() }
        val request = if (token != null) {
            original.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            original
        }
        return chain.proceed(request)
    }

    companion object {
        /** Header marker telling the interceptor to skip auth (stripped before send). */
        const val NO_AUTH_HEADER = "X-No-Auth"
    }
}
