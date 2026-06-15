package kz.savefood.app.core.network

import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kz.savefood.app.core.datastore.SessionStore
import kz.savefood.app.core.network.dto.RefreshResponse
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On a 401, silently calls POST /auth/refresh with the current token, persists
 * the new access token, and retries the original request once. Uses a bare
 * OkHttp client (no auth interceptor / authenticator) to avoid recursion.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val sessionStore: SessionStore,
    private val baseUrl: String,
    private val json: Json,
) : Authenticator {

    private val bareClient by lazy { OkHttpClient.Builder().build() }

    override fun authenticate(route: Route?, response: Response): Request? {
        // Give up after one retry to avoid an infinite 401 loop.
        if (responseCount(response) >= 2) return null

        val current = runBlocking { sessionStore.currentToken() } ?: return null

        val newToken = runBlocking { refresh(current) } ?: run {
            // Refresh failed → session is dead; clear it so the UI routes to login.
            runBlocking { sessionStore.clear() }
            return null
        }

        runBlocking { sessionStore.updateToken(newToken) }
        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }

    private fun refresh(token: String): String? {
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/auth/refresh")
            .header("Authorization", "Bearer $token")
            .post(ByteArray(0).toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            bareClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                json.decodeFromString<RefreshResponse>(body).accessToken
            }
        }.onFailure { Log.w(TAG, "Token refresh failed", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "TokenAuthenticator"
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
