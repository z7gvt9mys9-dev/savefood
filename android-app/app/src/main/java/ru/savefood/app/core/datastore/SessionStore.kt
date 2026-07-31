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
    val role: UserRole,
    val relatedId: Int?,
)

/**
 * Persists the auth session (JWT, role, related_id) in DataStore. The token is
 * encrypted with a non-exportable Android Keystore key before it is persisted.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenCipher: TokenCipher,
) {
    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val ROLE = stringPreferencesKey("role")
        val RELATED_ID = intPreferencesKey("related_id")
    }

    val sessionFlow: Flow<Session?> = context.dataStore.data.map { prefs ->
        val token = prefs[Keys.TOKEN]?.let(tokenCipher::decrypt) ?: return@map null
        Session(
            token = token,
            role = UserRole.from(prefs[Keys.ROLE]),
            relatedId = prefs[Keys.RELATED_ID],
        )
    }

    /** Current token read synchronously for OkHttp interceptors (called off the main thread). */
    suspend fun currentToken(): String? = context.dataStore.data.first()[Keys.TOKEN]?.let(tokenCipher::decrypt)

    suspend fun save(token: String, role: String, relatedId: Int?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TOKEN] = tokenCipher.encrypt(token)
            prefs[Keys.ROLE] = role
            if (relatedId != null) prefs[Keys.RELATED_ID] = relatedId else prefs.remove(Keys.RELATED_ID)
        }
    }

    /** Replace just the access token (used after a silent refresh). */
    suspend fun updateToken(token: String) {
        context.dataStore.edit { it[Keys.TOKEN] = tokenCipher.encrypt(token) }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}
