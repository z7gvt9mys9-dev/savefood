package ru.savefood.app.core.network

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Attaches the bearer token to every outgoing request, refreshing it first when
 * it is close enough to expiry that the backend could reject a later refresh.
 */
class AuthInterceptor internal constructor(
    private val tokenProvider: () -> String?,
) : Interceptor {
    @Inject
    constructor(refreshManager: TokenRefreshManager) : this(refreshManager::tokenForRequest)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        // Don't attach a token to the login call itself.
        if (original.header(NO_AUTH_HEADER) != null) {
            return chain.proceed(original.newBuilder().removeHeader(NO_AUTH_HEADER).build())
        }
        val token = tokenProvider()
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
