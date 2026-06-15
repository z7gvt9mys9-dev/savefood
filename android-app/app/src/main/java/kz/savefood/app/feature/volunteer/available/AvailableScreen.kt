package kz.savefood.app.feature.volunteer.available

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kz.savefood.app.R
import kz.savefood.app.core.designsystem.component.BadgeTone
import kz.savefood.app.core.designsystem.component.EmptyState
import kz.savefood.app.core.designsystem.component.LotCard
import kz.savefood.app.core.designsystem.component.SectionHeader
import kz.savefood.app.core.designsystem.component.ShimmerListItem
import kz.savefood.app.core.device.map.MapMarker
import kz.savefood.app.core.device.map.YandexMap
import kz.savefood.app.feature.volunteer.data.LotDto
import kz.savefood.app.feature.volunteer.data.MapTicketDto
import kz.savefood.app.feature.volunteer.data.VolunteerRepository
import kz.savefood.app.feature.volunteer.ui.VolunteerConfirmDialog

/**
 * "Available" tab: list/map of open lots with delivery requests. The dominant
 * action is claiming a lot, which immediately starts a route. [onRouteStarted]
 * lets the shell jump the user to the route tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AvailableScreen(
    onRouteStarted: () -> Unit,
    viewModel: AvailableViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }
    var confirmLot by remember { mutableStateOf<LotDto?>(null) }

    LaunchedEffect(state.startError) {
        state.startError?.let {
            snackbar.showSnackbar(it)
            viewModel.clearStartError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            SectionHeader(title = stringResource(R.string.vol_available_title))

            if (state.openTickets.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.vol_available_open_tickets, state.openTickets.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            OutlinedTextField(
                value = state.search,
                onValueChange = viewModel::onSearchChange,
                label = { Text(stringResource(R.string.vol_available_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )

            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.vol_available_tab_list)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.vol_available_tab_map)) })
            }

            when {
                state.loading -> Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) { repeat(3) { ShimmerListItem() } }

                state.error != null -> EmptyState(
                    icon = Icons.Filled.LocalShipping,
                    title = stringResource(R.string.common_error_generic),
                    description = state.error,
                    actionLabel = stringResource(R.string.common_retry),
                    onAction = viewModel::load,
                )

                state.lots.isEmpty() -> EmptyState(
                    icon = Icons.Filled.LocalShipping,
                    title = stringResource(R.string.vol_available_empty_title),
                    description = stringResource(R.string.vol_available_empty_desc),
                )

                tab == 0 -> LotList(
                    lots = state.lots,
                    startingLotId = state.startingLotId,
                    onTake = { confirmLot = it },
                )

                else -> LotMap(state.lots, state.openTickets, onTake = { confirmLot = it })
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    confirmLot?.let { lot ->
        VolunteerConfirmDialog(
            title = stringResource(R.string.vol_available_start_title),
            text = stringResource(R.string.vol_available_start_desc, lot.description ?: "#${lot.id}"),
            confirmLabel = stringResource(R.string.vol_available_take),
            onConfirm = {
                confirmLot = null
                viewModel.startRoute(lot.id) { onRouteStarted() }
            },
            onDismiss = { confirmLot = null },
        )
    }
}

@Composable
private fun LotList(
    lots: List<LotDto>,
    startingLotId: Int?,
    onTake: (LotDto) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
    ) {
        items(lots, key = { it.id }) { lot ->
            LotCard(
                title = lot.description ?: "#${lot.id}",
                category = lot.category ?: "—",
                quantity = lot.quantity?.let { "${it.toInt()} ${lot.unit ?: ""}".trim() } ?: "",
                expiry = lot.expiryDate ?: lot.timeSlot ?: "",
                address = lot.address ?: lot.shopName ?: "",
                imageUrl = VolunteerRepository.absoluteUrl(lot.photo),
                statusText = if (lot.requiresCold == true) stringResource(R.string.vol_available_cold_badge) else null,
                statusTone = BadgeTone.PENDING,
                onClick = { if (startingLotId == null) onTake(lot) },
            )
        }
    }
}

@Composable
private fun LotMap(
    lots: List<LotDto>,
    tickets: List<MapTicketDto>,
    onTake: (LotDto) -> Unit,
) {
    val lotMarkers = lots.mapNotNull { lot ->
        val lat = lot.shopLat
        val lon = lot.shopLon
        if (lat != null && lon != null) {
            MapMarker(id = "lot:${lot.id}", latitude = lat, longitude = lon, title = lot.description)
        } else null
    }
    val ticketMarkers = tickets.mapNotNull { t ->
        val lat = t.lat
        val lon = t.lon
        if (lat != null && lon != null) {
            MapMarker(id = "ticket:${t.ticketId}", latitude = lat, longitude = lon, title = t.items)
        } else null
    }
    Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
        YandexMap(
            markers = lotMarkers + ticketMarkers,
            modifier = Modifier.fillMaxSize(),
            onMarkerClick = { id ->
                // Only lot pins start a route; ticket pins are an awareness overlay.
                id.removePrefix("lot:").toIntOrNull()
                    ?.takeIf { id.startsWith("lot:") }
                    ?.let { lotId -> lots.firstOrNull { it.id == lotId }?.let(onTake) }
            },
        )
    }
}
