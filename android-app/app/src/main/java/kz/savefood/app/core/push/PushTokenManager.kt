package kz.savefood.app.core.push

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kz.savefood.app.core.datastore.Session
import kz.savefood.app.core.datastore.SessionStore
import kz.savefood.app.core.datastore.UserRole
import kz.savefood.app.core.push.dto.FcmRegisterRequest
import kz.savefood.app.core.push.dto.FcmUnregisterRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Registers/unregisters this device's FCM token with the backend, tied to the
 * lifecycle of the auth session:
 *  - [registerCurrentToken] on login and from [SaveFoodMessagingService.onNewToken];
 *  - [unregisterCurrentToken] on logout (before the session token is cleared, so
 *    the request is still authenticated).
 *
 * Every public method is best-effort: any failure (Firebase absent, offline,
 * server error) is swallowed so it never blocks the auth flow that triggered it.
 */
@Singleton
class PushTokenManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionStore: SessionStore,
    private val pushApi: PushApi,
) {

    /** Fetch the current FCM token and register it for the active session. */
    suspend fun registerCurrentToken() {
        val session = activeSession() ?: return
        val token = currentToken() ?: return
        register(token, session)
    }

    /** Register a freshly-rotated [token] (from onNewToken) for the active session. */
    suspend fun registerNewToken(token: String) {
        val session = activeSession() ?: return
        register(token, session)
    }

    /** Unregister this device's token; call while still logged in. */
    suspend fun unregisterCurrentToken() {
        if (sessionStore.currentToken() == null) return
        val token = currentToken() ?: return
        runCatching { pushApi.unregister(FcmUnregisterRequest(token)) }
            .onFailure { Log.w(TAG, "FCM unregister failed", it) }
    }

    private suspend fun register(token: String, session: Session) {
        val role = backendRole(session.role) ?: return
        runCatching {
            pushApi.register(FcmRegisterRequest(token, role, session.relatedId))
        }.onFailure { Log.w(TAG, "FCM register failed", it) }
    }

    /** Session, but only when it belongs to a role that receives push (not admin). */
    private suspend fun activeSession(): Session? =
        sessionStore.sessionFlow.first()?.takeIf { backendRole(it.role) != null }

    /** Raw backend role string, or null for roles without a mobile push target. */
    private fun backendRole(role: UserRole): String? = when (role) {
        UserRole.SHOP, UserRole.VOLUNTEER, UserRole.NEEDY -> role.name.lowercase()
        UserRole.ADMIN, UserRole.UNKNOWN -> null
    }

    /** Current FCM token, or null if Firebase isn't configured / token fetch fails. */
    private suspend fun currentToken(): String? {
        if (!isFirebaseAvailable(context)) return null
        return runCatching { fetchFcmToken() }
            .onFailure { Log.w(TAG, "FCM getToken failed", it) }
            .getOrNull()
    }

    private suspend fun fetchFcmToken(): String =
        suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> cont.resume(token) }
                .addOnFailureListener { e -> cont.resumeWithException(e) }
        }

    private companion object {
        const val TAG = "PushTokenManager"
    }
}
