package ru.savefood.app.core.network
import android.util.Log
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.savefood.app.core.datastore.SessionStore
import ru.savefood.app.core.datastore.TokenPair
import ru.savefood.app.core.network.dto.RefreshRequest
import ru.savefood.app.core.network.dto.RefreshResponse
/** Refreshes access JWTs with an independent rotating refresh credential. */
@Singleton
class TokenRefreshManager @Inject constructor(
    sessionStore: SessionStore,
    @Named("baseUrl") baseUrl: String,
    json: Json,
) {
    private val refresher = HttpTokenRefresher(baseUrl, json)
    private val coordinator = TokenRefreshCoordinator(
        currentTokenPair = sessionStore::currentTokenPair,
        replaceTokenPair = sessionStore::replaceTokenPair,
        clearTokenPair = sessionStore::clearIfRefreshToken,
        refreshToken = refresher::refresh,
    )
    /** Called by the interceptor before attaching Authorization. */
    fun tokenForRequest(): String? = runBlocking { coordinator.tokenForRequest() }
    /** Called by the authenticator after a 401, using the token actually rejected. */
    fun tokenAfterUnauthorized(rejectedToken: String): String? =
        runBlocking { coordinator.tokenAfterUnauthorized(rejectedToken) }
}
internal sealed interface RefreshAttempt {
    data class Success(val tokenPair: TokenPair) : RefreshAttempt
    data object AuthenticationRejected : RefreshAttempt
    data object InvalidResponse : RefreshAttempt
    data object TransientFailure : RefreshAttempt
}
internal class TokenRefreshCoordinator(
    private val currentTokenPair: suspend () -> TokenPair?,
    private val replaceTokenPair: suspend (expectedRefresh: String, replacement: TokenPair) -> Boolean,
    private val clearTokenPair: suspend (expectedRefresh: String) -> Boolean,
    private val refreshToken: (String) -> RefreshAttempt,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val refreshMarginSeconds: Long = DEFAULT_REFRESH_MARGIN_SECONDS,
) {
    private val refreshMutex = Mutex()
    suspend fun tokenForRequest(): String? {
        val observed = currentTokenPair() ?: return null
        if (!needsRefresh(observed.accessToken)) return observed.accessToken
        return refreshMutex.withLock { refreshLocked(observed.refreshToken) }
    }
    suspend fun tokenAfterUnauthorized(rejectedToken: String): String? =
        refreshMutex.withLock {
            val current = currentTokenPair() ?: return@withLock null
            if (current.accessToken != rejectedToken) return@withLock current.accessToken
            refreshLocked(current.refreshToken)
        }
    private suspend fun refreshLocked(observedRefreshToken: String): String? {
        val current = currentTokenPair() ?: return null
        if (current.refreshToken != observedRefreshToken) return current.accessToken
        val expiresAt = JwtExpiry.expiresAt(current.accessToken)
        return when (val attempt = refreshToken(current.refreshToken)) {
            is RefreshAttempt.Success -> {
                val replacementExpiry = JwtExpiry.expiresAt(attempt.tokenPair.accessToken)
                if (replacementExpiry == null ||
                    replacementExpiry <= nowEpochSeconds() + refreshMarginSeconds ||
                    attempt.tokenPair.refreshToken.isBlank() ||
                    attempt.tokenPair.refreshToken == current.refreshToken
                ) {
                    clearTokenPair(current.refreshToken)
                    null
                } else if (replaceTokenPair(current.refreshToken, attempt.tokenPair)) {
                    attempt.tokenPair.accessToken
                } else {
                    currentTokenPair()?.accessToken
                }
            }
            RefreshAttempt.AuthenticationRejected,
            RefreshAttempt.InvalidResponse,
            -> {
                clearTokenPair(current.refreshToken)
                null
            }
            RefreshAttempt.TransientFailure -> {
                if (expiresAt != null && expiresAt > nowEpochSeconds()) current.accessToken else null
            }
        }
    }
    private fun needsRefresh(token: String): Boolean {
        val expiresAt = JwtExpiry.expiresAt(token) ?: return true
        return expiresAt <= nowEpochSeconds() + refreshMarginSeconds
    }
    companion object {
        internal const val DEFAULT_REFRESH_MARGIN_SECONDS = 5 * 60L
    }
}
internal object JwtExpiry {
    fun expiresAt(token: String): Long? = runCatching {
        val parts = token.split('.')
        require(parts.size == 3)
        val payload = String(Base64.getUrlDecoder().decode(pad(parts[1])), Charsets.UTF_8)
        Json.parseToJsonElement(payload).jsonObject["exp"]?.jsonPrimitive?.longOrNull
    }.getOrNull()
    private fun pad(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)
}
internal class HttpTokenRefresher(
    baseUrl: String,
    private val json: Json,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
) {
    private val refreshUrl = "${baseUrl.trimEnd('/')}/auth/refresh"
    fun refresh(token: String): RefreshAttempt {
        val request = Request.Builder()
            .url(refreshUrl)
            .post(
                json.encodeToString(RefreshRequest(token))
                    .toRequestBody("application/json".toMediaType()),
            )
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        RefreshAttempt.AuthenticationRejected
                    !response.isSuccessful -> RefreshAttempt.TransientFailure
                    else -> {
                        val body = response.body?.string()
                            ?: return@use RefreshAttempt.InvalidResponse
                        val refreshed = runCatching {
                            json.decodeFromString<RefreshResponse>(body)
                        }.getOrNull()
                        if (refreshed == null || refreshed.accessToken.isBlank() ||
                            refreshed.refreshToken.isBlank()
                        ) {
                            RefreshAttempt.InvalidResponse
                        } else {
                            RefreshAttempt.Success(
                                TokenPair(refreshed.accessToken, refreshed.refreshToken),
                            )
                        }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Token refresh failed", it) }
            .getOrDefault(RefreshAttempt.TransientFailure)
    }
    private companion object {
        const val TAG = "TokenRefreshManager"
    }
}
