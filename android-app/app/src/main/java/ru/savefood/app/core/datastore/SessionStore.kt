package ru.savefood.app.core.datastore
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
private val Context.dataStore by preferencesDataStore(name = "savefood_session")
/** Auth role as carried in the JWT (`role` claim). */
enum class UserRole { SHOP, VOLUNTEER, NEEDY, ADMIN, UNKNOWN;
    companion object {
        fun from(raw: String?): UserRole = when (raw?.lowercase()) {
            "shop" -> SHOP
            "volunteer" -> VOLUNTEER
            "needy" -> NEEDY
            "admin" -> ADMIN
            else -> UNKNOWN
        }
    }
}
data class Session(
    val token: String,
    val refreshToken: String,
    val role: UserRole,
    val relatedId: Int?,
)
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
)
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenCipher: TokenCipher,
) {
    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val ROLE = stringPreferencesKey("role")
        val RELATED_ID = intPreferencesKey("related_id")
    }
    val sessionFlow: Flow<Session?> = context.dataStore.data.map { prefs ->
        val token = prefs[Keys.TOKEN]?.let(tokenCipher::decrypt) ?: return@map null
        val refreshToken = prefs[Keys.REFRESH_TOKEN]?.let(tokenCipher::decrypt) ?: return@map null
        Session(
            token = token,
            refreshToken = refreshToken,
            role = UserRole.from(prefs[Keys.ROLE]),
            relatedId = prefs[Keys.RELATED_ID],
        )
    }
    /** Current token read synchronously for OkHttp interceptors (called off the main thread). */
    suspend fun currentToken(): String? = context.dataStore.data.first()[Keys.TOKEN]?.let(tokenCipher::decrypt)
    suspend fun currentTokenPair(): TokenPair? {
        val prefs = context.dataStore.data.first()
        val accessToken = prefs[Keys.TOKEN]?.let(tokenCipher::decrypt) ?: return null
        val refreshToken = prefs[Keys.REFRESH_TOKEN]?.let(tokenCipher::decrypt) ?: return null
        return TokenPair(accessToken, refreshToken)
    }
    suspend fun save(token: String, refreshToken: String, role: String, relatedId: Int?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TOKEN] = tokenCipher.encrypt(token)
            prefs[Keys.REFRESH_TOKEN] = tokenCipher.encrypt(refreshToken)
            prefs[Keys.ROLE] = role
            if (relatedId != null) prefs[Keys.RELATED_ID] = relatedId else prefs.remove(Keys.RELATED_ID)
        }
    }
    suspend fun replaceTokenPair(expectedRefreshToken: String, replacement: TokenPair): Boolean {
        var replaced = false
        context.dataStore.edit { prefs ->
            val currentRefresh = prefs[Keys.REFRESH_TOKEN]?.let(tokenCipher::decrypt)
            if (currentRefresh == expectedRefreshToken) {
                prefs[Keys.TOKEN] = tokenCipher.encrypt(replacement.accessToken)
                prefs[Keys.REFRESH_TOKEN] = tokenCipher.encrypt(replacement.refreshToken)
                replaced = true
            }
        }
        return replaced
    }
    /** Clear only the session whose refresh credential was rejected. */
    suspend fun clearIfRefreshToken(expectedRefreshToken: String): Boolean {
        var cleared = false
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.REFRESH_TOKEN]?.let(tokenCipher::decrypt)
            if (current == expectedRefreshToken) {
                prefs.clear()
                cleared = true
            }
        }
        return cleared
    }
    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
