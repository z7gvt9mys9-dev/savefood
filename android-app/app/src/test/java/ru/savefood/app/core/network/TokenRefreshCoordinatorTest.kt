package ru.savefood.app.core.network

import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.savefood.app.core.datastore.TokenPair

class TokenRefreshCoordinatorTest {

    @Test
    fun validTokenContinuesWithoutRefresh() = runBlocking {
        val now = AtomicLong(1_000)
        val oldToken = jwt(exp = 2_000)
        val store = FakeTokenStore(pair(oldToken, "refresh-old"))
        val refreshes = AtomicInteger()
        val coordinator = coordinator(store, now) {
            refreshes.incrementAndGet()
            RefreshAttempt.Success(pair(jwt(exp = 5_000), "refresh-new"))
        }

        assertEquals(oldToken, coordinator.tokenForRequest())
        assertEquals(0, refreshes.get())
        assertFalse(store.cleared)
    }

    @Test
    fun tokenApproachingExpiryRefreshesBeforeExpiration() = runBlocking {
        val now = AtomicLong(1_000)
        val oldToken = jwt(exp = 1_200, marker = "old")
        val newToken = jwt(exp = 5_000, marker = "new")
        val store = FakeTokenStore(pair(oldToken, "refresh-old"))
        val coordinator = coordinator(store, now) { token ->
            assertEquals("refresh-old", token)
            RefreshAttempt.Success(pair(newToken, "refresh-new"))
        }

        assertEquals(newToken, coordinator.tokenForRequest())
        assertEquals(pair(newToken, "refresh-new"), store.tokenPair)
    }

    @Test
    fun sessionSurvivesCrossingOriginalAccessTokenExpiry() = runBlocking {
        val now = AtomicLong(1_000)
        val oldToken = jwt(exp = 1_200, marker = "old")
        val newToken = jwt(exp = 5_000, marker = "new")
        val store = FakeTokenStore(pair(oldToken, "refresh-old"))
        val refreshes = AtomicInteger()
        val coordinator = coordinator(store, now) {
            refreshes.incrementAndGet()
            RefreshAttempt.Success(pair(newToken, "refresh-new"))
        }

        assertEquals(newToken, coordinator.tokenForRequest())
        now.set(1_201)
        assertEquals(newToken, coordinator.tokenForRequest())
        assertEquals(1, refreshes.get())
        assertFalse(store.cleared)
    }

    @Test
    fun dormantPastAccessExpiryRefreshesWithIndependentCredential() = runBlocking {
        val now = AtomicLong(2_000)
        val expiredAccess = jwt(exp = 1_500, marker = "expired")
        val newAccess = jwt(exp = 5_000, marker = "new")
        val store = FakeTokenStore(pair(expiredAccess, "still-valid-refresh"))
        val coordinator = coordinator(store, now) { refreshToken ->
            assertEquals("still-valid-refresh", refreshToken)
            RefreshAttempt.Success(pair(newAccess, "rotated-refresh"))
        }

        assertEquals(newAccess, coordinator.tokenForRequest())
        assertEquals(pair(newAccess, "rotated-refresh"), store.tokenPair)
        assertFalse(store.cleared)
    }

    @Test
    fun concurrentRequestsWaitForOneRefresh() {
        val now = AtomicLong(1_000)
        val oldToken = jwt(exp = 1_200, marker = "old")
        val newToken = jwt(exp = 5_000, marker = "new")
        val store = FakeTokenStore(pair(oldToken, "refresh-old"))
        val refreshes = AtomicInteger()
        val coordinator = coordinator(store, now) {
            refreshes.incrementAndGet()
            Thread.sleep(75)
            RefreshAttempt.Success(pair(newToken, "refresh-new"))
        }
        val workers = 8
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)

