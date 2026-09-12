package ru.savefood.app.core.address

import android.content.Context
import android.location.Geocoder
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SuggestOptions
import com.yandex.mapkit.search.SuggestResponse
import com.yandex.mapkit.search.SuggestSession
import com.yandex.mapkit.search.SuggestType
import com.yandex.runtime.Error
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import ru.savefood.app.core.device.map.MapKitStatus
import java.util.Locale
import kotlin.coroutines.resume

@Singleton
class AddressSuggestionService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun suggest(query: String): List<String> {
        val normalized = query.trim()
        if (normalized.length < MIN_QUERY_LENGTH) return emptyList()

        val mapKitSuggestions = if (MapKitStatus.isReady) mapKitSuggest(normalized) else emptyList()
        return mapKitSuggestions.ifEmpty { platformSuggest(normalized) }
    }

    private suspend fun mapKitSuggest(query: String): List<String> =
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                suspendCancellableCoroutine { continuation ->
                    val session = SearchFactory.getInstance()
                        .createSearchManager(SearchManagerType.ONLINE)
                        .createSuggestSession()
                    continuation.invokeOnCancellation { session.reset() }
                    session.suggest(
                        query,
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
            }.getOrDefault(emptyList())
        }

    private suspend fun platformSuggest(query: String): List<String> = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext emptyList()
        runCatching {
            Geocoder(context, Locale.getDefault())
                .getFromLocationName(query, MAX_RESULTS)
                .orEmpty()
                .mapNotNull { address -> address.getAddressLine(0)?.takeIf(String::isNotBlank) }
                .distinct()
        }.getOrDefault(emptyList())
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
