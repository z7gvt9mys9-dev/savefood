package ru.savefood.app.feature.auth.data
import kotlinx.coroutines.flow.Flow
import ru.savefood.app.core.common.ApiResult
import ru.savefood.app.core.common.safeApiCall
import ru.savefood.app.core.common.AppStrings
import ru.savefood.app.R
import ru.savefood.app.core.datastore.Session
import ru.savefood.app.core.datastore.SessionStore
import ru.savefood.app.core.network.api.AuthApi
import ru.savefood.app.core.network.dto.RefreshRequest
import ru.savefood.app.core.network.dto.ShopRegistrationRequest
import ru.savefood.app.core.network.dto.VolunteerRegistrationRequest
import ru.savefood.app.core.network.dto.NeedyRegistrationRequest
import ru.savefood.app.feature.auth.AuthRole
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
    suspend fun login(username: String, password: String, role: String? = null): ApiResult<Session> {
        return when (val res = safeApiCall { authApi.login(username.trim(), password, role) }) {
            is ApiResult.Success -> {
                val body = res.data
                sessionStore.save(body.accessToken, body.refreshToken, body.role, body.relatedId)
                ApiResult.Success(
                    Session(
                        token = body.accessToken,
                        refreshToken = body.refreshToken,
                        role = ru.savefood.app.core.datastore.UserRole.from(body.role),
                        relatedId = body.relatedId,
                    ),
                )
            }
            is ApiResult.Error -> if (res.code == 401) {
                ApiResult.Error(AppStrings.get(R.string.auth_error_invalid), res.code)
            } else {
                res
            }
        }
    }

    suspend fun register(
        role: AuthRole,
        name: String,
        username: String,
        password: String,
        contact: String,
        city: String?,
        donorKind: String,
    ): ApiResult<Session> {
        val normalizedUsername = username.trim()
        val normalizedContact = contact.trim()
        val registration = when (role) {
            AuthRole.SHOP -> safeApiCall {
                authApi.registerShop(
                    ShopRegistrationRequest(
                        name = name.trim(),
                        contact = normalizedContact,
                        city = city?.trim()?.takeIf(String::isNotEmpty),
                        username = normalizedUsername,
                        password = password,
                        kind = donorKind,
                    ),
                )
            }
            AuthRole.VOLUNTEER -> safeApiCall {
                authApi.registerVolunteer(
                    VolunteerRegistrationRequest(
                        name = name.trim(),
                        contact = normalizedContact,
                        city = city?.trim()?.takeIf(String::isNotEmpty),
                        username = normalizedUsername,
                        password = password,
                    ),
                )
            }
            AuthRole.NEEDY -> safeApiCall {
                authApi.registerNeedy(
                    NeedyRegistrationRequest(
                        name = name.trim(),
                        contact = normalizedContact,
                        username = normalizedUsername,
                        password = password,
                    ),
                )
            }
        }
        if (registration is ApiResult.Error) return registration
        return login(normalizedUsername, password, role.apiValue)
    }
    suspend fun logout() {
        pushTokenManager.unregisterCurrentToken()
        sessionStore.currentTokenPair()?.refreshToken?.let { refreshToken ->
            safeApiCall { authApi.logout(RefreshRequest(refreshToken)) }
        }
        sessionStore.clear()
    }
}
