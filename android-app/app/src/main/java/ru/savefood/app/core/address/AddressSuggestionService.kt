package ru.savefood.app.core.address

import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SuggestOptions
import com.yandex.mapkit.search.SuggestResponse
import com.yandex.mapkit.search.SuggestSession
import com.yandex.mapkit.search.SuggestType
import com.yandex.runtime.Error
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ru.savefood.app.core.device.map.MapKitStatus
import kotlin.coroutines.resume

@Singleton
class AddressSuggestionService @Inject constructor() {
    suspend fun suggest(query: String): List<String> = withContext(Dispatchers.Main.immediate) {
        val normalized = query.trim()
        if (normalized.length < MIN_QUERY_LENGTH || !MapKitStatus.isReady) {
            return@withContext emptyList()
        }

        suspendCancellableCoroutine { continuation ->
            val session = SearchFactory.getInstance()
                .createSearchManager(SearchManagerType.ONLINE)
                .createSuggestSession()
            continuation.invokeOnCancellation { session.reset() }
            session.suggest(
                normalized,
                WORLD_BOUNDS,
                SuggestOptions().setSuggestTypes(SuggestType.GEO.value),
                object : SuggestSession.SuggestListener {
                    override fun onResponse(response: SuggestResponse) {
                        if (!continuation.isActive) return
                        val suggestions = response.items.mapNotNull { item ->
                            item.displayText?.takeIf(String::isNotBlank)
                                ?: item.title?.text?.takeIf(String::isNotBlank)
                        }.distinct().take(MAX_RESULTS)
                        continuation.resume(suggestions)
                    }

                    override fun onError(error: Error) {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                },
            )
        }
    }

    companion object {
        private const val MIN_QUERY_LENGTH = 3
        private const val MAX_RESULTS = 5
        private val WORLD_BOUNDS = BoundingBox(
            Point(-85.0, -180.0),
            Point(85.0, 180.0),
        )
    }
}
