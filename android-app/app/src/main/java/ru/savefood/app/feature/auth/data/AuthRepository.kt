package ru.savefood.app.feature.auth.data

import kotlinx.coroutines.flow.Flow
import ru.savefood.app.core.common.ApiResult
import ru.savefood.app.core.common.safeApiCall
import ru.savefood.app.core.datastore.Session
import ru.savefood.app.core.datastore.SessionStore
import ru.savefood.app.core.network.api.AuthApi
import ru.savefood.app.core.push.PushTokenManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val sessionStore: SessionStore,
    private val pushTokenManager: PushTokenManager,
) {
    val sessionFlow: Flow<Session?> = sessionStore.sessionFlow

    suspend fun login(username: String, password: String): ApiResult<Session> {
        return when (val res = safeApiCall { authApi.login(username.trim(), password) }) {
            is ApiResult.Success -> {
                val body = res.data
                sessionStore.save(body.accessToken, body.role, body.relatedId)
                ApiResult.Success(
                    Session(
                        token = body.accessToken,
                        role = ru.savefood.app.core.datastore.UserRole.from(body.role),
                        relatedId = body.relatedId,
                    ),
                )
            }
            is ApiResult.Error -> res
        }
    }

    suspend fun logout() {
        // Drop this device's FCM token first — it needs the still-valid session
        // token for auth. Best-effort: clearing the session must happen regardless.
        pushTokenManager.unregisterCurrentToken()
        sessionStore.clear()
    }
}