        try {
            val results = (1..workers).map {
                executor.submit<String?> {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    runBlocking { coordinator.tokenForRequest() }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()

            assertTrue(results.all { it.get(5, TimeUnit.SECONDS) == newToken })
            assertEquals(1, refreshes.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun invalidRefreshResponseClearsSessionSafely() = runBlocking {
        val now = AtomicLong(1_000)
        val store = FakeTokenStore(pair(jwt(exp = 1_200), "refresh-old"))
        val coordinator = coordinator(store, now) { RefreshAttempt.InvalidResponse }

        assertNull(coordinator.tokenForRequest())
        assertNull(store.tokenPair)
        assertTrue(store.cleared)
    }

    @Test
    fun rejectedRefreshClearsSessionButTransientFailureDoesNot() = runBlocking {
        val now = AtomicLong(1_000)
        val rejectedStore = FakeTokenStore(
            pair(jwt(exp = 1_200, marker = "rejected"), "refresh-rejected"),
        )
        val rejected = coordinator(rejectedStore, now) {
            RefreshAttempt.AuthenticationRejected
        }
        assertNull(rejected.tokenForRequest())
        assertTrue(rejectedStore.cleared)

        val transientToken = jwt(exp = 1_200, marker = "transient")
        val transientStore = FakeTokenStore(pair(transientToken, "refresh-transient"))
        val transient = coordinator(transientStore, now) { RefreshAttempt.TransientFailure }
        assertEquals(transientToken, transient.tokenForRequest())
        assertFalse(transientStore.cleared)
    }

    @Test
    fun transientFailureWithExpiredAccessKeepsRefreshSessionForLaterRetry() = runBlocking {
        val now = AtomicLong(2_000)
        val store = FakeTokenStore(pair(jwt(exp = 1_500), "refresh-still-valid"))
        val coordinator = coordinator(store, now) { RefreshAttempt.TransientFailure }

        assertNull(coordinator.tokenForRequest())
        assertEquals("refresh-still-valid", store.tokenPair?.refreshToken)
        assertFalse(store.cleared)
    }

    @Test
    fun rotatedAccessAndRefreshTokensArePersistedTogether() = runBlocking {
        val now = AtomicLong(1_000)
        val oldPair = pair(jwt(exp = 1_100, marker = "old"), "refresh-old")
        val newPair = pair(jwt(exp = 5_000, marker = "new"), "refresh-new")
        val store = FakeTokenStore(oldPair)
        val coordinator = coordinator(store, now) { RefreshAttempt.Success(newPair) }

        assertEquals(newPair.accessToken, coordinator.tokenForRequest())
        assertEquals(newPair, store.tokenPair)
        assertEquals(1, store.replacements)
    }

    @Test
    fun refreshedRequestUsesNewToken() {
        val newToken = jwt(exp = 5_000, marker = "new")
        val captured = AtomicReference<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor { newToken })
            .addInterceptor(Interceptor { chain ->
                captured.set(chain.request())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody())
                    .build()
            })
            .build()

        client.newCall(Request.Builder().url("https://savefood.test/lots").build())
            .execute().close()

        assertEquals("Bearer $newToken", captured.get().header("Authorization"))
    }

    @Test
    fun authenticatorRetriesOnceAndDoesNotLoop() {
        val calls = AtomicInteger()
        val authenticator = TokenAuthenticator {
            calls.incrementAndGet()
            "new-token"
        }
        val original = Request.Builder()
            .url("https://savefood.test/lots")
            .header("Authorization", "Bearer old-token")
            .build()
        val first401 = unauthorized(original)

        val retry = authenticator.authenticate(null, first401)
        assertEquals("Bearer new-token", retry?.header("Authorization"))

        val second401 = unauthorized(retry!!, first401)
        assertNull(authenticator.authenticate(null, second401))
        assertEquals(1, calls.get())
    }

    private fun coordinator(
        store: FakeTokenStore,
        now: AtomicLong,
        refresh: (String) -> RefreshAttempt,
    ) = TokenRefreshCoordinator(
        currentTokenPair = { store.tokenPair },
        replaceTokenPair = store::replace,
        clearTokenPair = store::clear,
        refreshToken = refresh,
        nowEpochSeconds = now::get,
    )

    private class FakeTokenStore(initialTokenPair: TokenPair?) {
        @Volatile var tokenPair: TokenPair? = initialTokenPair
        @Volatile var cleared: Boolean = false
        @Volatile var replacements: Int = 0

        suspend fun replace(expected: String, replacement: TokenPair): Boolean = synchronized(this) {
            if (tokenPair?.refreshToken != expected) return@synchronized false
            tokenPair = replacement
            replacements++
            true
        }

        suspend fun clear(expected: String): Boolean = synchronized(this) {
            if (tokenPair?.refreshToken != expected) return@synchronized false
            tokenPair = null
            cleared = true
            true
        }
    }

    private fun pair(accessToken: String, refreshToken: String) =
        TokenPair(accessToken, refreshToken)

    private fun unauthorized(request: Request, prior: Response? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .priorResponse(prior)
            .build()

    private fun jwt(exp: Long, marker: String = "token"): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"HS256\"}".toByteArray())
        val payload = encoder.encodeToString(
            "{\"exp\":$exp,\"marker\":\"$marker\"}".toByteArray(),
        )
        return "$header.$payload.signature"
    }
}
