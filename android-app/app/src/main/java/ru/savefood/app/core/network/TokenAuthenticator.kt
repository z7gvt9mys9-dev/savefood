package ru.savefood.app.core.network

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last-chance 401 recovery. Normal refresh happens proactively in the auth
 * interceptor; this path retries once and never authenticates its own refresh.
 */
@Singleton
class TokenAuthenticator internal constructor(
    private val tokenAfterUnauthorized: (String) -> String?,
) : Authenticator {
    @Inject
    constructor(refreshManager: TokenRefreshManager) :
        this(refreshManager::tokenAfterUnauthorized)

    override fun authenticate(route: Route?, response: Response): Request? {
        // Give up after one retry to avoid an infinite 401 loop.
        if (responseCount(response) >= 2) return null

        val rejectedToken = response.request.header("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?: return null
        val newToken = tokenAfterUnauthorized(rejectedToken) ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
