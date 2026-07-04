package ru.savefood.app.feature.needy.find

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import ru.savefood.app.R
import ru.savefood.app.core.designsystem.component.EmptyState
import ru.savefood.app.core.designsystem.component.LotCard
import ru.savefood.app.core.designsystem.component.SectionHeader
import ru.savefood.app.core.designsystem.component.ShimmerListItem
import ru.savefood.app.core.device.map.MapMarker
import ru.savefood.app.core.device.map.YandexMap
import ru.savefood.app.feature.needy.data.LotDto
import ru.savefood.app.feature.needy.data.NeedyRepository

private const val ROUTE_LIST = "find/list"
private const val ROUTE_WIZARD = "find/wizard"

/**
 * "Find food" tab: list/map of active lots + a request wizard (nested nav).
 *
 * Wrapped in a [SharedTransitionLayout] so tapping a lot card runs a container
 * transform — the card morphs into the wizard's lot summary (shared key
 * `lot-<id>`) — instead of a hard screen swap.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FindFoodScreen(viewModel: FindFoodViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    SharedTransitionLayout {
        NavHost(
            navController = nav,
            startDestination = ROUTE_LIST,
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(180)) },
        ) {
            composable(ROUTE_LIST) {
                LotsScreen(
                    viewModel = viewModel,
                    onRequest = { lotId -> nav.navigate("$ROUTE_WIZARD?lotId=$lotId") },
                    sharedScope = this@SharedTransitionLayout,
                    animatedScope = this@composable,
                )
            }
            composable(
                route = "$ROUTE_WIZARD?lotId={lotId}",
                arguments = listOf(navArgument("lotId") { type = NavType.IntType; defaultValue = -1 }),
            ) { entry ->
                val lotId = entry.arguments?.getInt("lotId")?.takeIf { it >= 0 }
                TicketWizardScreen(
                    lot = viewModel.lotById(lotId),
                    viewModel = viewModel,
                    onDone = { nav.popBackStack() },
                    onCancel = { nav.popBackStack() },
                    sharedScope = this@SharedTransitionLayout,
                    animatedScope = this@composable,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun LotsScreen(
    viewModel: FindFoodViewModel,
    onRequest: (Int?) -> Unit,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SectionHeader(title = stringResource(R.string.needy_find_title))

        OutlinedTextField(
            value = state.search,
            onValueChange = viewModel::onSearchChange,
            label = { Text(stringResource(R.string.needy_find_search_hint)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )

        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.needy_find_tab_list)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.needy_find_tab_map)) })
        }

        when {
            state.loading -> Column(
                modifier = Modifier.padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { repeat(3) { ShimmerListItem() } }

            state.error != null -> EmptyState(
                icon = Icons.Filled.Restaurant,
                title = stringResource(R.string.common_error_generic),
                description = state.error,
                actionLabel = stringResource(R.string.needy_retry),
                onAction = viewModel::load,
            )

            state.lots.isEmpty() -> EmptyState(
                icon = Icons.Filled.Restaurant,
                title = stringResource(R.string.needy_find_empty_title),
                description = stringResource(R.string.needy_find_empty_desc),
            )

            tab == 0 -> LotList(state.lots, onRequest, sharedScope, animatedScope)
            else -> LotMap(state.lots, onRequest)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun LotList(
    lots: List<LotDto>,
    onRequest: (Int?) -> Unit,
    sharedScope: SharedTransitionScope,
    animatedScope: AnimatedVisibilityScope,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        items(lots, key = { it.id }) { lot ->
            with(sharedScope) {
                LotCard(
                    title = lot.description ?: "#${lot.id}",
                    category = lot.category ?: "—",
                    quantity = lot.quantity?.let { "${it.toInt()} ${lot.unit ?: ""}".trim() } ?: "",
                    expiry = lot.expiryDate ?: lot.timeSlot ?: "",
                    address = lot.address ?: lot.shopName ?: "",
                    imageUrl = NeedyRepository.absoluteUrl(lot.photo),
                    onClick = { onRequest(lot.id) },
                    modifier = Modifier.sharedBounds(
                        rememberSharedContentState(key = "lot-${lot.id}"),
                        animatedVisibilityScope = animatedScope,
                    ),
                )
            }
        }
    }
}

@Composable
private fun LotMap(lots: List<LotDto>, onRequest: (Int?) -> Unit) {
    val markers = lots.mapNotNull { lot ->
        val lat = lot.shopLat
        val lon = lot.shopLon
        if (lat != null && lon != null) {
            MapMarker(id = lot.id.toString(), latitude = lat, longitude = lon, title = lot.description)
        } else null
    }
    Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
        YandexMap(
            markers = markers,
            modifier = Modifier.fillMaxSize(),
            onMarkerClick = { id -> onRequest(id.toIntOrNull()) },
        )
    }
}
